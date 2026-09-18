package com.xiaoyv.bangumi.shared.libnative.ech

import android.util.Base64
import com.xiaoyv.bangumi.shared.libnative.application
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 从构建注入的网关获取 DNS 与 ECH 配置。空配置和解析失败均阻断（fail-closed）。
 *
 * 冷启动策略：**上次成功的结果会落盘，下次启动直接拿它开路**（内存缓存 → 落盘缓存 → 在线查询），
 * 同时后台去取在线数据覆盖它——首屏不再等 DoH。缓存里的配置若已过期（握手失败），
 * [BgmEchTransport] 会调用 [invalidateConfig] 丢掉它并立刻改用在线的配置重试一次。
 *
 * 在线查询：按主机加锁合并并发；失败先换端点重试一次，仍失败才写 5 分钟冷却（持久化，重启不绕过）。
 */
private val HINT_PATTERN = Regex("ipv4hint=([0-9.,]+)")

/** 失败后换端点重试的等待时间；只重试一次。 */
private const val RETRY_DELAY_MILLIS = 400L

/**
 * 落盘 ECH 配置的使用上限：**不能超过 5 小时**（CF 侧密钥约 5 小时轮换）。
 *
 * 拿过期的配置去握手是匹配不上的，所以超过这个时长就不再使用，
 * 而是重新取一次在线配置并缓存下来。用缓存开路时也会同时后台刷新。
 * 另外 [BgmEchTransport] 会兜底：握手被拒 → 丢弃配置 → 重取在线配置 → 重试一次。
 */
private const val CACHED_CONFIG_MAX_AGE_MILLIS = 5 * 60 * 60 * 1000L

/** 地址不参与密钥轮换，但同样按不超过 5 小时缓存（过期就重取一次）。 */
private const val CACHED_ADDRESS_MAX_AGE_MILLIS = 5 * 60 * 60 * 1000L

/** 用缓存开路时在内存里的占位时长；后台拿到在线数据就覆盖它。 */
private const val CACHED_HOLD_MILLIS = 15 * 60 * 1000L

/**
 * 配置的唯一"活源"：CF 官方的 ECH 域名 `cloudflare-ech.com`。
 *
 * 它是 CF 自动维护的（随查随新），而"自己手写/注入到别处"的记录一旦过期，**再拉还是那份旧的**，
 * 拿它去握手只会被服务器拒绝。所以受保护域名一律先取这份配置（跨 zone 注入实测可行：
 * 内层 SNI 仍是目标域名，SNI 依旧不外泄），只在它拿不到时才退回该域名自己的记录。
 *
 * 实测（2026-09-18，App 同款 Conscrypt 栈）：这份配置对 i.pximg.net / pixiv.net / bgm.tv /
 * api.bgm.tv / anime-pictures.net / xget 全部握手成功。
 */
private const val REFERENCE_ECH_HOST = "cloudflare-ech.com"

/** 同一主机后台刷新在线数据的最小间隔，避免网关抖动时反复重试。 */
private const val REFRESH_MIN_INTERVAL_MILLIS = 60 * 1000L

/** DNS-over-HTTPS 响应里的 ECH 字段。 */
private val REGEX_ECH = Regex("(?:^|\\s)ech=\"?([A-Za-z0-9+/=]+)")

internal object BgmEchDoh {
    private data class Entry<T>(val value: T, val until: Long)

    /** 网关条目：URL 与其自带地址。受污染的网络里不能用系统 DNS 解析网关域名。 */
    private data class Endpoint(val url: HttpUrl, val addresses: List<InetAddress>)
    private val addresses = ConcurrentHashMap<String, Entry<List<InetAddress>>>()
    /** ECH 记录里的 ipv4hint：CF 建议配合 ECH 使用的地址，常与 A 记录不是同一组。 */
    private val hints = ConcurrentHashMap<String, Entry<List<InetAddress>>>()
    private val configs = ConcurrentHashMap<String, Entry<ByteArray>>()
    /**
     * 每个主机一把锁：不同主机的 DoH 查询互不排队。
     * 以前是对象级锁，一屏图片会一个个等着取地址，这是"加载慢"的主因之一。
     */
    private val hostLocks = ConcurrentHashMap<String, Any>()
    private fun lockFor(host: String): Any = hostLocks.computeIfAbsent(host) { Any() }
    private fun now(): Long = java.lang.System.currentTimeMillis()
    private val preferences by lazy { application.getSharedPreferences("ech_doh_state", 0) }
    /**
     * 网关池格式 `https://网关/路径|IP|IP`，逗号分隔多条。
     * 每条必须自带 IP：缺 IP 直接阻断，绝不用系统 DNS 解析网关。
     */
    private val endpoints by lazy {
        val id = application.resources.getIdentifier("ech_doh_pool", "string", application.packageName)
        if (id == 0) emptyList() else application.getString(id).split(',')
            .map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
                val parts = entry.split('|').map { it.trim() }
                val url = parts.first().toHttpUrl()
                require(url.isHttps && url.username.isEmpty() && url.password.isEmpty())
                require(!BgmEchPolicy.isProtected(url.host))
                if (parts.size < 2) throw IOException("ECH 网关缺少自有 IP，已阻断")
                Endpoint(url, parts.drop(1).map { parseIpv4Literal(it) })
            }
    }
    private val client by lazy {
        OkHttpClient.Builder()
            .dns { host ->
                val own = endpoints.filter { it.url.host == host }.flatMap { it.addresses }.distinct()
                if (own.isEmpty()) throw IOException("ECH 网关没有自身地址，禁止系统解析")
                own
            }
            .proxy(java.net.Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val refreshAt = ConcurrentHashMap<String, Long>()
    private val refreshPool by lazy {
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "ech-doh-refresh").apply { isDaemon = true }
        }
    }

    /**
     * 已经用缓存开路，这里在后台取在线数据并覆盖缓存。
     * bestEffort：刷新失败**不写冷却**，否则一次抖动会把启动路径整个拖垮。
     */
    private fun refreshLater(host: String) {
        val last = refreshAt[host] ?: 0L
        if (now() - last < REFRESH_MIN_INTERVAL_MILLIS) return
        refreshAt[host] = now()
        runCatching {
            refreshPool.execute {
                runCatching { fetchAddresses(host, bestEffort = true) }
                runCatching { fetchConfig(host, bestEffort = true) }
            }
        }
    }

    /**
     * 该主机是否"优先使用 ECH 记录里的地址"。
     * A 记录的地址（含优选 IP）如果不接受本 zone 的 ECH 配置，就会被服务器拒绝；
     * 这种情况下改用 HTTPS 记录里的 ipv4hint（CF 为 ECH 推荐的地址）。
     */
    fun preferHints(hostname: String): Boolean =
        preferences.getBoolean("hintfirst:${hostname.lowercase(Locale.ROOT).trimEnd('.')}", false)

    /**
     * 该域名是否改用"它自己的记录"优先。
     *
     * 默认走官方源 [REFERENCE_ECH_HOST]；只有当官方那份被服务器拒绝过（个别 zone 不吃跨 zone 注入），
     * 才把它翻过来用该域名自己的记录，免得一直拿同一份撞。
     */
    fun ownRecordFirst(hostname: String): Boolean = preferences.getBoolean(
        "ownfirst:${hostname.lowercase(Locale.ROOT).trimEnd('.')}", false,
    )

    fun markOwnRecordFirst(hostname: String) {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        if (ownRecordFirst(host)) return
        preferences.edit().putBoolean("ownfirst:$host", true).commit()
    }

    fun markPreferHints(hostname: String) {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        if (preferHints(host)) return
        preferences.edit().putBoolean("hintfirst:$host", true).commit()
    }

    /**
     * 丢掉某主机的 **ECH 配置与 hints**（内存 + 落盘）。用于"缓存里的配置已过期或不被接受"。
     * 地址保留：重试时还能直接复用，只有配置这一步需要重新取在线数据。
     */
    fun invalidateConfig(hostname: String) {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        // 官方源那份也一起丢掉：它可能就是被拒的那一份，留着会让重试拿回同一个值。
        synchronized(lockFor(REFERENCE_ECH_HOST)) {
            configs.remove(REFERENCE_ECH_HOST)
            hints.remove(REFERENCE_ECH_HOST)
            preferences.edit()
                .remove("cfg:$REFERENCE_ECH_HOST").remove("cfg:$REFERENCE_ECH_HOST.at")
                .remove("hint:$REFERENCE_ECH_HOST").remove("hint:$REFERENCE_ECH_HOST.at")
                .commit()
        }
        synchronized(lockFor(host)) {
            configs.remove(host)
            hints.remove(host)
            preferences.edit()
                .remove("cfg:$host").remove("cfg:$host.at")
                .remove("hint:$host").remove("hint:$host.at")
                .commit()
            refreshAt.remove(host)
        }
    }

    fun resolve(hostname: String): List<InetAddress> = resolve(hostname, bestEffort = false)

    private fun resolve(hostname: String, bestEffort: Boolean): List<InetAddress> {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        synchronized(lockFor(host)) {
            addresses[host]?.let { if (it.until > now()) return it.value }
            // 冷启动优先：上次成功过的地址先拿来开路，在线数据交给后台替换。
            persistedAddresses(host)?.let { cached ->
                addresses[host] = Entry(cached, now() + CACHED_HOLD_MILLIS)
                persistedHints(host)?.let { stored -> hints[host] = Entry(stored, now() + CACHED_HOLD_MILLIS) }
                refreshLater(host)
                return cached
            }
            return try {
                fetchAddresses(host, bestEffort)
            } catch (error: Exception) {
                if (bestEffort) throw error
                // 在线也拿不到：仍用上次成功的地址，绝不回落系统解析（那是污染源）。
                persistedAddresses(host) ?: throw error
            }
        }
    }

    /** 在线取 A 记录并落缓存。失败语义由 [bestEffort] 决定（见 [guarded]）。 */
    private fun fetchAddresses(host: String, bestEffort: Boolean): List<InetAddress> {
        val pool = endpoints
        return guarded(bestEffort) {
            val json = fetch(pool, host, "A")
            val answer = json.getJSONArray("Answer")
            val result = mutableListOf<InetAddress>()
            var ttl = 300L
            for (i in 0 until answer.length()) {
                val item = answer.getJSONObject(i)
                if (item.optInt("type") != 1) continue
                result += parseIpv4Literal(item.getString("data"))
                ttl = minOf(ttl, item.getLong("TTL").coerceAtLeast(0))
            }
            if (result.isEmpty()) throw IOException("DoH 没有有效地址")
            addresses[host] = Entry(result, now() + ttl * 1000)
            persist("addr:$host", result.joinToString(",") { it.hostAddress })
            result
        }
    }

    /** ECH 记录里的 hint 地址；取配置失败时抛错（与 ECH 同样的 fail-closed 语义）。 */
    fun hints(hostname: String): List<InetAddress> = hints(hostname, bestEffort = false)

    private fun hints(hostname: String, bestEffort: Boolean): List<InetAddress> {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        synchronized(lockFor(host)) {
            hints[host]?.let { if (it.until > now()) return it.value }
            echConfig(host, bestEffort)
            return hints[host]?.value.orEmpty()
        }
    }

    private val warmedUp = AtomicBoolean(false)
    private val warmUpPool by lazy {
        Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "ech-doh-warmup").apply { isDaemon = true }
        }
    }

    /**
     * 启动预热：提前把地址与 ECH 配置放进缓存，用户第一屏就不用在关键路径上等 DoH。
     * 预热失败**不写冷却**（网关抖动不该被放大成 5 分钟不可用），真正要用时仍按原逻辑失败即阻断。
     */
    fun warmUp(hostnames: Collection<String>) {
        if (!warmedUp.compareAndSet(false, true)) return
        hostnames.forEach { hostname ->
            runCatching {
                warmUpPool.execute {
                    runCatching { resolve(hostname, bestEffort = true) }
                    runCatching { hints(hostname, bestEffort = true) }
                }
            }
        }
    }

    fun echConfig(hostname: String): ByteArray = echConfig(hostname, bestEffort = false)

    private fun echConfig(hostname: String, bestEffort: Boolean): ByteArray {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        synchronized(lockFor(host)) {
            configs[host]?.let { if (it.until > now()) return it.value }
            // 冷启动优先：上次成功的 ECH 配置先拿来握手，后台再取在线配置替换。
            persistedConfig(host)?.let { cached ->
                configs[host] = Entry(cached, now() + CACHED_HOLD_MILLIS)
                persistedHints(host)?.let { stored -> hints[host] = Entry(stored, now() + CACHED_HOLD_MILLIS) }
                refreshLater(host)
                return cached
            }
            return try {
                fetchConfig(host, bestEffort)
            } catch (error: Exception) {
                if (bestEffort) throw error
                // 旧配置只可能握手失败，不会让 SNI 明文外泄；拿不到就仍然阻断。
                persistedConfig(host) ?: throw error
            }
        }
    }

    /**
     * 取某域名的 ECH 配置：**官方源优先**（[REFERENCE_ECH_HOST]，CF 自动维护、随查随新），
     * 拿不到或被翻过标志位时才用它自己的记录。两条路的结果都会落到该域名名下缓存起来。
     */
    private fun fetchConfig(host: String, bestEffort: Boolean): ByteArray {
        if (host == REFERENCE_ECH_HOST) return fetchConfigFromGateway(host, bestEffort)
        // 先走哪条路：默认官方源；翻过标志位（官方那份被拒过）就先用它自己的记录。
        val first = if (ownRecordFirst(host)) host else REFERENCE_ECH_HOST
        val second = if (ownRecordFirst(host)) REFERENCE_ECH_HOST else host
        runCatching { fetchConfigFromGateway(first, bestEffort) }.getOrNull()?.let { config ->
            return if (first == host) config else adoptConfig(host, config)
        }
        // 第一条路拿不到：换另一条；两条都不行才如实抛错（fail-closed）。
        val fallback = runCatching { fetchConfigFromGateway(second, bestEffort) }.getOrThrow()
        return if (second == host) fallback else adoptConfig(host, fallback)
    }

    /** 把官方源的配置记为**该域名**的配置（连 hints 一起），这样冷启动直接有缓存可用。 */
    private fun adoptConfig(host: String, config: ByteArray): ByteArray {
        hints[REFERENCE_ECH_HOST]?.let { stored -> hints[host] = stored }
        persistConfig(host, config)
        return config
    }

    private fun persistConfig(host: String, config: ByteArray) {
        configs[host] = Entry(config, now() + 300_000L)
        persist("cfg:$host", Base64.encodeToString(config, Base64.DEFAULT))
    }

    /** 从网关按域名取 ECH 记录（含 ipv4hint）并落盘。 */
    private fun fetchConfigFromGateway(host: String, bestEffort: Boolean): ByteArray {
        val pool = endpoints
        return guarded(bestEffort) {
            val json = fetch(pool, host, "HTTPS")
            val answer = json.getJSONArray("Answer")
            var selected: ByteArray? = null
            var ttl = 300L
            for (i in 0 until answer.length()) {
                val item = answer.getJSONObject(i)
                if (item.optInt("type") != 65) continue
                val encoded = REGEX_ECH.find(item.getString("data"))?.groupValues?.get(1) ?: continue
                val wire = Base64.decode(encoded, Base64.DEFAULT)
                validateConfig(wire)
                selected = wire
                ttl = item.getLong("TTL").coerceIn(0, 300)
                val parsed = parseHints(item.getString("data"))
                hints[host] = Entry(parsed, now() + ttl * 1000)
                if (parsed.isNotEmpty()) persist("hint:$host", parsed.joinToString(",") { it.hostAddress })
                break
            }
            val result = selected ?: throw IOException("网关未提供 ECH 配置，已阻断")
            configs[host] = Entry(result, now() + ttl * 1000)
            persist("cfg:$host", Base64.encodeToString(result, Base64.DEFAULT))
            result
        }
    }

    private fun <T> guarded(bestEffort: Boolean, block: () -> T): T {
        val current = now()
        val blockedUntil = preferences.getLong("blocked_until", 0)
        if (blockedUntil > current) {
            throw IOException("DoH 冷却中，剩余 ${(blockedUntil - current + 999) / 1000} 秒")
        }
        return try {
            block()
        } catch (first: Exception) {
            if (bestEffort) throw IOException("DoH 预热失败（不影响后续请求）")
            // 冷启动时首次查询常因网络刚唤醒而失败：换端点后立刻重试一次，
            // 仍失败才冷却——避免把一次瞬时抖动放大成 5 分钟整体不可用。
            rotateEndpoint()
            try {
                Thread.sleep(RETRY_DELAY_MILLIS)
                block()
            } catch (second: Exception) {
                preferences.edit().putLong("blocked_until", now() + 300_000L).commit()
                throw IOException("DoH 查询失败，已阻断并冷却 5 分钟")
            }
        }
    }

    private fun rotateEndpoint() {
        val next = preferences.getInt("endpoint_index", 0).toLong() + 1
        preferences.edit().putInt("endpoint_index", (next % Int.MAX_VALUE).toInt()).commit()
    }

    private fun fetch(pool: List<Endpoint>, host: String, type: String): JSONObject {
        if (pool.isEmpty()) throw IOException("未配置 ECH 网关")
        val endpoint = pool[preferences.getInt("endpoint_index", 0).mod(pool.size)]
        val url = endpoint.url.newBuilder()
            .setQueryParameter("name", host).setQueryParameter("type", type).build()
        val request = Request.Builder().url(url).header("Accept", "application/dns-json").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("DoH 状态异常")
            val body = response.body ?: throw IOException("DoH 响应为空")
            val bytes = body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 65_536) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 65_537 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > 65_536) throw IOException("DoH 响应过大")
            JSONObject(bytes.toString(Charsets.UTF_8)).also {
                if (it.optInt("Status", -1) != 0 || it.optBoolean("TC")) throw IOException("DNS 响应异常")
            }
        }
    }

    /** 最近一次成功的值落盘：冷启动拿它开路、在线失败也拿它兜底（都只是旧值，不会明文回落）。 */
    private fun persist(key: String, value: String) {
        preferences.edit().putString(key, value).putLong("$key.at", now()).apply()
    }

    private fun persisted(key: String, maxAgeMillis: Long): String? {
        val at = preferences.getLong("$key.at", 0)
        if (at <= 0 || now() - at > maxAgeMillis) return null
        return preferences.getString(key, null)
    }

    private fun persistedAddresses(host: String): List<InetAddress>? =
        persisted("addr:$host", CACHED_ADDRESS_MAX_AGE_MILLIS)?.split(',')?.mapNotNull {
            runCatching { parseIpv4Literal(it.trim()) }.getOrNull()
        }?.takeIf { it.isNotEmpty() }

    /** hints 与 ECH 配置同源（都来自 HTTPS 记录），因此跟配置同一个有效期。 */
    private fun persistedHints(host: String): List<InetAddress>? =
        persisted("hint:$host", CACHED_CONFIG_MAX_AGE_MILLIS)?.split(',')?.mapNotNull {
            runCatching { parseIpv4Literal(it.trim()) }.getOrNull()
        }?.takeIf { it.isNotEmpty() }

    private fun persistedConfig(host: String): ByteArray? =
        persisted("cfg:$host", CACHED_CONFIG_MAX_AGE_MILLIS)
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            ?.takeIf { runCatching { validateConfig(it) }.isSuccess }

    internal fun parseIpv4Literal(value: String): InetAddress {
        val parts = value.split('.')
        if (parts.size != 4) throw IOException("无效 IPv4")
        val bytes = parts.map {
            if (it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' }) throw IOException("无效 IPv4")
            val n = it.toInt()
            if (n !in 0..255) throw IOException("无效 IPv4")
            n.toByte()
        }.toByteArray()
        return InetAddress.getByAddress(bytes)
    }

    private fun parseHints(data: String): List<InetAddress> =
        HINT_PATTERN.find(data)?.groupValues?.get(1)
            ?.split(',')
            ?.mapNotNull { value -> runCatching { parseIpv4Literal(value.trim()) }.getOrNull() }
            .orEmpty()

    internal fun validateConfig(wire: ByteArray) {
        if (wire.size < 8) throw IOException("ECH 配置过短")
        val length = ((wire[0].toInt() and 255) shl 8) or (wire[1].toInt() and 255)
        if (length != wire.size - 2) throw IOException("ECH 配置长度错误")
    }
}

package com.xiaoyv.bangumi.shared.libnative.ech

import android.util.Base64
import android.util.Log
import com.xiaoyv.bangumi.shared.libnative.application
import okhttp3.Dns
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

/** 日志标签。 */
private const val TAG = "BGM-ECH-DOH"

/**
 * ECH 活值的候选：**国内三家的纯 IP 端点**（阿里 / 腾讯 / 360，各带一个备份）。
 *
 * 为什么用纯 IP：不查 DNS、不被污染、证书直接对 IP 生效（三家实测 HTTPS 均可用）。
 * 为什么不加 `Host` 头：阿里带 `Host` 会直接失败（实测 http=000）；正确答案就是
 * "URL 里的 IP 当 Host + `?dns=` 传二进制报文"，所以一律不加 Host 头。
 * 为什么只认 wire：三家都不支持 `application/dns-json`（阿里/360 回 400 no 'dns' query parameter，
 * 腾讯回 UrlParameterError），只能发 `application/dns-message`。实测三家取到的活值与 CF 官方逐字节相同。
 *
 * 策略：**随机挑一家试，失败换下一家**（不同时打、也不重复打同一家），单家 2.5 秒超时。
 *
 * 可通过构建注入 `ech_doh_ips`（逗号分隔）覆盖；没有则用内置。
 */
private val BUILTIN_DOH_IPS = listOf(
    "223.5.5.5",        // 阿里
    "223.6.6.6",        // 阿里备用
    "1.12.12.12",       // 腾讯
    "120.53.53.53",     // 腾讯备用
    "101.198.193.29",   // 360
    "101.198.192.33",   // 360 备用
)

/** 优先读构建注入的 IP 列表，拿不到再用内置。只读一次：资源运行时不会变。 */
private val ECH_DOH_IPS: List<String> by lazy {
    runCatching {
        val id = application.resources.getIdentifier("ech_doh_ips", "string", application.packageName)
        if (id == 0) return@runCatching null
        application.getString(id).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: BUILTIN_DOH_IPS
}

/** 单家超时：快失败快换下一家，别让冷启动干等。 */
private const val LIVE_ONE_TIMEOUT_MILLIS = 2500L

/**
 * 活值的缓存时长：记录的 TTL 只有 ~200 秒，但公钥实测能稳定数天，
 * 所以至少缓存 1 小时（真正省掉冷启动那次查询），且不超过 5 小时（CF 约 5 小时轮换密钥）。
 * 万一被轮换，握手被拒会走 [BgmEchDoh.invalidateConfig] 自愈。
 */
private const val LIVE_CACHE_MIN_MILLIS = 60 * 60 * 1000L
private const val LIVE_CACHE_MAX_MILLIS = 5 * 60 * 60 * 1000L

/** 活值整条链路都拿不到时的短冷却（只在内存里）：避免并发预热把同一批 IP 反复打一遍。 */
private const val LIVE_FAIL_COOLDOWN_MILLIS = 30 * 1000L

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
            // 活值内存缓存也要丢：它可能正是被服务器拒的那一份。
            liveEntry = null
            BgmEchState.drop(REFERENCE_ECH_HOST)
            preferences.edit()
                .remove("cfg:$REFERENCE_ECH_HOST").remove("cfg:$REFERENCE_ECH_HOST.at")
                .remove("hint:$REFERENCE_ECH_HOST").remove("hint:$REFERENCE_ECH_HOST.at")
                .commit()
        }
        synchronized(lockFor(host)) {
            configs.remove(host)
            hints.remove(host)
            BgmEchState.drop(host)
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
        // 活值先预热一次：官方源那份配置是所有受保护域名共用的（并发取也只打一次，见 fetchLiveEch 的单飞）。
        runCatching { warmUpPool.execute { runCatching { fetchLiveEch() } } }
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

    /**
     * 返回首个可用的 DoH 网关 URL（给 kathttp3 的 DohResolver 用）。
     */
    fun dohUrl(): String {
        return endpoints.firstOrNull()?.url.toString().ifEmpty {
            "https://cloudflare-dns.com/dns-query"
        }
    }

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
        // 专用落盘（`ech_state`）：冷启动直接复用活值，不必再等一次 DoH。
        BgmEchState.save(host, config, CACHED_CONFIG_MAX_AGE_MILLIS)
    }

    // ---------------- 活值：国内三家纯 IP（wire 格式） ----------------

    /**
     * 一次活值查询的结果。
     * [wire] 含 2 字节长度前缀，可直接喂 Conscrypt；[hints] 是同一个 SVCB 记录里的 ipv4hint。
     */
    private class LiveEch(val wire: ByteArray, val hints: List<InetAddress>, val ttlMillis: Long)

    /** 活值的内存缓存与失败时间戳（纯内存：重启后重取一次即可，不往磁盘写冷却）。 */
    @Volatile
    private var liveEntry: Entry<LiveEch>? = null

    @Volatile
    private var liveFailedAt = 0L

    private val liveLock = Any()

    /** 纯 IP 查询用的短超时客户端：不查系统 DNS（URL 里的 host 就是 IP）、不打代理、不重试。 */
    private val liveClient by lazy {
        OkHttpClient.Builder()
            .dns(object : Dns {
                // URL 里的 host 就是纯 IP：这里只把字面量变成 InetAddress，**永远不查系统 DNS**（污染源）。
                override fun lookup(hostname: String): List<InetAddress> = listOf(parseIpv4Literal(hostname))
            })
            .proxy(java.net.Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(LIVE_ONE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(LIVE_ONE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .callTimeout(LIVE_ONE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 取官方源的 ECH 活值。
     *
     * 单飞（synchronized + 双检 + 失败短冷却）：预热是并发的，这里保证同一时刻只打一家，
     * 不会把同一家重复打；拿到的值立刻进内存缓存、落盘缓存与官方源名下。
     */
    private fun fetchLiveEch(): ByteArray? {
        liveEntry?.let { if (it.until > now()) return it.value.wire }
        if (now() - liveFailedAt < LIVE_FAIL_COOLDOWN_MILLIS) return null
        synchronized(liveLock) {
            liveEntry?.let { if (it.until > now()) return it.value.wire }
            val hit = queryLiveEch()
            if (hit == null) {
                liveFailedAt = now()
                return null
            }
            val ttlMillis = hit.ttlMillis
            liveEntry = Entry(hit, now() + ttlMillis)
            // 成功值同时记到官方源名下，这样 adoptConfig 能把它（连 hints）复制给目标域名。
            if (hit.hints.isNotEmpty()) {
                hints[REFERENCE_ECH_HOST] = Entry(hit.hints, now() + ttlMillis)
                persist("hint:$REFERENCE_ECH_HOST", hit.hints.joinToString(",") { it.hostAddress })
            }
            configs[REFERENCE_ECH_HOST] = Entry(hit.wire, now() + ttlMillis)
            persist("cfg:$REFERENCE_ECH_HOST", Base64.encodeToString(hit.wire, Base64.DEFAULT))
            BgmEchState.save(REFERENCE_ECH_HOST, hit.wire, ttlMillis)
            return hit.wire
        }
    }

    /** 随机挑一家纯 IP 取活值；这家不行换下一家（顺序每轮重新打乱）。 */
    private fun queryLiveEch(): LiveEch? {
        for (ip in ECH_DOH_IPS.shuffled()) {
            val hit = runCatching { queryEchWire(ip, REFERENCE_ECH_HOST) }.getOrNull()
            if (hit != null) {
                Log.i(TAG, "live ech via $ip: ${hit.wire.size} bytes, ttl=${hit.ttlMillis}ms, hints=${hit.hints.size}")
                return hit
            }
            Log.i(TAG, "live ech via $ip failed, next")
        }
        Log.i(TAG, "live ech unavailable: ${ECH_DOH_IPS.size} domestic ips all failed")
        return null
    }

    /** 纯 IP + wire 的 DoH 查询（`?dns=<base64url>`，无 Host 头）。 */
    private fun queryEchWire(ip: String, name: String): LiveEch? {
        val question = Base64.encodeToString(
            buildQuery(name),
            Base64.NO_WRAP or Base64.URL_SAFE,
        ).trimEnd('=')
        // 绝不加 Host 头：阿里带 Host 直接失败（实测 http=000）；URL 的 host 就是 IP，证书对 IP 有效。
        val request = Request.Builder()
            .url("https://$ip/dns-query?dns=$question")
            .header("Accept", "application/dns-message")
            .build()
        val message = liveClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.bytes() ?: return null
        }
        return parseSvcbEch(message)
    }

    /** 建 DNS 查询报文（ID + 标志 + 1 个问题，type 65 = HTTPS）。 */
    private fun buildQuery(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 65, 0x00, 0x01))
        return out.toByteArray()
    }

    /**
     * 解析 DNS 应答，找 type=65 的 HTTPS 记录，走 SvcParams 取 key=5（ech）与 key=4（ipv4hint）。
     * 返回的 ech 字节**含 2 字节长度前缀**，可直接喂 Conscrypt（实测以 0x00 0x45 开头，0x45=69）。
     */
    private fun parseSvcbEch(message: ByteArray): LiveEch? {
        if (message.size < 12) return null
        if ((message[3].toInt() and 0x0F) != 0) return null // rcode != NOERROR
        var index = 12
        while (index < message.size && message[index].toInt() != 0) {
            index += (message[index].toInt() and 0xFF) + 1
        }
        index += 5 // 跳过 question 的根标签 + qtype + qclass
        val answers = ((message[6].toInt() and 0xFF) shl 8) or (message[7].toInt() and 0xFF)
        for (n in 0 until answers) {
            if (index + 12 > message.size) return null
            if ((message[index].toInt() and 0xC0) == 0xC0) {
                index += 2
            } else {
                while (index < message.size && message[index].toInt() != 0) {
                    index += (message[index].toInt() and 0xFF) + 1
                }
                index += 1
            }
            val type = ((message[index].toInt() and 0xFF) shl 8) or (message[index + 1].toInt() and 0xFF)
            val ttl = ((message[index + 4].toInt() and 0xFF).toLong() shl 24) or
                ((message[index + 5].toInt() and 0xFF).toLong() shl 16) or
                ((message[index + 6].toInt() and 0xFF).toLong() shl 8) or
                (message[index + 7].toInt() and 0xFF).toLong()
            val length = ((message[index + 8].toInt() and 0xFF) shl 8) or (message[index + 9].toInt() and 0xFF)
            val rdata = index + 10
            if (type == 65 && length > 4 && rdata + length <= message.size) {
                // SVCB: priority(2) + target(域名) + SvcParams
                var cursor = rdata + 2
                while (cursor < rdata + length && message[cursor].toInt() != 0) {
                    cursor += (message[cursor].toInt() and 0xFF) + 1
                }
                cursor += 1
                var found: ByteArray? = null
                val parsedHints = mutableListOf<InetAddress>()
                while (cursor + 4 <= rdata + length) {
                    val key = ((message[cursor].toInt() and 0xFF) shl 8) or (message[cursor + 1].toInt() and 0xFF)
                    val size = ((message[cursor + 2].toInt() and 0xFF) shl 8) or (message[cursor + 3].toInt() and 0xFF)
                    if (key == 5 && size > 0 && cursor + 4 + size <= rdata + length) {
                        found = message.copyOfRange(cursor + 4, cursor + 4 + size)
                    } else if (key == 4 && size >= 4 && cursor + 4 + size <= rdata + length) {
                        var address = cursor + 4
                        while (address + 4 <= cursor + 4 + size) {
                            val literal = "${message[address].toInt() and 0xFF}.${message[address + 1].toInt() and 0xFF}." +
                                "${message[address + 2].toInt() and 0xFF}.${message[address + 3].toInt() and 0xFF}"
                            runCatching { parseIpv4Literal(literal) }.getOrNull()?.let { parsedHints += it }
                            address += 4
                        }
                    }
                    cursor += 4 + size
                }
                val wire = found
                if (wire != null && runCatching { validateConfig(wire) }.isSuccess) {
                    val ttlMillis = (ttl * 1000L).coerceIn(LIVE_CACHE_MIN_MILLIS, LIVE_CACHE_MAX_MILLIS - 1) + 1
                    return LiveEch(wire, parsedHints.distinct(), ttlMillis)
                }
            }
            index = rdata + length
        }
        return null
    }

    /** 从网关按域名取 ECH 记录（含 ipv4hint）并落盘。 */
    private fun fetchConfigFromGateway(host: String, bestEffort: Boolean): ByteArray {
        // 官方活源先走国内三家纯 IP（wire，实测与 CF 官方逐字节相同）；三家都不通才回退原有的网关 JSON 链路。
        // 这一步刻意放在 guarded 之外：冷却/退避是给网关池的，别把纯 IP 这条更快的路一起冻住。
        if (host == REFERENCE_ECH_HOST) {
            fetchLiveEch()?.let { live -> return live }
        }
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
        // 先看专用落盘（`ech_state`）；没有或已过期再退回 `ech_doh_state` 里的旧键。
        BgmEchState.load(host)?.takeIf { runCatching { validateConfig(it) }.isSuccess }
            ?: persisted("cfg:$host", CACHED_CONFIG_MAX_AGE_MILLIS)
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

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
 * 按主机加锁合并并发请求；失败先换端点重试一次，仍失败才写 5 分钟冷却（持久化，重启不绕过）。
 * 网络失败时用最近一次成功的地址/配置兜底（10 分钟内），只可能握手失败，不会明文回落。
 */
private val HINT_PATTERN = Regex("ipv4hint=([0-9.,]+)")

/** 失败后换端点重试的等待时间；只重试一次。 */
private const val RETRY_DELAY_MILLIS = 400L

/** 最近一次成功的地址/配置可用多久（超过就只信网络）。 */
private const val FALLBACK_MAX_AGE_MILLIS = 10 * 60 * 1000L

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

    fun resolve(hostname: String): List<InetAddress> = resolve(hostname, bestEffort = false)

    private fun resolve(hostname: String, bestEffort: Boolean): List<InetAddress> {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        val pool = endpoints
        synchronized(lockFor(host)) {
            addresses[host]?.let { if (it.until > now()) return it.value }
            return try {
                guarded(bestEffort) {
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
            } catch (error: Exception) {
                if (bestEffort) throw error
                // 网关抖动/冷启动失败时，用最近一次成功的地址兜底（仅旧地址，不涉及明文回落）。
                persistedAddresses(host) ?: throw error
            }
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
        val pool = endpoints
        synchronized(lockFor(host)) {
            configs[host]?.let { if (it.until > now()) return it.value }
            return try {
                guarded(bestEffort) {
                    val json = fetch(pool, host, "HTTPS")
                    val answer = json.getJSONArray("Answer")
                    var selected: ByteArray? = null
                    var ttl = 300L
                    for (i in 0 until answer.length()) {
                        val item = answer.getJSONObject(i)
                        if (item.optInt("type") != 65) continue
                        val encoded = Regex("(?:^|\\s)ech=\"?([A-Za-z0-9+/=]+)").find(item.getString("data"))
                            ?.groupValues?.get(1) ?: continue
                        val wire = Base64.decode(encoded, Base64.DEFAULT)
                        validateConfig(wire)
                        selected = wire
                        ttl = item.getLong("TTL").coerceIn(0, 300)
                        hints[host] = Entry(parseHints(item.getString("data")), now() + ttl * 1000)
                        break
                    }
                    val result = selected ?: throw IOException("网关未提供 ECH 配置，已阻断")
                    configs[host] = Entry(result, now() + ttl * 1000)
                    persist("cfg:$host", Base64.encodeToString(result, Base64.DEFAULT))
                    result
                }
            } catch (error: Exception) {
                if (bestEffort) throw error
                // 旧配置只可能握手失败，不会让 SNI 明文外泄；拿不到就仍然阻断。
                persistedConfig(host) ?: throw error
            }
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

    /** 最近一次成功的值落盘，网关抖动/冷启动时用来兜底（不改 fail-closed：只是旧值，不会明文回落）。 */
    private fun persist(key: String, value: String) {
        preferences.edit().putString(key, value).putLong("$key.at", now()).apply()
    }

    private fun persisted(key: String): String? {
        val at = preferences.getLong("$key.at", 0)
        if (at <= 0 || now() - at > FALLBACK_MAX_AGE_MILLIS) return null
        return preferences.getString(key, null)
    }

    private fun persistedAddresses(host: String): List<InetAddress>? =
        persisted("addr:$host")?.split(',')?.mapNotNull {
            runCatching { parseIpv4Literal(it.trim()) }.getOrNull()
        }?.takeIf { it.isNotEmpty() }

    private fun persistedConfig(host: String): ByteArray? =
        persisted("cfg:$host")?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
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

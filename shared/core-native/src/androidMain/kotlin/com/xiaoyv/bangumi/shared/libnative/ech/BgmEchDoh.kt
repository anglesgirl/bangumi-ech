package com.xiaoyv.bangumi.shared.libnative.ech

import android.util.Base64
import com.xiaoyv.bangumi.shared.libnative.application
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 从构建注入的网关获取 DNS 与 ECH 配置。空配置和解析失败均阻断。
 * 串行合并缓存请求；失败冷却写入私有存储，重启不绕过，无自动重试。
 */
internal object BgmEchDoh {
    private data class Entry<T>(val value: T, val until: Long)
    private val addresses = mutableMapOf<String, Entry<List<InetAddress>>>()
    private val configs = mutableMapOf<String, Entry<ByteArray>>()
    private val preferences by lazy { application.getSharedPreferences("ech_doh_state", 0) }
    private val endpoints by lazy {
        val id = application.resources.getIdentifier("ech_doh_pool", "string", application.packageName)
        if (id == 0) emptyList() else application.getString(id).split(',').map { it.trim() }
            .filter { it.isNotEmpty() }.map { it.toHttpUrl() }.onEach {
                require(it.isHttps && it.username.isEmpty() && it.password.isEmpty())
                require(!BgmEchPolicy.isProtected(it.host))
            }
    }
    private val client by lazy {
        OkHttpClient.Builder()
            .dns { host ->
                if (endpoints.any { it.host == host } && host.endsWith(".cloudflare-gateway.com")) {
                    listOf("162.159.36.20", "162.159.36.5").map(::parseIpv4Literal)
                } else Dns.SYSTEM.lookup(host)
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

    @Synchronized
    fun resolve(hostname: String): List<InetAddress> {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        addresses[host]?.let { if (it.until > java.lang.System.currentTimeMillis()) return it.value }
        return guarded {
            val json = fetch(host, "A")
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
            addresses[host] = Entry(result, java.lang.System.currentTimeMillis() + ttl * 1000)
            result
        }
    }

    @Synchronized
    fun echConfig(hostname: String): ByteArray {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        configs[host]?.let { if (it.until > java.lang.System.currentTimeMillis()) return it.value }
        return guarded {
            val json = fetch(host, "HTTPS")
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
                break
            }
            val result = selected ?: throw IOException("网关未提供 ECH 配置，已阻断")
            configs[host] = Entry(result, java.lang.System.currentTimeMillis() + ttl * 1000)
            result
        }
    }

    private fun <T> guarded(block: () -> T): T {
        val now = java.lang.System.currentTimeMillis()
        val blockedUntil = preferences.getLong("blocked_until", 0)
        if (blockedUntil > now) throw IOException("DoH 冷却中，剩余 ${(blockedUntil - now + 999) / 1000} 秒")
        return try {
            block()
        } catch (e: Exception) {
            preferences.edit().putLong("blocked_until", java.lang.System.currentTimeMillis() + 300_000L).commit()
            // 仅下一次用户操作可选备用节点；本次失败不自动重发。
            val next = preferences.getInt("endpoint_index", 0).toLong() + 1
            preferences.edit().putInt("endpoint_index", (next % Int.MAX_VALUE).toInt()).commit()
            throw IOException("DoH 查询失败，已阻断并冷却 5 分钟")
        }
    }

    private fun fetch(host: String, type: String): JSONObject {
        if (endpoints.isEmpty()) throw IOException("未配置 ECH 网关")
        val endpoint = endpoints[preferences.getInt("endpoint_index", 0).mod(endpoints.size)]
        val url = endpoint.newBuilder().setQueryParameter("name", host).setQueryParameter("type", type).build()
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

    internal fun validateConfig(wire: ByteArray) {
        if (wire.size < 8) throw IOException("ECH 配置过短")
        val length = ((wire[0].toInt() and 255) shl 8) or (wire[1].toInt() and 255)
        if (length != wire.size - 2) throw IOException("ECH 配置长度错误")
    }
}

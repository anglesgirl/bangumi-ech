package com.xiaoyv.bangumi.shared.libnative.ech

import android.content.Context
import android.util.Base64
import android.util.Log
import dev.kathttp3.DohResolver
import dev.kathttp3.KatHttp3Client
import dev.kathttp3.KatHttp3ClientConfig
import dev.kathttp3.KatHttp3Header
import dev.kathttp3.KatHttp3Request
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * kathttp3（HTTP/3 + ECH）的 Kotlin 入口。基于 ngtcp2 + nghttp3 + BoringSSL，
 * ECH 走 BoringSSL 原生 SSL_set1_ech_config_list，比 quiche 补丁更稳。
 *
 * 定位：**只服务静态图片 GET**。有状态请求（API/登录/POST/Cookie）一律仍走
 * OkHttp + Conscrypt 的 TCP/ECH 链路 —— 那条也是这里失败时的兜底。
 */
object BgmEchH3 {
    private const val TAG = "BgmEchH3"
    private const val PREFS = "bgm_ech_h3"
    private const val KEY_DEBUG_LOG = "debug_log"

    /** H3 详细日志开关（默认关，测试时打开） */
    fun isDebugLogEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOG, false)
    }

    fun setDebugLogEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEBUG_LOG, enabled).apply()
    }

    /** kathttp3 客户端（懒加载，复用连接池） */
    @Volatile
    private var katClient: KatHttp3Client? = null

    /** 最近一次请求的结果（失败时用来定位原因） */
    @Volatile
    private var lastJson: String = ""

    private fun getClient(context: Context): KatHttp3Client {
        return katClient ?: synchronized(this) {
            katClient ?: run {
                // 用我们的 DoH 网关解析，自动提取 ECH
                val dohUrl = BgmEchDoh.dohUrl() // 雅💓涵的DOH
                val resolver = DohResolver(endpoint = dohUrl)
                val config = KatHttp3ClientConfig(
                    resolver = resolver,
                    connectTimeoutMillis = 3000,
                    requestTimeoutMillis = 25000,
                )
                KatHttp3Client(config, context.applicationContext).also { katClient = it }
            }
        }
    }

    /** 系统 CA 导出成单个 PEM（Rust 侧 load_verify_locations_from_file 读它），只做一次。 */
    fun caBundlePath(context: Context): String {
        val f = File(context.cacheDir, "bgm-system-ca.pem")
        if (f.exists() && f.length() > 1024) return f.absolutePath
        return try {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            val sb = StringBuilder()
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val a = aliases.nextElement()
                val cert = ks.getCertificate(a) as? X509Certificate ?: continue
                sb.append("-----BEGIN CERTIFICATE-----\n")
                sb.append(Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
                sb.append("\n-----END CERTIFICATE-----\n")
            }
            f.writeText(sb.toString())
            f.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "系统 CA 导出失败：${t.message}")
            ""
        }
    }

    /**
     * 走 H3+ECH 拉一个静态资源并落盘。
     * @return 成功返回文件；**任何失败都返回 null**（调用方回落 TCP/ECH 链路，保持 fail-closed）
     */
    fun fetchToFile(
        context: Context,
        host: String,
        pathWithQuery: String,
        referer: String?,
        out: File,
    ): File? {
        val t0 = System.currentTimeMillis()
        return try {
            runBlocking {
                val client = getClient(context)
                val url = "https://$host$pathWithQuery"
                val headers = mutableListOf<KatHttp3Header>()
                referer?.takeIf { it.isNotEmpty() }?.let {
                    headers.add(KatHttp3Header("referer", it))
                }
                // Pixiv 图片需要 Referer，ImageInterceptor 已经设置了，这里兜底
                val request = KatHttp3Request(
                    method = "GET",
                    url = url,
                    headers = headers,
                )
                val t1 = System.currentTimeMillis()
                val response = client.execute(request)
                val t2 = System.currentTimeMillis()
                lastJson = "status=${response.status} len=${response.body.size}"
                // 详细计时上报：定位 P站慢是 DNS/ECH/握手/传输哪个阶段
                reportTiming(context, host, url, t0, t1, t2, response.status, response.body.size, null)
                if (response.status in 200..299 && response.body.isNotEmpty()) {
                    out.writeBytes(response.body)
                    out
                } else {
                    Log.w(TAG, "H3 请求失败：HTTP ${response.status}")
                    null
                }
            }
        } catch (t: Throwable) {
            val t3 = System.currentTimeMillis()
            Log.w(TAG, "H3 调用异常：${t.message}")
            reportTiming(context, host, "https://$host$pathWithQuery", t0, t0, t3, -1, 0, t.message)
            null
        }
    }

    /** 图片加载状态上报（给 H3FetcherHook 用）：h3_attempt / h3_skip_breaker / h3_success / fallback */
    fun logState(context: Context, host: String, state: String, durationMs: Long, url: String) {
        if (!isDebugLogEnabled(context)) return
        val diag = diagUrl(context) + "?app=bangumi-ech"
        Thread {
            runCatching {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                val json = org.json.JSONObject()
                    .put("event", "h3-state")
                    .put("ts", fmt.format(java.util.Date()))
                    .put("host", host)
                    .put("state", state)
                    .put("duration_ms", durationMs)
                    .put("url", url.take(200))
                    .put("engine", "kathttp3")
                val c = (java.net.URL(diag).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                c.outputStream.use { it.write(json.toString().toByteArray()) }
                c.responseCode
                c.disconnect()
            }
        }.start()
    }

    /** 详细计时上报（测试用：不做 60s 限流，每条都发） */
    private fun reportTiming(
        context: Context,
        host: String,
        url: String,
        t0: Long,
        t1: Long,
        t2: Long,
        status: Int,
        bytes: Int,
        error: String?,
    ) {
        if (!isDebugLogEnabled(context)) return
        val diag = diagUrl(context) + "?app=bangumi-ech"
        Thread {
            runCatching {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                val json = org.json.JSONObject()
                    .put("event", "h3-timing")
                    .put("ts", fmt.format(java.util.Date()))
                    .put("host", host)
                    .put("url", url.take(200))
                    .put("total_ms", t2 - t0)
                    .put("queue_ms", t1 - t0)
                    .put("request_ms", t2 - t1)
                    .put("status", status)
                    .put("bytes", bytes)
                    .put("error", error ?: "")
                    .put("engine", "kathttp3")
                val c = (java.net.URL(diag).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                c.outputStream.use { it.write(json.toString().toByteArray()) }
                c.responseCode
                c.disconnect()
            }
        }.start()
    }

    /**
     * 对外唯一入口：受保护图片域的 H3+ECH 拉取并落盘。
     * 非受保护域名 / 解析失败 / 握手失败 一律返回 null（调用方回落 TCP/ECH，fail-closed）。
     * 之所以放在本模块，是因为 [BgmEchDoh] / [BgmEchPolicy] 是模块内 internal。
     */
    /**
     * 只接管 pixiv 官方图床：其它图源（如 AnimePic）对请求头敏感，
     * 走 H3 可能拿到 200 + 非图片内容，Coil 解不出 → 黑屏。宁可不接管。
     */
    /** 防盗链 Referer：只有确实要求的站点才加（site 后缀 → Referer） */
    /**
     * H3 可用性记忆（落盘）。策略：**默认每个域名都先试 H3** —— 服务端到底支不支持
     * 由实测决定，不写死白名单（写死会漏掉后来才开 H3 的站点，也会白试已知不行的）。
     *
     * 失败一次就把该域名记入**负缓存**（[H3_FAIL_TTL_MS] 内直接走 H2/TCP+ECH，不再白试）；
     * 成功后清掉负缓存。TTL 过期会再试一次 —— 服务端可能后来才启用 H3。
     *
     * 实测过的"不支持"案例（现已由负缓存自动学会，不再写死）：
     *   lain.bgm.tv —— CF 侧未启用 H3，握手 alert 40
     *   AnimePic CDN —— 自身拦非浏览器请求（403 拦截页 / 302）
     */
    private const val H3_STATE_PREFS = "ech_h3_state"
    private const val H3_FAIL_TTL_MS = 24 * 60 * 60 * 1000L

    private fun h3Prefs(context: Context) =
        context.applicationContext.getSharedPreferences(H3_STATE_PREFS, Context.MODE_PRIVATE)

    /** 是否该先试 H3：只要没被负缓存拦下就算可用。 */
    private fun shouldTryH3(context: Context, host: String): Boolean {
        val until = runCatching { h3Prefs(context).getLong("bad:$host", 0L) }.getOrDefault(0L)
        return System.currentTimeMillis() >= until
    }

    private fun rememberH3(context: Context, host: String, ok: Boolean, why: String = "") {
        runCatching {
            h3Prefs(context).edit()
                .putLong("bad:$host", if (ok) 0L else System.currentTimeMillis() + H3_FAIL_TTL_MS)
                .apply()
        }
        if (ok) Log.i(TAG, "H3 可用，已记住: $host")
        else Log.i(TAG, "H3 不通，已记负缓存 ${H3_FAIL_TTL_MS / 3600000}h，改走 H2: $host ($why)")
    }

    private val REFERERS = listOf(
        "pximg.net" to "https://www.pixiv.net/",
        // Bangumi 图片域名：部分 CDN 要求 Referer 才回图
        "bgm.tv" to "https://bgm.tv/",
        "bangumi.tv" to "https://bangumi.tv/",
        "chii.in" to "https://chii.in/",
    )

    private fun refererFor(host: String): String? =
        REFERERS.firstOrNull { host == it.first || host.endsWith("." + it.first) }?.second

    /** 图片魔数校验：不是图片一律判失败（回落原链路），绝不把 HTML/拦截页交给解码器。 */
    private fun looksLikeImage(f: File): Boolean {
        if (!f.exists() || f.length() < 16) return false
        val head = ByteArray(16)
        return runCatching {
            java.io.FileInputStream(f).use { it.read(head) }
            val b = head
            (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||                       // jpeg
                (b[0] == 0x89.toByte() && b[1] == 0x50.toByte()) ||                   // png
                (b[0] == 0x47.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte()) || // gif
                (b[0] == 0x42.toByte() && b[1] == 0x4D.toByte()) ||                   // bmp
                (b.size >= 12 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP") || // webp
                (b.size >= 12 && String(b, 4, 4) == "ftyp")                           // avif/heif
        }.getOrDefault(false)
    }

    private const val DIAG_URL_FALLBACK = "https://log.anglesgirl.eu.org/v1/events"
    private val lastReport = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 诊断上报地址：优先读构建注入的 `ech_diag_url`，没有则用内置。 */
    private fun diagUrl(context: Context): String {
        return runCatching {
            val id = context.resources.getIdentifier("ech_diag_url", "string", context.packageName)
            if (id != 0) context.getString(id).trim().takeIf { it.isNotEmpty() } else null
        }.getOrNull() ?: DIAG_URL_FALLBACK
    }

    /** 只在失败时上报、同 host 60s 最多一条：判断"图慢"是 H3 没走，还是链路本身慢。 */
    private fun report(context: Context, host: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now - (lastReport[host] ?: 0L) < 60_000L) return
        lastReport[host] = now
        val url = diagUrl(context) + "?app=bangumi-ech"
        Thread {
            runCatching {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                val json = org.json.JSONObject()
                    .put("event", "h3-image-miss")
                    .put("ts", fmt.format(java.util.Date()))
                    .put("host", host)
                    .put("reason", reason)
                    .put("detail", lastJson.take(1200))
                val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                c.outputStream.use { it.write(json.toString().toByteArray()) }
                c.responseCode
                c.disconnect()
            }
        }.start()
    }

    fun fetchImageToFile(context: Context, url: String): File? {
        val uri = try {
            java.net.URI(url)
        } catch (_: Throwable) {
            return null
        }
        val host = uri.host ?: return null
        // 策略：默认所有域名都先试 H3；不支持的会失败一次并被记入负缓存，24h 内直接走 H2。
        // 已知会失败的两类（现已由负缓存自动学会，无需写死）：
        //   AnimePic CDN 自身拦非浏览器请求 → 403 拦截页 / 302（真实 IP 直连同样如此）
        //   lain.bgm.tv 在 CF 侧未启用 H3 → 握手 alert 40
        // 失败即回落原链路（自带 UA/Accept/Referer），用户无感。
        // 默认所有域名都先试 H3；只有被负缓存记过的才直接走 H2。
        if (!shouldTryH3(context, host)) return null
        val pathWithQuery = buildString {
            append(uri.rawPath ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val referer = refererFor(host)
        // 必须保留原扩展名：AnimePic 缩略图是 .avif，之前统一叫 .bin 会让 Coil 挑不出解码器 → 黑屏
        val ext = (uri.rawPath ?: "").substringAfterLast('.', "").take(5)
            .filter { it.isLetterOrDigit() }
            .ifEmpty { "bin" }
        val out = File(context.cacheDir, "h3-" + System.nanoTime() + "." + ext)
        val ok = fetchToFile(context, host, pathWithQuery, referer, out)
        if (ok == null) {
            report(context, host, "H3 未取回")
            rememberH3(context, host, false, "取回失败")
            out.delete()
            return null
        }
        report(context, host, "H3 成功：" + ok.length() + "B 扩展名=" + ok.extension)
        if (!looksLikeImage(ok)) {
            report(context, host, "H3 返回的不是图片（疑似拦截页/HTML），已回落原链路")
            rememberH3(context, host, false, "返回非图片")
            ok.delete()
            return null
        }
        rememberH3(context, host, true)
        return ok
    }
}

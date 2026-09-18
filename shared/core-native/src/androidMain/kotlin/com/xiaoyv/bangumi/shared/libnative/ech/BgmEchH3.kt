package com.xiaoyv.bangumi.shared.libnative.ech

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * quiche（HTTP/3 + ECH）的 JNI 入口。Rust 工程在 native-h3/，CI 用 cargo-ndk 编成 .so。
 *
 * 定位：**只服务静态图片 GET**。有状态请求（API/登录/POST/Cookie）一律仍走
 * OkHttp + Conscrypt 的 TCP/ECH 链路 —— 那条也是这里失败时的兜底。
 */
object BgmEchH3 {
    private const val TAG = "BgmEchH3"

    @Volatile
    private var loaded = false

    /** 最近一次 JNI 返回的 JSON（失败时用来定位原因） */
    @Volatile
    private var lastJson: String = ""

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("bgm_h3")
            loaded = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "H3 native 库未加载：${t.message}")
            false
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
        ip: String,
        ech: ByteArray?,
        pathWithQuery: String,
        referer: String?,
        out: File,
    ): File? {
        if (!ensureLoaded()) return null
        val echB64 = ech?.takeIf { it.isNotEmpty() }?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: ""
        val json = try {
            h3Fetch(host, ip, echB64, pathWithQuery, referer ?: "", caBundlePath(context), out.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "H3 调用异常：${t.message}")
            return null
        }
        val saved = try {
            lastJson = json
            JSONObject(json).optString("saved_to", "")
        } catch (_: Throwable) {
            ""
        }
        return if (saved.isNotEmpty() && !saved.startsWith("ERR:") && out.exists() && out.length() > 0) out else null
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
    /** H3 只接管的图床（见 fetchImageToFile 里的实测依据） */
    private val H3_HOSTS = listOf("i.pximg.net")

    private val REFERERS = listOf(
        "pximg.net" to "https://www.pixiv.net/",
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

    private const val DIAG_URL = "https://log.anglesgirl.eu.org/v1/events"
    private val lastReport = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 只在失败时上报、同 host 60s 最多一条：判断"图慢"是 H3 没走，还是链路本身慢。 */
    private fun report(host: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now - (lastReport[host] ?: 0L) < 60_000L) return
        lastReport[host] = now
        Thread {
            runCatching {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                val json = org.json.JSONObject()
                    .put("app", "bangumi-ech")
                    .put("event", "h3-image-miss")
                    .put("timestamp", fmt.format(java.util.Date()))
                    .put("host", host)
                    .put("reason", reason)
                    .put("detail", lastJson.take(1200))
                val c = (java.net.URL(DIAG_URL).openConnection() as java.net.HttpURLConnection).apply {
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
        // 只接管 pixiv 官方图床。实测依据（今天逐条复现）：
        //   AnimePic CDN 自身拦非浏览器请求 → 403 拦截页 / 302（用真实 IP 直连同样如此）
        //   lain.bgm.tv 在 CF 侧未启用 H3 → 握手 alert 40
        // 这两类走 H3 只会白试一次，交给原链路（自带 UA/Accept/Referer）更稳。
        if (!H3_HOSTS.any { host == it || host.endsWith(".$it") }) return null
        val ip = runCatching { BgmEchDoh.resolve(host).firstOrNull()?.hostAddress }.getOrNull()
            ?: run { report(host, "DoH 未解析出 IP"); return null }
        val ech = runCatching { BgmEchDoh.echConfig(host) }.getOrNull()
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
        val ok = fetchToFile(context, host, ip, ech, pathWithQuery, referer, out)
        if (ok == null) {
            report(host, "H3 未取回（ech=" + (ech?.size ?: 0) + "B, ip=" + ip + "）")
            out.delete()
            return null
        }
        report(host, "H3 成功：" + ok.length() + "B 扩展名=" + ok.extension)
        if (!looksLikeImage(ok)) {
            report(host, "H3 返回的不是图片（疑似拦截页/HTML），已回落原链路")
            ok.delete()
            return null
        }
        return ok
    }

    external fun h3Fetch(
        host: String,
        peerIp: String,
        echB64: String,
        path: String,
        referer: String,
        caPath: String,
        outFile: String,
    ): String
}

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
    fun fetchImageToFile(context: Context, url: String): File? {
        val uri = try {
            java.net.URI(url)
        } catch (_: Throwable) {
            return null
        }
        val host = uri.host ?: return null
        if (!BgmEchPolicy.isProtected(host)) return null
        val ip = runCatching { BgmEchDoh.resolve(host).firstOrNull()?.hostAddress }.getOrNull() ?: return null
        val ech = runCatching { BgmEchDoh.echConfig(host) }.getOrNull()
        val pathWithQuery = buildString {
            append(uri.rawPath ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val referer = if (host.endsWith("pximg.net")) "https://www.pixiv.net/" else null
        val out = File(context.cacheDir, "h3-" + System.nanoTime() + ".bin")
        val ok = fetchToFile(context, host, ip, ech, pathWithQuery, referer, out)
        if (ok == null) out.delete()
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

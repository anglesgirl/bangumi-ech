package com.xiaoyv.bangumi.shared.libnative.ech

import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 页面里非 GET 请求的原生代发入口（fetch / XHR / 表单都由注入脚本转到这类）。
 *
 * 桥对整页 JS 可见，所以这里必须**再判一次**受保护域名与请求形态：
 * 不合规的请求直接回错误，绝不代发（fail-closed），也不回落页面自己的明文 TLS 栈。
 */
internal class EchWebBridge(private val view: WebView) {
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ech-web-bridge").apply { isDaemon = true }
    }

    @JavascriptInterface
    fun send(id: String, payload: String) {
        val scheduled = runCatching { workers.execute { handle(id, payload) } }
        if (scheduled.isFailure) {
            respond(id, JSONObject().put("error", "传输桥不可用").toString())
        }
    }

    private fun handle(id: String, payload: String) {
        val result = runCatching { forward(payload) }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                JSONObject().put("error", failure.message ?: failure.javaClass.simpleName)
            },
        )
        respond(id, result.toString())
    }

    private fun forward(payload: String): JSONObject {
        val json = JSONObject(payload)
        val method = json.optString("method", "GET").uppercase()
        val url = json.optString("url").toHttpUrlOrNull()
            ?: throw IllegalArgumentException("地址无效")
        if (!url.isHttps) throw IllegalArgumentException("禁止明文 HTTP")
        if (!BgmEchPolicy.isProtected(url.host)) throw IllegalArgumentException("非受保护域名，拒绝代发")
        if (method !in ALLOWED_METHODS) throw IllegalArgumentException("不支持的方法 $method")

        val bodyBytes = json.optString("body").takeIf { it.isNotEmpty() }
            ?.let { Base64.decode(it, Base64.DEFAULT) }
            ?: ByteArray(0)
        if (bodyBytes.size > MAX_BODY_BYTES) throw IllegalArgumentException("请求体过大，已阻断")

        var contentType: MediaType? = null
        val builder = Request.Builder().url(url)
        json.optJSONObject("headers")?.let { headers ->
            headers.keys().forEach { name ->
                // Cookie 由 CookieJar 从 CookieManager 取；逐跳头与长度由客户端自己定。
                if (BLOCKED_HEADERS.any { it.equals(name, ignoreCase = true) }) return@forEach
                val value = headers.optString(name)
                if (name.equals("Content-Type", ignoreCase = true)) contentType = value.toMediaTypeOrNull()
                runCatching { builder.header(name, value) }
            }
        }
        val body = if (method == "GET" || method == "HEAD") null else bodyBytes.toRequestBody(contentType)
        builder.method(method, body)

        EchWebTransfer.client.newCall(builder.build()).execute().use { response ->
            // 边读边卡上限：不能先 bytes() 整包读进来再判大小，那样大响应会直接把内存吃爆。
            val body = response.body
            if ((body?.contentLength() ?: -1L) > MAX_BODY_BYTES) {
                throw IllegalArgumentException("响应体过大，已阻断")
            }
            val buffer = okio.Buffer()
            body?.source()?.let { source -> buffer.write(source, MAX_BODY_BYTES.toLong() + 1) }
            if (buffer.size > MAX_BODY_BYTES) throw IllegalArgumentException("响应体过大，已阻断")
            val bytes = buffer.readByteArray()
            val headers = JSONObject()
            response.headers.names()
                .filterNot { it.equals("Set-Cookie", ignoreCase = true) }
                .forEach { name -> headers.put(name, response.header(name).orEmpty()) }
            return JSONObject()
                .put("status", response.code)
                .put("statusText", response.message)
                .put("url", response.request.url.toString())
                .put("headers", headers)
                .put("body", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    private fun respond(id: String, payloadJson: String) {
        val script = "window.${EchWebBridgeJs.CALLBACK}(${JSONObject.quote(id)}, $payloadJson)"
        view.post { runCatching { view.evaluateJavascript(script, null) } }
    }

    private companion object {
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        val BLOCKED_HEADERS = setOf(
            "Cookie", "Host", "Connection", "Content-Length", "Transfer-Encoding", "Accept-Encoding",
        )
    }
}

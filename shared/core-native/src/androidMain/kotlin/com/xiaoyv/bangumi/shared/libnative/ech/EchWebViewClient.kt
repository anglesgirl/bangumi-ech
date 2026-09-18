package com.xiaoyv.bangumi.shared.libnative.ech

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.multiplatform.webview.web.AccompanistWebViewClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream

/**
 * 受保护域名的 WebView 流量统一走 ECH。
 *
 * GET（含主文档与子资源）在这里直接代发；非 GET 的 body 不在 [WebResourceRequest] 里，
 * 由注入的 JS 桥代发。两条路都不允许回落到页面自带的明文 TLS 栈：
 * 受保护域名一旦失败就返回 502 页面，绝不返回 null 放行。
 */
internal class EchWebViewClient : AccompanistWebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url ?: return super.shouldInterceptRequest(view, request)
        if (!BgmEchPolicy.isProtected(url.host.orEmpty())) {
            return super.shouldInterceptRequest(view, request)
        }
        if (!request.method.equals("GET", ignoreCase = true)) {
            return blockedResponse("该请求需要传输桥，已被阻断（${request.method}）")
        }
        return try {
            val builder = Request.Builder().url(url.toString()).get()
            request.requestHeaders.forEach { (name, value) ->
                // Cookie 交给 CookieJar 统一从 CookieManager 取；跳转/协商类头不转发。
                if (name.equals("Cookie", true) || name.equals("Host", true) ||
                    name.equals("Connection", true) || name.equals("Accept-Encoding", true)
                ) {
                    return@forEach
                }
                runCatching { builder.header(name, value) }
            }
            val response = EchWebTransfer.client.newCall(builder.build()).execute()
            try {
                response.toWebResourceResponse()
            } catch (error: Exception) {
                response.close()
                throw error
            }
        } catch (error: Exception) {
            blockedResponse("ECH 通道不可用：${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        EchWebBridgeJs.inject(view, url)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        view?.let { EchWebBridgeJs.inject(it, url) }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        EchWebBridgeJs.inject(view, url)
    }

    private fun blockedResponse(reason: String): WebResourceResponse = WebResourceResponse(
        "text/html",
        "utf-8",
        502,
        "ECH required",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(EchWebBridgeJs.BLOCKED_PAGE.replace("__REASON__", reason).toByteArray()),
    )
}

/** 响应体流交给 WebView 消费，不能在这里关闭（关流即关闭响应）。 */
private fun Response.toWebResourceResponse(): WebResourceResponse {
    val stream = body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
    val contentType = header("Content-Type").orEmpty()
    val mimeType = contentType.substringBefore(';').trim().ifEmpty { "text/html" }
    val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(contentType)
        ?.groupValues?.get(1)?.trim()?.trim('"') ?: "utf-8"
    val headers = headers.names()
        .filterNot { it.equals("Set-Cookie", true) || it.equals("Content-Encoding", true) ||
            it.equals("Content-Length", true) || it.equals("Transfer-Encoding", true) }
        .associateWith { header(it).orEmpty() }
    return WebResourceResponse(mimeType, charset, code, message.ifEmpty { "OK" }, headers, stream)
}

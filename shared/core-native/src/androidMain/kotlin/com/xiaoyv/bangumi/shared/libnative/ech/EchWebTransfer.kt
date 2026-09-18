package com.xiaoyv.bangumi.shared.libnative.ech

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebView 流量的 ECH 传输：复用与原生请求同一套 Conscrypt ECH 栈与 DoH 策略。
 *
 * Cookie 与 WebView 的 CookieManager 双向同步：出站从 CookieManager 取，
 * 入站把 Set-Cookie 写回，保证页面里的登录态和原生请求不分叉。
 * 不自动跟跳：重定向的 Set-Cookie 必须先落进 CookieManager 再交给页面。
 */
internal object EchWebTransfer {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .apply { BgmEchTransport.configure(this) }
            .build()
    }

    /** WebView 的 CookieManager 是页面能看见的那一份，两边共用同一存储。 */
    private object WebViewCookieJar : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val header = runCatching { CookieManager.getInstance().getCookie(url.toString()) }
                .getOrNull()
                ?: return emptyList()
            return header.split(';').mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) null else runCatching { Cookie.parse(url, trimmed) }.getOrNull()
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
            cookies.forEach { cookie ->
                runCatching { manager.setCookie(url.toString(), cookie.toWebViewValue()) }
            }
            runCatching { manager.flush() }
        }
    }

    /**
     * WebView 不接受带 Domain 的写入，且 SameSite=None 必须配 Secure；
     * 这里统一去掉 Domain 与 Secure，把 SameSite=None 放宽为 Lax，否则 cookie 收不下。
     */
    private fun Cookie.toWebViewValue(): String = buildString {
        append(name).append('=').append(value)
        append("; Path=").append(if (path.isEmpty()) "/" else path)
        if (!hostOnly) append("; Domain=").append(domain)
        if (expiresAt < Long.MAX_VALUE / 2) {
            append("; Expires=").append(expiresValue(expiresAt))
        }
        // OkHttp 4.x 的 Cookie 不解析 SameSite；缺省即按 Lax 处理，这里不额外写。
        if (httpOnly) append("; HttpOnly")
    }

    /** SimpleDateFormat 非线程安全；每次调用新建，避免并发写坏格式。 */
    private fun expiresValue(expiresAt: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).format(Date(expiresAt))
}

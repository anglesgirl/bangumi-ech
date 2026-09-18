package com.xiaoyv.bangumi.shared.libnative.ech

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.conscrypt.Conscrypt
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.security.SecureRandom
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 在现有 OkHttp 构造入口安装 ECH，保留 Ktor 的业务、Cookie 和认证插件。
 * 受保护主机只走 ECH 和 DoH；此模块不接管 WebView。
 */
internal object BgmEchTransport {
    fun configure(builder: OkHttpClient.Builder) {
        val original = builder.build()
        val trustManager = original.x509TrustManager
            ?: throw IllegalStateException("缺少系统证书校验器")
        val policyTrustManager = BgmEchPolicy.PolicyTrustManager(trustManager)
        // 显式绑定 Provider，无需全局改变其他组件的 TLS Provider。
        val context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
            init(null, arrayOf(policyTrustManager), SecureRandom())
        }
        builder.sslSocketFactory(
            HostSocketFactory(context.socketFactory, original.sslSocketFactory),
            policyTrustManager,
        )
        // 受保护域名的地址只来自网关 DoH，绝不回落到会给出污染结果的系统解析。
        builder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (BgmEchPolicy.isProtected(hostname)) {
                    // ECH 记录自带的 ipv4hint 优先（CF 为配合 ECH 发布，常与 A 记录不同），
                    // 再补上 A 记录地址；连接层按序回退，避免单个地址不可达就整站失败。
                    val hinted = runCatching { BgmEchDoh.hints(hostname) }.getOrDefault(emptyList())
                    return (hinted + BgmEchDoh.resolve(hostname)).distinct()
                }
                // 只换地址的域名（无 ECH 记录）：固定地址优先，其次网关 DoH，最后系统解析。
                if (BgmEchPolicy.isDohOnly(hostname)) {
                    val pinned = BgmEchPolicy.pinnedAddresses(hostname).mapNotNull { value ->
                        runCatching { BgmEchDoh.parseIpv4Literal(value) }.getOrNull()
                    }
                    val resolved = runCatching { BgmEchDoh.resolve(hostname) }
                        .getOrElse { original.dns.lookup(hostname) }
                    return (pinned + resolved).distinct()
                }
                return original.dns.lookup(hostname)
            }
        })
        // 严格委托上游主机名校验；非默认验证器同时禁止 OkHttp 跨主机 H2 合并连接，
        // 防止保护域名借用另一主机已建立的非 ECH TLS 连接。
        builder.hostnameVerifier(HostnameVerifier { host, session ->
            original.hostnameVerifier.verify(host, session)
        })
        builder.proxy(null)
        builder.proxySelector(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                if (BgmEchPolicy.isProtected(uri.host.orEmpty())) return listOf(Proxy.NO_PROXY)
                return original.proxy?.let { listOf(it) } ?: original.proxySelector.select(uri)
            }

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
                original.proxySelector.connectFailed(uri, sa, ioe)
            }
        })
        // 网关可能为同一域名给出多个地址：按序回退，避免单个地址不可达就整页失败。
        // 只影响连接建立；收到响应后不重发请求，DoH 查询自身也不重试。
        builder.retryOnConnectionFailure(true)
        builder.followSslRedirects(false)
        builder.addInterceptor(Interceptor { chain ->
            requireHttps(chain.request())
            chain.proceed(chain.request())
        })
        // OkHttp 内部重定向的每一跳再次校验，禁止向保护域名发送明文 HTTP。
        builder.addNetworkInterceptor(Interceptor { chain ->
            requireHttps(chain.request())
            chain.proceed(chain.request())
        })
    }

    internal fun requireHttps(request: Request) {
        if (BgmEchPolicy.isProtected(request.url.host) && !request.url.isHttps) {
            throw IOException("受保护域名禁止明文 HTTP")
        }
    }

    private class HostSocketFactory(
        private val ech: SSLSocketFactory,
        private val original: SSLSocketFactory,
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = original.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> =
            (original.supportedCipherSuites + ech.supportedCipherSuites).distinct().toTypedArray()

        override fun createSocket(raw: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            if (!BgmEchPolicy.isProtected(host)) return original.createSocket(raw, host, port, autoClose)
            // 先取配置再建 TLS Socket，失败时不留下未关闭的 Socket。
            val config = BgmEchDoh.echConfig(host)
            val socket = ech.createSocket(raw, host, port, autoClose)
            try {
                if (socket !is SSLSocket) throw IOException("ECH 需要 TLS Socket")
                Conscrypt.setEchConfigList(socket, config)
                return socket
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw IOException("ECH 配置失败，已阻断", e)
            }
        }

        // 此工厂仅供 OkHttp 的分层 Socket 路径使用，禁止不带可信主机名的旁路。
        override fun createSocket(): Socket = throw IOException("要求带主机名的分层 TLS Socket")
        override fun createSocket(host: String, port: Int): Socket =
            throw IOException("要求带主机名的分层 TLS Socket")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            throw IOException("要求带主机名的分层 TLS Socket")
        override fun createSocket(host: InetAddress, port: Int): Socket =
            throw IOException("禁止无主机名的 TLS Socket")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            throw IOException("禁止无主机名的 TLS Socket")
    }
}

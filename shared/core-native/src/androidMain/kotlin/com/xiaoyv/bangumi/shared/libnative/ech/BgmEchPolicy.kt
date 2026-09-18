package com.xiaoyv.bangumi.shared.libnative.ech

import android.net.http.X509TrustManagerExtensions
import org.conscrypt.DomainEncryptionMode
import org.conscrypt.NetworkSecurityPolicy
import org.conscrypt.metrics.CertificateTransparencyVerificationReason
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.X509TrustManager

/**
 * 安卓 Bangumi ECH 策略。只定义保护范围和证书策略，不改变现有网络请求。
 * 接入 TLS 工厂时必须使用 [PolicyTrustManager]，并在握手前提供 ECHConfigList。
 * 本策略存在不代表网络已获得 ECH，WebView 也不会自动使用此策略。
 */
object BgmEchPolicy {
    /** 网关已发布 ECH 记录、必须走加密通道的域名。 */
    private val domains = setOf(
        "bgm.tv", "bangumi.tv", "chii.in",
        "pixiv.net", "pximg.net",
        // 作者自建图床代理：同样在 CF 上并发布了 ECH 记录。
        "xget.xiaoyv.com.cn",
        // AnimePic 图源：用户把 ECH 记录注入了这三个主机，JVM 探针实测跨 zone 配置可握手成功。
        "anime-pictures.net",
    )

    /**
     * 没有 ECH 记录、但地址会被污染的域名：只替换解析地址，**不动 TLS**（保持浏览器自身指纹）。
     * 人机验证类站点对 TLS 指纹敏感，所以这类域名不进 ECH 通道，只帮它换 IP。
     */
    private val dohOnlyDomains = setOf(
        "challenges.cloudflare.com",
        // 第三方图床：CF 上但没开 ECH，只帮它换掉污染地址。
        "i.pixiv.re",
    )

    /**
     * 只换地址的域名的首选地址：CF 边缘 IP，国内可达性由用户实测选定。
     * 这两个 IP 已实测能正确服务该域名（证书校验通过、`/cdn-cgi/trace` 回显该域名）。
     * 失效时自动退回网关 DoH，再退回系统解析。
     */
    private val dohOnlyAddresses = mapOf(
        // 优先用用户在国内实测优选过的日本段（172.64.229.0/24），再来一个同段备用。
        "challenges.cloudflare.com" to listOf("172.64.229.1", "172.64.229.20"),
    )

    /** 该域名的首选固定地址（可能为空）。 */
    fun pinnedAddresses(hostname: String): List<String> =
        dohOnlyAddresses[hostname.lowercase(Locale.ROOT).trimEnd('.')].orEmpty()

    fun isProtected(hostname: String): Boolean = matches(domains, hostname)

    fun isDohOnly(hostname: String): Boolean = matches(dohOnlyDomains, hostname)

    private fun matches(scope: Set<String>, hostname: String): Boolean {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        return scope.any { domain -> host == domain || host.endsWith(".$domain") }
    }

    /**
     * 启动预热用的主机：App 起来后马上会请求的那几个。
     * 冷启动时把地址与 ECH 配置先取回来，避免用户第一屏等两次 DoH 往返。
     */
    private val warmUpHosts = listOf(
        // bgm 系
        "bgm.tv",
        "api.bgm.tv",
        "next.bgm.tv",
        // 图片与图床
        "xget.xiaoyv.com.cn",
        "i.pximg.net",
        "api.anime-pictures.net",
        "opreviews.anime-pictures.net",
        "oimages.anime-pictures.net",
        // Pixiv
        "www.pixiv.net",
        "app-api.pixiv.net",
        // 人机验证（只换地址）
        "challenges.cloudflare.com",
    )

    fun warmUpHosts(): List<String> = warmUpHosts

    /** 供注入脚本使用：与 [isProtected] 同一份域名清单，避免两边各写一份。 */
    fun scriptDomains(): String = domains.sorted().joinToString(",", "[", "]") { "'$it'" }

    private val policy = object : NetworkSecurityPolicy {
        override fun isCertificateTransparencyVerificationRequired(hostname: String): Boolean = false

        override fun getCertificateTransparencyVerificationReason(hostname: String):
            CertificateTransparencyVerificationReason = CertificateTransparencyVerificationReason.UNKNOWN

        override fun getDomainEncryptionMode(hostname: String): DomainEncryptionMode =
            if (isProtected(hostname)) DomainEncryptionMode.REQUIRED
            else DomainEncryptionMode.DISABLED
    }

    /** 保留系统证书校验；公开方法名供 Conscrypt 反射使用，R8 必须保留。 */
    class PolicyTrustManager(private val delegate: X509TrustManager) : X509TrustManager {
        private val extensions = X509TrustManagerExtensions(delegate)

        fun getNetworkSecurityPolicy(): NetworkSecurityPolicy = policy

        /**
         * Conscrypt 与 OkHttp 通过公开重载传递真实主机名，保留系统的域名级信任配置。
         */
        @Suppress("unused")
        fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            hostname: String,
        ): List<X509Certificate> = extensions.checkServerTrusted(chain, authType, hostname)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            delegate.checkServerTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }
}

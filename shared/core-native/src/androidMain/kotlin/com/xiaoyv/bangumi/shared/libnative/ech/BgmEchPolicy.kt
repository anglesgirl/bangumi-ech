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
    private val domains = setOf("bgm.tv", "bangumi.tv", "chii.in")

    fun isProtected(hostname: String): Boolean {
        val host = hostname.lowercase(Locale.ROOT).trimEnd('.')
        return domains.any { domain -> host == domain || host.endsWith(".$domain") }
    }

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

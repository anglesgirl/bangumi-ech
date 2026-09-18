package com.xiaoyv.bangumi.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.xiaoyv.bangumi.shared.libnative.ech.BgmEchDoh
import com.xiaoyv.bangumi.shared.libnative.ech.BgmEchH3
import com.xiaoyv.bangumi.shared.libnative.ech.BgmEchPolicy
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.net.URI

/**
 * 受保护图片域的 H3+ECH 快速通道。
 * 只认「受保护域名」的图片静态 GET；失败一律交给 fallback（原 Ktor/OkHttp + Conscrypt 链路）。
 */
internal class H3ImageFetcher(
    private val url: String,
    private val options: Options,
    private val fallback: Fetcher?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val hit = runCatching { tryH3() }.getOrNull()
        if (hit != null) {
            return SourceFetchResult(
                source = ImageSource(source = FileSystem.SYSTEM.source(hit.absoluteFile.toOkioPath()), context = options.context),
                mimeType = null,
                dataSource = DataSource.NETWORK,
            )
        }
        // H3 不通（UDP 被限/握手失败/库没加载）→ 回落原有 TCP+ECH 链路
        return fallback?.fetch() ?: throw IllegalStateException("H3 与兜底链路均不可用")
    }

    private fun tryH3(): File? {
        val uri = URI(url)
        val host = uri.host ?: return null
        val ip = BgmEchDoh.resolve(host).firstOrNull()?.hostAddress ?: return null
        val ech = runCatching { BgmEchDoh.echConfig(host) }.getOrNull()
        val pathWithQuery = buildString {
            append(uri.rawPath ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val out = File(options.context.cacheDir, "h3-" + System.nanoTime() + ".bin")
        // Referer：P 站官方图床有防盗链
        val referer = if (host.endsWith("pximg.net")) "https://www.pixiv.net/" else null
        val file = BgmEchH3.fetchToFile(options.context, host, ip, ech, pathWithQuery, referer, out)
        return file ?: run { out.delete(); null }
    }
}

internal class H3ImageFetcherFactory(private val fallback: Fetcher.Factory) : Fetcher.Factory {
    override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher? {
        val url = data as? String ?: return null
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        if (!BgmEchPolicy.isProtected(host)) return null
        return H3ImageFetcher(url, options, fallback.create(data, options, imageLoader))
    }
}

actual fun h3ImageFetcherFactory(fallback: Fetcher.Factory): Fetcher.Factory? = H3ImageFetcherFactory(fallback)

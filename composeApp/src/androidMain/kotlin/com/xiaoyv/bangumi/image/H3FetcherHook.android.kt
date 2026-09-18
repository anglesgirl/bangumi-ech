package com.xiaoyv.bangumi.image

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.xiaoyv.bangumi.shared.libnative.ech.BgmEchH3
import okio.FileSystem
import okio.buffer
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * H3 熔断：某图片 URL 的 H3 失败（多半是这条线路限速/封了 UDP）后，短期直接走 TCP，
 * 避免每张图都白等一次握手超时。
 */
private object H3Breaker {
    private const val COOLDOWN_MS = 5 * 60 * 1000L
    private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun isOpen(url: String): Boolean = (blockedUntil[url] ?: 0L) > System.currentTimeMillis()
    fun trip(url: String) { blockedUntil[url] = System.currentTimeMillis() + COOLDOWN_MS }
    fun ok(url: String) { blockedUntil.remove(url) }
}

/** 受保护图片域：先走 H3+ECH，失败交给 fallback（原 Ktor/OkHttp + Conscrypt 链路）。 */
internal class H3ImageFetcher(
    private val url: String,
    private val options: Options,
    private val fallback: Fetcher?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val hit = runCatching { tryH3() }.getOrNull()
        if (hit != null) {
            return SourceFetchResult(
                source = ImageSource(
                    source = FileSystem.SYSTEM.source(hit.absoluteFile.toOkioPath()).buffer(),
                    fileSystem = FileSystem.SYSTEM,
                ),
                mimeType = null,
                dataSource = DataSource.NETWORK,
            )
        }
        return fallback?.fetch() ?: throw IllegalStateException("H3 与兜底链路均不可用")
    }

    private fun tryH3(): File? {
        if (H3Breaker.isOpen(url)) return null // 刚失败过：这段时间直接用 TCP
        // 域名是否受保护、IP/ECH 怎么取，都在 core-native 的门面里判定
        val file = BgmEchH3.fetchImageToFile(options.context, url)
        if (file != null) {
            H3Breaker.ok(url)
            return file
        }
        H3Breaker.trip(url)
        return null
    }
}

internal class H3ImageFetcherFactory(
    private val fallback: Fetcher.Factory<Uri>,
) : Fetcher.Factory<Uri> {

    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        val url = data.toString()
        if (!url.startsWith("http")) return null // 只接管网络图片
        return H3ImageFetcher(url, options, fallback.create(data, options, imageLoader))
    }
}

actual fun h3ImageFetcherFactory(fallback: Fetcher.Factory<Uri>): Fetcher.Factory<Uri>? =
    H3ImageFetcherFactory(fallback)

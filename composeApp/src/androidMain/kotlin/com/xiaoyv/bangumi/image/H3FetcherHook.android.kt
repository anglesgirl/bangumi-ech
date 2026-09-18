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
    // 线路抖动（尤其移动走香港）常常"单张断、刷新就好"，所以：
    // 连续 2 次失败才熔断，且只停 60 秒 —— 别把一次抖动放大成 5 分钟不走 H3。
    private const val STRIKES = 2
    private const val COOLDOWN_MS = 60 * 1000L
    private val fails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun isOpen(host: String): Boolean = (blockedUntil[host] ?: 0L) > System.currentTimeMillis()

    fun fail(host: String) {
        val n = (fails[host] ?: 0) + 1
        fails[host] = n
        if (n >= STRIKES) blockedUntil[host] = System.currentTimeMillis() + COOLDOWN_MS
    }

    fun ok(host: String) {
        fails.remove(host)
        blockedUntil.remove(host)
    }
}

private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull() ?: url

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
        val host = hostOf(url)
        if (H3Breaker.isOpen(host)) return null // 刚连续失败过：这段时间直接用 TCP
        // 域名是否受保护、IP/ECH 怎么取，都在 core-native 的门面里判定
        val file = BgmEchH3.fetchImageToFile(options.context, url)
        if (file != null) {
            H3Breaker.ok(host)
            return file
        }
        H3Breaker.fail(host)
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

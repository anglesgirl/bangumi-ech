package com.xiaoyv.bangumi.shared.data.repository.impl

import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.xiaoyv.bangumi.shared.core.types.settings.SettingUpdateChannel
import com.xiaoyv.bangumi.shared.core.utils.defaultJson
import com.xiaoyv.bangumi.shared.core.utils.runResult
import com.xiaoyv.bangumi.shared.data.api.client.ApiClient
import com.xiaoyv.bangumi.shared.data.model.response.bgm.ComposeUploadImage
import com.xiaoyv.bangumi.shared.data.model.response.chore.ComposeAppRelease
import com.xiaoyv.bangumi.shared.data.model.response.chore.ComposeBangumiStatus
import com.xiaoyv.bangumi.shared.data.model.response.trace.MicrosoftTranslate
import com.xiaoyv.bangumi.shared.data.repository.ChoreRepository
import com.xiaoyv.bangumi.shared.libnative.platformContext
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.ImageFormat
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.compressImage
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.source
import io.github.vinceglb.filekit.write
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.io.buffered
import okio.ByteString.Companion.encodeUtf8

class ChoreRepositoryImpl(private val client: ApiClient) : ChoreRepository {

    @OptIn(ExperimentalCoilApi::class)
    override suspend fun fetchPictureFileByUrl(url: String): Result<PlatformFile> = runResult {
        val imageLoader = SingletonImageLoader.get(platformContext)

        val request = ImageRequest.Builder(platformContext)
            .data(url)
            .build()

        when (val result = imageLoader.execute(request)) {
            is ErrorResult -> throw result.throwable
            is SuccessResult -> {
                val diskCache = requireNotNull(imageLoader.diskCache)
                val cacheKey = request.diskCacheKey ?: result.request.data.toString()

                val snapshot = diskCache.openSnapshot(cacheKey)
                    ?: diskCache.openSnapshot(url)
                    ?: throw IllegalStateException("Failed to load image using Coil: $url")

                snapshot.use {
                    val extension = runCatching {
                        PlatformFile(it.metadata.toString()).readString()
                    }.map { metadataText ->
                        when {
                            metadataText.contains("image/jpeg") || metadataText.contains("image/jpg") -> "jpg"
                            metadataText.contains("image/png") -> "png"
                            metadataText.contains("image/gif") -> "gif"
                            metadataText.contains("image/webp") -> "webp"
                            metadataText.contains("image/avif") -> "avif"
                            else -> "jpg"
                        }
                    }.recover {
                        url.substringAfterLast(".", "jpg").substringBefore("?")
                    }.getOrThrow()

                    val cachedPath = it.data
                    val saveDir = FileKit.cacheDir.div("image_cache_download").apply { createDirectories() }
                    val fileName = "${url.encodeUtf8().md5().hex()}.$extension"
                    val destFile = saveDir.div(fileName)
                    PlatformFile(cachedPath.toString()).copyTo(destFile)
                    destFile
                }
            }
        }
    }

    override suspend fun compressImageAndUpload(file: PlatformFile): Result<ComposeUploadImage> =
        runResult {
            val compressFile = compressImage(file).getOrThrow()
            uploadImage(compressFile).getOrThrow()
        }

    override suspend fun compressImage(file: PlatformFile): Result<PlatformFile> {
        val compressedBytes = FileKit.compressImage(
            bytes = file.readBytes(),
            quality = 90,
            maxWidth = 1024,
            maxHeight = 1024,
            imageFormat = ImageFormat.JPEG
        )

        // Save the compressed image
        val compressedFile = PlatformFile(FileKit.filesDir, "compressed.jpg")
        compressedFile.write(compressedBytes)
        return Result.success(compressedFile)
    }

    override suspend fun uploadImage(file: PlatformFile): Result<ComposeUploadImage> = runResult {
        val provider = InputProvider { file.source().buffered() }
        val multipart = MultiPartFormDataContent(formData {
            append("file", provider, Headers.build {
                append(HttpHeaders.ContentType, "image/jpeg")
                append(HttpHeaders.ContentDisposition, "filename=\"blog_image.jpg\"")
            })
        })
        client.bgmWebApi.submitUploadImage(multipart)
    }

    override suspend fun translate(text: String, isHtml: Boolean): Result<String> =
        client.requestTraceApi {
            val responseString = submitMicrosoftTranslate(
                textType = if (isHtml) "html" else "plain",
                param = listOf(text)
            )
            defaultJson.decodeFromString<List<MicrosoftTranslate>>(responseString).joinToString("\n") {
                it.translations.joinToString(", ") { translation ->
                    translation.text
                }
            }
        }

    override suspend fun fetchBangumiStatus(): Result<ComposeBangumiStatus> =
        client.requestChoreApi { fetchBangumiStatus() }

    override suspend fun fetchAppRelease(@SettingUpdateChannel channel: Int): Result<ComposeAppRelease> =
        client.requestChoreApi {
            when (channel) {
                SettingUpdateChannel.PREVIEW -> fetchPreReleaseAppRelease()
                else -> fetchLatestAppRelease()
            }
        }
}

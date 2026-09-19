package com.xiaoyv.bangumi.shared.data.repository.impl

import androidx.compose.ui.graphics.Color
import androidx.paging.PagingConfig
import com.xiaoyv.bangumi.shared.core.types.list.ListAlbumType
import com.xiaoyv.bangumi.shared.core.types.pixiv.PixivIllustSearchMode
import com.xiaoyv.bangumi.shared.core.types.pixiv.PixivIllustSearchRating
import com.xiaoyv.bangumi.shared.core.types.pixiv.PixivIllustrationSearchType
import com.xiaoyv.bangumi.shared.core.utils.parseHtmlHexColor
import com.xiaoyv.bangumi.shared.core.utils.runResult
import com.xiaoyv.bangumi.shared.core.utils.toApiOffset
import com.xiaoyv.bangumi.shared.core.utils.toApiPage
import com.xiaoyv.bangumi.shared.data.api.client.ApiClient
import com.xiaoyv.bangumi.shared.data.model.request.list.album.ListAlbumParam
import com.xiaoyv.bangumi.shared.data.model.response.bgm.ComposeMono
import com.xiaoyv.bangumi.shared.data.model.response.image.ComposeAnimePictureImage
import com.xiaoyv.bangumi.shared.data.model.response.image.ComposeGallery
import com.xiaoyv.bangumi.shared.data.parser.bgm.SubjectParser
import com.xiaoyv.bangumi.shared.data.repository.ImageRepository
import com.xiaoyv.bangumi.shared.data.repository.datasource.MemoryPagingController
import com.xiaoyv.bangumi.shared.data.repository.datasource.createMemoryPageLimitPagingController
import com.xiaoyv.bangumi.shared.data.repository.datasource.createMemoryStepUniquePagingController
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/**
 * [ImageRepositoryImpl]
 *
 * @since 2025/5/22
 */
class ImageRepositoryImpl(
    private val client: ApiClient,
    private val pagingConfig: PagingConfig,
    private val subjectParser: SubjectParser,
) : ImageRepository {

    /** 解析 Anime-Pictures 详情用：宽容未知字段，避免接口加字段就解析失败 */
    private val detailJson = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun fetchAlbumPager(param: ListAlbumParam): MemoryPagingController<ComposeGallery, String> {
        return createMemoryPageLimitPagingController(
            pagingConfig = pagingConfig,
            idSelector = { it.id },
            onLoadData = { page ->
                fetchAlbumList(param, page, pagingConfig.pageSize).getOrThrow()
            }
        )
    }


    override fun fetchAnimePictures(
        searchTags: String?,
        deniedTags: String?,
    ): MemoryPagingController<ComposeGallery, String> {
        return createMemoryPageLimitPagingController(
            pagingConfig = pagingConfig,
            idSelector = { it.id },
            onLoadData = { page ->
                val picture = client.imageApi.fetchAnimePictures(
                    searchTags = searchTags,
                    deniedTags = deniedTags,
                    page = page,
                    size = pagingConfig.pageSize
                )
                picture.posts.orEmpty().map {
                    ComposeGallery(
                        id = it.id,
                        type = ListAlbumType.ANIME_PICTURES,
                        image = it.url,
                        original = it.largeUrl,
                        width = it.width,
                        height = it.height,
                        size = it.size,
                        color = it.color.orEmpty()
                    )
                }
            }
        )
    }

    override fun fetchPixivPictures(tag: String): MemoryPagingController<ComposeGallery, String> {
        return createMemoryStepUniquePagingController(
            pagingConfig = pagingConfig,
            idSelector = { it.id },
            onLoadData = { key ->
                val offset = key ?: 0
                val illusts = client.requestPixivAjaxApi {
                    searchIllustrations(
                        keyword = tag,
                        mode = PixivIllustSearchRating.ALL,
                        searchMode = PixivIllustSearchMode.TAG_TITLE_AND_CAPTION,
                        type = PixivIllustrationSearchType.ILLUST,
                        page = offset.toApiPage(pagingConfig.pageSize)
                    ).body?.illust?.data.orEmpty()
                }.getOrThrow()

                val items = illusts.map {
                    ComposeGallery(
                        id = it.id.toString(),
                        type = ListAlbumType.PIVIX,
                        image = it.url,
                        original = it.url,
                        width = it.width,
                        height = it.height,
                        count = it.pageCount
                    )
                }
                val nextKey = if (items.isEmpty()) null else offset + items.size

                items to nextKey
            }
        )
    }

    override suspend fun fetchAlbumList(param: ListAlbumParam, page: Int, size: Int): Result<List<ComposeGallery>> {
        return when (param.type) {
            ListAlbumType.CHARACTER_ALBUM -> client.requestWebApi {
                with(subjectParser) {
                    fetchCharacterAlbum(param.characterId, page)
                        .fetchCharacterAlbumCoverted()
                        .map { it.copy(type = param.type) }
                }
            }

            ListAlbumType.SUBJECT_PREVIEW -> {
                if (param.doubanId.isBlank() || param.doubanType.isBlank()) {
                    Result.success(emptyList())
                } else client.requestDouBanApi {
                    client.dbApi
                        .queryDouBanPhotoList(param.doubanId, param.doubanType, page.toApiOffset(size), size)
                        .copy(doubanMediaId = param.doubanId)
                        .photos.map {
                            val hexColor = parseHtmlHexColor(it.image.primaryColor.orEmpty()) ?: Color.LightGray
                            val largeImage = it.displayLargeImage

                            ComposeGallery(
                                id = largeImage.url.orEmpty(),
                                type = param.type,
                                width = largeImage.width,
                                height = largeImage.height,
                                image = largeImage.url.orEmpty(),
                                original = largeImage.url.orEmpty(),
                                color = listOf(
                                    (hexColor.red * 255).roundToInt().coerceIn(0, 255),
                                    (hexColor.green * 255).roundToInt().coerceIn(0, 255),
                                    (hexColor.blue * 255).roundToInt().coerceIn(0, 255),
                                )
                            )
                        }
                }
            }

            else -> error("not support")
        }
    }


    override suspend fun fetchAnimePictureDetail(id: String): Result<List<ComposeGallery>> =
        runResult {
            val body = client.imageApi.fetchAnimePictureDetail(id)
            // 兼容两种返回形态：扁平帖子本体 / 包一层 {"post": {...}}
            val image = body["post"]
                ?.let { detailJson.decodeFromJsonElement<ComposeAnimePictureImage>(it) }
                ?: detailJson.decodeFromJsonElement(body)
            listOf(
                ComposeGallery(
                    id = image.id,
                    type = ListAlbumType.ANIME_PICTURES,
                    image = image.url,
                    original = image.largeUrl,
                    width = image.width,
                    height = image.height,
                    size = image.size,
                    count = 1,
                )
            )
        }

    override suspend fun fetchPixivPictureDetail(id: String): Result<List<ComposeGallery>> =
        runResult {
            client.imageApi.fetchPixivIllustDetail(id).body.orEmpty().let {
                it.map { item ->
                    ComposeGallery(
                        id = item.id.orEmpty(),
                        type = ListAlbumType.PIVIX,
                        image = item.urls?.regular.orEmpty(),
                        original = item.urls?.original.orEmpty(),
                        width = item.width,
                        height = item.height,
                        count = it.size
                    )
                }
            }
        }

    override suspend fun fetchAnimePictureTag(data: ComposeMono): Result<List<String>> =
        runResult {
            // anime-pictures 的标签索引按原名（多为日文汉字/假名）建立，原名必须保留。
            // 历史 bug：原实现用「纯汉字即过滤」(^[\u4e00-\u9fa5]+$) 想丢掉中文名，
            // 但日文汉字名也落在同一区间，于是原名被删光、搜索词为空，
            // 结果是任何角色都返回 0 条、页面永远显示「暂无内容」。
            val original = data.name.trim()
            if (original.isNotEmpty()) return@runResult listOf(original)

            // 仅当原名缺失时才退回别名，并排除中文译名。
            val nameInfo = data.infobox.find { it.key == "别名" }?.value
            val aliases = if (nameInfo !is JsonArray) {
                listOfNotNull(nameInfo?.jsonPrimitive?.contentOrNull)
            } else {
                nameInfo.mapNotNull {
                    (it as? JsonObject)?.getValue("v")?.jsonPrimitive?.contentOrNull
                }
            }
            aliases
                .map(String::trim)
                .filter { it.isNotEmpty() && it != data.nameCN }
                .distinct()
        }
}

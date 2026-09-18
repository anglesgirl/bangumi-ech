package com.xiaoyv.bangumi.shared.data.model.response.image

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [ComposeAnimePicture]
 *
 * @since 2025/5/22
 */
@Immutable
@Serializable
data class ComposeAnimePicture(
    @SerialName("max_pages")
    val maxPages: Int = 0,
    @SerialName("page_number")
    val pageNumber: Int = 0,
    @SerialName("posts")
    val posts: List<ComposeAnimePictureImage>? = null,
    @SerialName("posts_count")
    val postsCount: Int = 0,
    @SerialName("posts_per_page")
    val postsPerPage: Int = 0,
    @SerialName("response_posts_count")
    val responsePostsCount: Int = 0,
)

@Immutable
@Serializable
data class ComposeAnimePictureImage(
    @SerialName("id")
    val id: String = "",
    @SerialName("artefacts_degree")
    val artefactsDegree: Double = 0.0,
    @SerialName("color")
    val color: List<Int>? = null,
    @SerialName("datetime")
    val datetime: String? = null,
    @SerialName("download_count")
    val downloadCount: Int = 0,
    @SerialName("erotics")
    val erotics: Int = 0,
    @SerialName("ext")
    val ext: String? = null,
    @SerialName("have_alpha")
    val haveAlpha: Boolean = false,
    @SerialName("height")
    val height: Int = 0,
    @SerialName("md5")
    val md5: String? = null,
    @SerialName("md5_pixels")
    val md5Pixels: String? = null,
    @SerialName("pubtime")
    val pubtime: String? = null,
    @SerialName("score")
    val score: Float = 0f,
    @SerialName("score_number")
    val scoreNumber: Int = 0,
    @SerialName("size")
    val size: Long = 0,
    @SerialName("smooth_degree")
    val smoothDegree: Double = 0.0,
    @SerialName("spoiler")
    val spoiler: Boolean = false,
    @SerialName("status")
    val status: Int = 0,
    @SerialName("status_type")
    val statusType: Int = 0,
    @SerialName("tags_count")
    val tagsCount: Int = 0,
    @SerialName("width")
    val width: Int = 0,
) {
    private val dir get() = if (md5.orEmpty().length > 3) md5.orEmpty().substring(0, 3) else ""
    val url get() = "https://opreviews.anime-pictures.net/$dir/${md5}_lp.avif"
    /**
     * 详情页大图。依据 HAR 实测：站点自己的放大视图用的是 opreviews 的 `_bp.avif`
     * （26 次请求全 200，14~74KB）；而 oimages 原图必须带 `?if=ANIME-PICTURES.NET_-_<id>-<w>x<h>-<文件名>`
     * 令牌，缺令牌一律 302 跳首页 —— 上游拼的 URL 正是缺令牌，所以详情页一直出不来图。
     */
    val largeUrl get() = "https://oimages.anime-pictures.net/$dir/$md5$ext?if=ANIME-PICTURES.NET"
}


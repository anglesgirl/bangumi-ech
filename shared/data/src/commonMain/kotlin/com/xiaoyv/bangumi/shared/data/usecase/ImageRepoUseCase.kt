package com.xiaoyv.bangumi.shared.data.usecase

import com.xiaoyv.bangumi.shared.core.types.list.ListAlbumType
import com.xiaoyv.bangumi.shared.data.model.response.image.ComposeGallery
import com.xiaoyv.bangumi.shared.data.repository.ImageRepository

/**
 * [ImageRepoUseCase]
 *
 * @since 2025/5/24
 */
class ImageRepoUseCase(
    private val imageRepository: ImageRepository,
) {

    suspend fun fetchPictureGallery(
        id: String,
        @ListAlbumType type: Int,
    ): Result<List<ComposeGallery>> {
        return when (type) {
            ListAlbumType.PIVIX -> imageRepository.fetchPixivPictureDetail(id)
            // 原实现 else 一律 emptyList()，导致 AnimePic 点进去永远是空页面
            ListAlbumType.ANIME_PICTURES -> imageRepository.fetchAnimePictureDetail(id)
            else -> Result.success(emptyList())
        }
    }
}
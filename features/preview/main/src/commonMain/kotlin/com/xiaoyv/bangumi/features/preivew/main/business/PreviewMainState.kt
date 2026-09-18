package com.xiaoyv.bangumi.features.preivew.main.business

import androidx.compose.runtime.Immutable
import com.xiaoyv.bangumi.shared.core.utils.serialization.SerializeList
import com.xiaoyv.bangumi.shared.ui.component.tab.ComposeTextTab
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * [PreviewMainState]
 *
 * @author why
 * @since 2025/1/12
 */
@Immutable
data class PreviewMainState(
    val index: Int,
    val title: String,
    val items: SerializeList<String> = persistentListOf(),
    /** 与 [items] 同下标的占位图；加载原图期间先显示它，避免白屏转圈。 */
    val placeholders: SerializeList<String> = persistentListOf(),
    val contextMenus: PersistentList<ComposeTextTab<Int>> = persistentListOf(),
)

package com.xiaoyv.bangumi.features.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xiaoyv.bangumi.core_resource.resources.Res
import com.xiaoyv.bangumi.core_resource.resources.global_ok
import com.xiaoyv.bangumi.core_resource.resources.settings_home_shortcuts_reorder
import com.xiaoyv.bangumi.core_resource.resources.settings_home_shortcuts_sheet
import com.xiaoyv.bangumi.shared.ui.component.tab.ComposeDrawableTab
import com.xiaoyv.bangumi.shared.ui.composition.TabTokens
import com.xiaoyv.bangumi.shared.ui.theme.ContentMargin
import com.xiaoyv.bangumi.shared.ui.theme.ContentMarginHalf
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** 单行高度，拖动换位的阈值按它算。 */
private val ShortcutRowHeight = 64.dp

/**
 * 首页快捷入口的显示与排序面板。
 *
 * - 长按并上下拖动可以调整顺序；
 * - 右侧开关控制是否在首页显示；
 * - 点“确定”回写 [order]（顺序）与 [hidden]（隐藏项）。
 *
 * @param order 当前保存的顺序，元素为 [com.xiaoyv.bangumi.shared.core.types.FeatureType] 的值
 * @param hidden 当前被隐藏的入口 id
 * @param onDismiss 关闭面板
 * @param onApply 回写新的顺序与隐藏列表
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeShortcutSheet(
    order: List<String>,
    hidden: Collection<String>,
    onDismiss: () -> Unit,
    onApply: (List<String>, List<String>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tabs = remember { mutableStateListOf<ComposeDrawableTab>() }
    val hiddenIds = remember { mutableStateListOf<String>().apply { addAll(hidden) } }

    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            tabs.addAll(TabTokens.resolvedHomeActions(order, emptyList()))
        }
    }

    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ShortcutRowHeight.toPx() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ContentMargin)
                .padding(bottom = ContentMargin),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(Res.string.settings_home_shortcuts_sheet),
                    style = MaterialTheme.typography.titleLarge,
                )
                // 顶栏常驻的“确定”：列表再长也点得到，不依赖底部按钮是否被挤出可视区。
                TextButton(
                    onClick = { onApply(tabs.map { it.type.toString() }, hiddenIds.toList()) },
                ) {
                    Text(text = stringResource(Res.string.global_ok))
                }
            }
            Text(
                modifier = Modifier.padding(top = ContentMarginHalf, bottom = ContentMargin),
                text = stringResource(Res.string.settings_home_shortcuts_reorder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                tabs.forEachIndexed { index, tab ->
                    key(tab.type) {
                        val visible = hiddenIds.none { it == tab.type }
                        val dragging = index == dragIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ShortcutRowHeight)
                                .zIndex(if (dragging) 1f else 0f)
                                .offset { IntOffset(0, if (dragging) dragOffset.roundToInt() else 0) }
                                .background(
                                    if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh
                                    else MaterialTheme.colorScheme.surface
                                )
                                .pointerInput(tab.type) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragIndex = index
                                            dragOffset = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            val from = dragIndex
                                            if (from < 0) return@detectDragGesturesAfterLongPress
                                            dragOffset += amount.y
                                            if (dragOffset > rowHeightPx / 2 && from < tabs.lastIndex) {
                                                tabs.add(from + 1, tabs.removeAt(from))
                                                dragIndex = from + 1
                                                dragOffset -= rowHeightPx
                                            } else if (dragOffset < -rowHeightPx / 2 && from > 0) {
                                                tabs.add(from - 1, tabs.removeAt(from))
                                                dragIndex = from - 1
                                                dragOffset += rowHeightPx
                                            }
                                        },
                                        onDragEnd = {
                                            dragIndex = -1
                                            dragOffset = 0f
                                        },
                                        onDragCancel = {
                                            dragIndex = -1
                                            dragOffset = 0f
                                        },
                                    )
                                }
                                .padding(horizontal = ContentMarginHalf),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ContentMargin),
                        ) {
                            Icon(
                                modifier = Modifier.size(24.dp),
                                painter = painterResource(tab.icon),
                                contentDescription = null,
                                tint = if (visible) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                modifier = Modifier.weight(1f),
                                text = stringResource(tab.label),
                                color = if (visible) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outline,
                            )
                            Switch(
                                checked = visible,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        hiddenIds.removeAll { it == tab.type }
                                    } else if (hiddenIds.none { it == tab.type }) {
                                        hiddenIds.add(tab.type.toString())
                                    }
                                },
                            )
                            Text(
                                text = "≡",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ContentMargin),
                onClick = { onApply(tabs.map { it.type.toString() }, hiddenIds.toList()) },
            ) {
                Text(text = stringResource(Res.string.global_ok))
            }
        }
    }
}

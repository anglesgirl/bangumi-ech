package com.xiaoyv.bangumi.features.subject.browser

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LineStyle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiaoyv.bangumi.core_resource.resources.Res
import com.xiaoyv.bangumi.core_resource.resources.search_result_title
import com.xiaoyv.bangumi.core_resource.resources.global_all
import com.xiaoyv.bangumi.core_resource.resources.global_date
import com.xiaoyv.bangumi.core_resource.resources.global_kind
import com.xiaoyv.bangumi.core_resource.resources.global_search
import com.xiaoyv.bangumi.core_resource.resources.global_sort
import com.xiaoyv.bangumi.core_resource.resources.subject_browser_month_unit
import com.xiaoyv.bangumi.core_resource.resources.subject_browser_year_unit
import com.xiaoyv.bangumi.core_resource.resources.type_path_browser
import com.xiaoyv.bangumi.features.subject.browser.business.SubjectBrowserEvent
import com.xiaoyv.bangumi.features.subject.browser.business.SubjectBrowserState
import com.xiaoyv.bangumi.features.subject.browser.business.SubjectBrowserViewModel
import com.xiaoyv.bangumi.features.subject.page.SubjectPageRoute
import com.xiaoyv.bangumi.shared.core.mvi.UiState
import com.xiaoyv.bangumi.shared.core.types.SubjectSortBrowserType
import com.xiaoyv.bangumi.shared.core.types.SubjectType
import com.xiaoyv.bangumi.shared.data.manager.shared.LocalHideNavIcon
import com.xiaoyv.bangumi.shared.ui.component.bar.BgmTopAppBar
import com.xiaoyv.bangumi.shared.ui.component.chip.DropMenuChip
import com.xiaoyv.bangumi.shared.ui.component.dialog.alert.rememberAlertDialogState
import com.xiaoyv.bangumi.shared.ui.component.dialog.date.MonthPicker
import com.xiaoyv.bangumi.shared.ui.component.layout.state.StateLayout
import com.xiaoyv.bangumi.shared.ui.component.navigation.Screen
import com.xiaoyv.bangumi.shared.ui.theme.ContentMarginHalf
import com.xiaoyv.bangumi.shared.ui.composition.TabTokens
import com.xiaoyv.bangumi.shared.ui.composition.TabTokens.subjectBrowserSortTabs
import com.xiaoyv.bangumi.shared.ui.kts.collectBaseSideEffect
import com.xiaoyv.bangumi.shared.ui.theme.BgmIcons
import com.xiaoyv.bangumi.shared.ui.theme.BgmIconsMirrored
import kotlinx.collections.immutable.toPersistentList
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xiaoyv.bangumi.core_resource.resources.global_cancel
import com.xiaoyv.bangumi.core_resource.resources.global_ok
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

@Composable
fun SubjectBrowserRoute(
    viewModel: SubjectBrowserViewModel,
    onNavUp: () -> Unit,
    onNavScreen: (Screen) -> Unit,
) {
    val baseState by viewModel.collectAsState()

    viewModel.collectBaseSideEffect {

    }

    SubjectBrowserScreen(
        uiState = baseState,
        onActionEvent = viewModel::onEvent,
        onUiEvent = {
            when (it) {
                is SubjectBrowserEvent.UI.OnNavUp -> onNavUp()
                is SubjectBrowserEvent.UI.OnNavScreen -> onNavScreen(it.screen)
            }
        },
    )
}

@Composable
private fun SubjectBrowserScreen(
    uiState: UiState<SubjectBrowserState>,
    onUiEvent: (SubjectBrowserEvent.UI) -> Unit,
    onActionEvent: (SubjectBrowserEvent.Action) -> Unit,
) {
    var showKeywordDialog by remember { mutableStateOf(false) }
    var keywordInput by remember { mutableStateOf(uiState.data.param.browser.keyword) }

    if (showKeywordDialog) {
        AlertDialog(
            onDismissRequest = { showKeywordDialog = false },
            title = { Text(text = stringResource(Res.string.search_result_title)) },
            text = {
                OutlinedTextField(
                    value = keywordInput,
                    onValueChange = { keywordInput = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        showKeywordDialog = false
                        val body = uiState.data.param.browser
                        val target = keywordInput.trim()
                        if (target != body.keyword) {
                            onActionEvent(
                                SubjectBrowserEvent.Action.OnUpdateBrowserSubjectParam(
                                    body = body.copy(keyword = target)
                                )
                            )
                        }
                    }),
                    placeholder = { Text(text = stringResource(Res.string.global_search)) },
                    trailingIcon = {
                        IconButton(onClick = { keywordInput = "" }) {
                            Icon(imageVector = Icons.Rounded.Close, contentDescription = null)
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showKeywordDialog = false
                    val body = uiState.data.param.browser
                    val target = keywordInput.trim()
                    if (target != body.keyword) {
                        onActionEvent(
                            SubjectBrowserEvent.Action.OnUpdateBrowserSubjectParam(
                                body = body.copy(keyword = target)
                            )
                        )
                    }
                }) { Text(text = stringResource(Res.string.global_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showKeywordDialog = false }) {
                    Text(text = stringResource(Res.string.global_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BgmTopAppBar(
                title = uiState.data.run {
                    buildString {
                        if (title.isNotBlank()) {
                            append(title)
                        } else {
                            append(stringResource(Res.string.type_path_browser))
                            append(" - ")
                            append(stringResource(SubjectSortBrowserType.string(param.browser.sort)))
                            if (param.browser.tags.isNotEmpty()) {
                                append(" - ")
                                append(param.browser.tags.joinToString("，"))
                            }
                            if (param.browser.keyword.isNotBlank()) {
                                append(" - ")
                                append(param.browser.keyword)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { onActionEvent(SubjectBrowserEvent.Action.OnChangeLayoutMode) }) {
                        uiState.data.run {
                            Icon(
                                imageVector = if (param.ui.gridLayout) BgmIcons.LineStyle else BgmIcons.GridView,
                                contentDescription = null
                            )
                        }
                    }
                    IconButton(onClick = { showKeywordDialog = true }) {
                        Icon(
                            imageVector = BgmIcons.Search,
                            contentDescription = stringResource(Res.string.global_search)
                        )
                    }
                },
                navigationIcon = if (LocalHideNavIcon.current) null else {
                    {
                        IconButton(onClick = { onUiEvent(SubjectBrowserEvent.UI.OnNavUp) }) {
                            Icon(
                                imageVector = BgmIconsMirrored.ArrowBack,
                                contentDescription = null
                            )
                        }
                    }
                }
            )
        }
    ) {
        StateLayout(
            modifier = Modifier
                .fillMaxSize()
                .padding(it),
            onRefresh = { onActionEvent(SubjectBrowserEvent.Action.OnRefresh(it)) },
            uiState = uiState,
        ) { state ->
            SubjectBrowserScreenContent(state, onUiEvent, onActionEvent)
        }
    }
}


@Composable
private fun SubjectBrowserScreenContent(
    state: SubjectBrowserState,
    onUiEvent: (SubjectBrowserEvent.UI) -> Unit,
    onActionEvent: (SubjectBrowserEvent.Action) -> Unit,
) {
    val param = state.param

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = ContentMarginHalf, horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 从标签页进入时类型已在上层选过，这里隐藏类型切换。
            if (!param.browser.hideTypeTabs) DropMenuChip(
                labelPrefix = stringResource(Res.string.global_kind),
                current = param.browser.subjectType,
                options = TabTokens.subjectTypeTabs,
                onOptionClick = {
                    onActionEvent(
                        SubjectBrowserEvent.Action.OnUpdateBrowserSubjectParam(
                            // cat 是按条目类型区分的（动画 1/2/3/5，书籍 1001…，游戏 4001…），
                            // 换类型必须归零，否则会带着上一个类型的 cat 去查，结果是空列表。
                            body = param.browser.copy(subjectType = it.type, cat = 0, series = false)
                        )
                    )
                }
            )

            // 动画子类型筛选（TV/WEB/OVA/剧场版），仅动画类型时显示
            if (param.browser.subjectType == SubjectType.ANIME) {
                DropMenuChip(
                    labelPrefix = stringResource(Res.string.global_kind),
                    current = param.browser.cat,
                    options = TabTokens.subjectAnimeCategoryTabs,
                    onOptionClick = {
                        onActionEvent(
                            SubjectBrowserEvent.Action.OnUpdateBrowserSubjectParam(
                                body = param.browser.copy(cat = it.type)
                            )
                        )
                    }
                )
            }

            // 是否隐藏排序过滤项，比如需要固定显示某些排序时，需要隐藏
            if (!state.param.browser.hideSortFilter) DropMenuChip(
                labelPrefix = stringResource(Res.string.global_sort),
                current = param.browser.sort,
                options = subjectBrowserSortTabs.let {
                    // 由于带标签的数据是通过搜索接口实现的，这里暂时不支持发售日过滤
                    if (state.param.browser.tags.isNotEmpty()) it.filter { type -> type.type != SubjectSortBrowserType.DATE }
                        .toPersistentList() else it
                },
                onOptionClick = {
                    onActionEvent(
                        SubjectBrowserEvent.Action.OnUpdateBrowserSubjectParam(
                            body = param.browser.copy(sort = it.type)
                        )
                    )
                }
            )

            // 是否隐藏日期过滤项，比如需要固定显示某些日期时，需要隐藏
            if (!state.param.browser.hideDateFilter) {
                val yearPickerDialogState = rememberAlertDialogState()

                MonthPicker(
                    dialogState = yearPickerDialogState,
                    currentMonth = param.browser.month,
                    currentYear = param.browser.year,
                    onConfirm = { year, month ->
                        onActionEvent(
                            SubjectBrowserEvent.Action.OnUpdateBrowserSubjectParam(
                                body = param.browser.copy(year = year, month = month)
                            )
                        )
                    }
                )

                AssistChip(
                    onClick = { yearPickerDialogState.show() },
                    colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    label = {
                        Text(
                            text = buildString {
                                append(stringResource(Res.string.global_date))
                                append(" ")

                                val year = param.browser.year
                                val month = param.browser.month
                                if (year == 0 && month == 0) {
                                    append(stringResource(Res.string.global_all))
                                } else {
                                    if (year > 0) append("$year${stringResource(Res.string.subject_browser_year_unit)}")
                                    if (month > 0) append("$month${stringResource(Res.string.subject_browser_month_unit)}")
                                }
                            }
                        )
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            SubjectPageRoute(
                param = param,
                onNavScreen = {
                    onUiEvent(SubjectBrowserEvent.UI.OnNavScreen(it))
                }
            )
        }
    }
}



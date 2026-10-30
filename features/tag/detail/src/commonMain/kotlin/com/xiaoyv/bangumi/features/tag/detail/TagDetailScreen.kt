package com.xiaoyv.bangumi.features.tag.detail

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.xiaoyv.bangumi.core_resource.resources.Res
import com.xiaoyv.bangumi.core_resource.resources.search_tag_title
import com.xiaoyv.bangumi.core_resource.resources.global_search
import com.xiaoyv.bangumi.core_resource.resources.global_tag
import com.xiaoyv.bangumi.features.tag.detail.business.TagDetailEvent
import com.xiaoyv.bangumi.features.tag.detail.business.TagDetailState
import com.xiaoyv.bangumi.features.tag.detail.business.TagDetailViewModel
import com.xiaoyv.bangumi.features.tag.page.TagPageRoute
import com.xiaoyv.bangumi.shared.core.mvi.UiState
import com.xiaoyv.bangumi.shared.core.types.list.ListTagType
import com.xiaoyv.bangumi.shared.data.model.request.list.tag.ListTagParam
import com.xiaoyv.bangumi.shared.ui.component.bar.BgmTopAppBar
import com.xiaoyv.bangumi.shared.ui.component.layout.state.StateLayout
import com.xiaoyv.bangumi.shared.ui.component.navigation.Screen
import com.xiaoyv.bangumi.shared.ui.component.pager.BgmTabHorizontalPager
import com.xiaoyv.bangumi.shared.ui.composition.TabTokens.subjectTypeTabs
import com.xiaoyv.bangumi.shared.ui.kts.collectBaseSideEffect
import com.xiaoyv.bangumi.shared.ui.theme.BgmIcons
import org.jetbrains.compose.resources.stringResource
import org.orbitmvi.orbit.compose.collectAsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xiaoyv.bangumi.core_resource.resources.global_cancel
import com.xiaoyv.bangumi.core_resource.resources.global_ok
import com.xiaoyv.bangumi.shared.data.model.request.list.tag.TagSearchBody
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

@Composable
fun TagDetailRoute(
    viewModel: TagDetailViewModel,
    onNavUp: () -> Unit,
    onNavScreen: (Screen) -> Unit,
) {
    val baseState by viewModel.collectAsState()

    viewModel.collectBaseSideEffect {

    }

    TagDetailScreen(
        uiState = baseState,
        onActionEvent = viewModel::onEvent,
        onUiEvent = {
            when (it) {
                is TagDetailEvent.UI.OnNavUp -> onNavUp()
                is TagDetailEvent.UI.OnNavScreen -> onNavScreen(it.screen)
            }
        },
    )
}

@Composable
private fun TagDetailScreen(
    uiState: UiState<TagDetailState>,
    onUiEvent: (TagDetailEvent.UI) -> Unit,
    onActionEvent: (TagDetailEvent.Action) -> Unit,
) {

    var showTagSearchDialog by remember { mutableStateOf(false) }
    var tagKeyword by remember { mutableStateOf("") }
    var tagKeywordInput by remember { mutableStateOf("") }

    if (showTagSearchDialog) {
        AlertDialog(
            onDismissRequest = { showTagSearchDialog = false },
            title = { Text(text = stringResource(Res.string.search_tag_title)) },
            text = {
                OutlinedTextField(
                    value = tagKeywordInput,
                    onValueChange = { tagKeywordInput = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        showTagSearchDialog = false
                        tagKeyword = tagKeywordInput.trim()
                    }),
                    placeholder = { Text(text = stringResource(Res.string.global_search)) },
                    trailingIcon = {
                        IconButton(onClick = { tagKeywordInput = "" }) {
                            Icon(imageVector = Icons.Rounded.Close, contentDescription = null)
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showTagSearchDialog = false
                    tagKeyword = tagKeywordInput.trim()
                }) { Text(text = stringResource(Res.string.global_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTagSearchDialog = false
                    tagKeywordInput = ""
                    tagKeyword = ""
                }) { Text(text = stringResource(Res.string.global_cancel)) }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BgmTopAppBar(
                title = stringResource(Res.string.global_tag),
                actions = {
                    IconButton(onClick = { showTagSearchDialog = true }) {
                        Icon(
                            imageVector = BgmIcons.Search,
                            contentDescription = stringResource(Res.string.global_search)
                        )
                    }
                },
                onNavigationClick = { onUiEvent(TagDetailEvent.UI.OnNavUp) }
            )
        }
    ) {
        StateLayout(
            modifier = Modifier
                .fillMaxSize()
                .padding(it),
            onRefresh = { onActionEvent(TagDetailEvent.Action.OnRefresh(it)) },
            uiState = uiState,
        ) { state ->
            TagDetailScreenContent(state, tagKeyword, onUiEvent, onActionEvent)
        }
    }
}


@Composable
private fun TagDetailScreenContent(
    state: TagDetailState,
    tagKeyword: String,
    onUiEvent: (TagDetailEvent.UI) -> Unit,
    onActionEvent: (TagDetailEvent.Action) -> Unit,
) {
    BgmTabHorizontalPager(
        modifier = Modifier.fillMaxSize(),
        tabs = subjectTypeTabs,
        initialPage = subjectTypeTabs.indexOfFirst { it.type == state.type }.coerceAtLeast(0),
    ) {
        TagPageRoute(
            param = remember(state.type, tagKeyword) {
                val subjectType = subjectTypeTabs[it].type
                ListTagParam(
                    type = if (tagKeyword.isBlank()) ListTagType.BROWSER else ListTagType.SEARCH,
                    subjectType = subjectType,
                    search = TagSearchBody(keyword = tagKeyword, subjectType = subjectType)
                )
            },
            onNavScreen = { screen ->
                onUiEvent(TagDetailEvent.UI.OnNavScreen(screen))
            }
        )
    }
}

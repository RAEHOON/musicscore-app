package com.scorebook.feature.pageeditor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.scorebook.core.data.ScoreRepository
import com.scorebook.core.model.PageCrop
import com.scorebook.core.model.Score
import com.scorebook.core.model.ScorePage
import com.scorebook.core.mvi.MviViewModel
import com.scorebook.core.mvi.UiEffect
import com.scorebook.core.mvi.UiIntent
import com.scorebook.core.mvi.UiState
import com.scorebook.core.render.PageRenderer
import com.scorebook.navigation.PageEditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PageEditorState(
    val score: Score? = null,
    val pages: List<ScorePage> = emptyList(),
    val selection: Set<Long> = emptySet(),
    val busy: Boolean = false,
    val dragging: Boolean = false,
    val cropTarget: ScorePage? = null,
    val confirmDelete: Boolean = false,
    val splitDialog: Boolean = false,
) : UiState

sealed interface PageEditorIntent : UiIntent {
    data class Toggle(val pageId: Long) : PageEditorIntent
    data object SelectAll : PageEditorIntent
    data object ClearSelection : PageEditorIntent
    data class Move(val from: Int, val to: Int) : PageEditorIntent
    data object CommitOrder : PageEditorIntent
    data class Rotate(val delta: Int) : PageEditorIntent
    data object RequestDelete : PageEditorIntent
    data object ConfirmDelete : PageEditorIntent
    data object InsertStaff : PageEditorIntent
    data class OpenCrop(val page: ScorePage?) : PageEditorIntent
    data class ApplyCrop(val crop: PageCrop?, val toSelection: Boolean) : PageEditorIntent
    data object RequestSplit : PageEditorIntent
    data class ConfirmSplit(val title: String) : PageEditorIntent
    data object DismissDialog : PageEditorIntent
}

sealed interface PageEditorEffect : UiEffect {
    data class Message(val text: String) : PageEditorEffect
    data class OpenScore(val scoreId: Long) : PageEditorEffect
}

@HiltViewModel
class PageEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scores: ScoreRepository,
    val renderer: PageRenderer,
) : MviViewModel<PageEditorState, PageEditorIntent, PageEditorEffect>(PageEditorState()) {

    private val scoreId = savedStateHandle.toRoute<PageEditorRoute>().scoreId

    init {
        viewModelScope.launch { scores.observeScore(scoreId).collect { setState { copy(score = it) } } }
        viewModelScope.launch {
            scores.observePages(scoreId).collect { list ->
                if (!currentState.dragging) {
                    setState { copy(pages = list, selection = selection.filterTo(HashSet()) { id -> list.any { it.id == id } }) }
                }
            }
        }
    }

    override fun handleIntent(intent: PageEditorIntent) {
        when (intent) {
            is PageEditorIntent.Toggle -> setState {
                copy(selection = if (intent.pageId in selection) selection - intent.pageId else selection + intent.pageId)
            }
            PageEditorIntent.SelectAll -> setState { copy(selection = pages.mapTo(HashSet()) { it.id }) }
            PageEditorIntent.ClearSelection -> setState { copy(selection = emptySet()) }
            is PageEditorIntent.Move -> setState {
                val list = pages.toMutableList()
                if (intent.from in list.indices && intent.to in list.indices) list.add(intent.to, list.removeAt(intent.from))
                copy(pages = list, dragging = true)
            }
            PageEditorIntent.CommitOrder -> {
                val order = currentState.pages.map { it.id }
                setState { copy(dragging = false) }
                launchBusy { scores.reorderPages(scoreId, order) }
            }
            is PageEditorIntent.Rotate -> {
                val ids = targetIds() ?: return
                launchBusy { scores.rotatePages(scoreId, ids, intent.delta) }
            }
            PageEditorIntent.RequestDelete -> {
                if (currentState.selection.isEmpty()) message("삭제할 페이지를 선택하세요")
                else if (currentState.selection.size >= currentState.pages.size) message("모든 페이지를 지울 수는 없습니다")
                else setState { copy(confirmDelete = true) }
            }
            PageEditorIntent.ConfirmDelete -> {
                val ids = currentState.selection
                setState { copy(confirmDelete = false, selection = emptySet()) }
                launchBusy { scores.deletePages(scoreId, ids) }
            }
            PageEditorIntent.InsertStaff -> {
                val s = currentState
                val after = s.pages.indexOfLast { it.id in s.selection }.takeIf { it >= 0 } ?: (s.pages.size - 1)
                launchBusy {
                    scores.insertStaffPage(scoreId, after)
                    message("${after + 2}쪽에 빈 오선지를 넣었습니다")
                }
            }
            is PageEditorIntent.OpenCrop -> {
                val page = intent.page ?: currentState.pages.firstOrNull { it.id in currentState.selection }
                if (page == null) message("자를 페이지를 선택하세요") else setState { copy(cropTarget = page) }
            }
            is PageEditorIntent.ApplyCrop -> {
                val target = currentState.cropTarget ?: return
                val ids = if (intent.toSelection && currentState.selection.isNotEmpty()) currentState.selection else setOf(target.id)
                setState { copy(cropTarget = null) }
                launchBusy { scores.setCrop(scoreId, ids, intent.crop) }
            }
            PageEditorIntent.RequestSplit -> {
                if (currentState.selection.isEmpty()) message("새 악보로 만들 페이지를 선택하세요")
                else setState { copy(splitDialog = true) }
            }
            is PageEditorIntent.ConfirmSplit -> {
                val ids = currentState.selection
                setState { copy(splitDialog = false, selection = emptySet()) }
                launchBusy {
                    val id = scores.splitScore(scoreId, ids, intent.title)
                    message("'${intent.title}' 악보를 만들었습니다")
                    sendEffect(PageEditorEffect.OpenScore(id))
                }
            }
            PageEditorIntent.DismissDialog -> setState { copy(cropTarget = null, confirmDelete = false, splitDialog = false) }
        }
    }

    private fun targetIds(): Set<Long>? {
        val sel = currentState.selection
        if (sel.isEmpty()) {
            message("페이지를 먼저 선택하세요")
            return null
        }
        return sel
    }

    private fun message(text: String) = sendEffect(PageEditorEffect.Message(text))

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            setState { copy(busy = true) }
            try {
                block()
            } finally {
                setState { copy(busy = false) }
            }
        }
    }
}

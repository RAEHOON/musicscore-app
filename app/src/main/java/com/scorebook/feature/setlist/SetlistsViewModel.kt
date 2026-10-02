package com.scorebook.feature.setlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.scorebook.core.data.AnnotationRepository
import com.scorebook.core.data.ScoreRepository
import com.scorebook.core.data.SetlistRepository
import com.scorebook.core.model.Bookmark
import com.scorebook.core.model.Score
import com.scorebook.core.model.Setlist
import com.scorebook.core.model.SetlistEntry
import com.scorebook.core.mvi.MviViewModel
import com.scorebook.core.mvi.UiEffect
import com.scorebook.core.mvi.UiIntent
import com.scorebook.core.mvi.UiState
import com.scorebook.navigation.SetlistDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

// ------------------------------------------------------------------ 셋리스트 목록

data class SetlistsState(
    val setlists: List<Setlist> = emptyList(),
    val loading: Boolean = true,
    val creating: Boolean = false,
    val renaming: Setlist? = null,
    val deleting: Setlist? = null,
) : UiState

sealed interface SetlistsIntent : UiIntent {
    data class ShowCreate(val show: Boolean) : SetlistsIntent
    data class Create(val name: String) : SetlistsIntent
    data class RequestRename(val setlist: Setlist?) : SetlistsIntent
    data class Rename(val name: String) : SetlistsIntent
    data class RequestDelete(val setlist: Setlist?) : SetlistsIntent
    data object ConfirmDelete : SetlistsIntent
}

sealed interface SetlistsEffect : UiEffect {
    data class Open(val id: Long) : SetlistsEffect
}

@HiltViewModel
class SetlistsViewModel @Inject constructor(
    private val repo: SetlistRepository,
) : MviViewModel<SetlistsState, SetlistsIntent, SetlistsEffect>(SetlistsState()) {

    init {
        viewModelScope.launch { repo.observeSetlists().collect { setState { copy(setlists = it, loading = false) } } }
    }

    override fun handleIntent(intent: SetlistsIntent) {
        when (intent) {
            is SetlistsIntent.ShowCreate -> setState { copy(creating = intent.show) }
            is SetlistsIntent.Create -> viewModelScope.launch {
                setState { copy(creating = false) }
                sendEffect(SetlistsEffect.Open(repo.create(intent.name)))
            }
            is SetlistsIntent.RequestRename -> setState { copy(renaming = intent.setlist) }
            is SetlistsIntent.Rename -> viewModelScope.launch {
                currentState.renaming?.let { repo.rename(it.id, intent.name) }
                setState { copy(renaming = null) }
            }
            is SetlistsIntent.RequestDelete -> setState { copy(deleting = intent.setlist) }
            SetlistsIntent.ConfirmDelete -> viewModelScope.launch {
                currentState.deleting?.let { repo.delete(it.id) }
                setState { copy(deleting = null) }
            }
        }
    }
}

// ------------------------------------------------------------------ 셋리스트 상세

data class SetlistDetailState(
    val name: String = "",
    val entries: List<SetlistEntry> = emptyList(),
    val allScores: List<Score> = emptyList(),
    val bookmarks: Map<Long, List<Bookmark>> = emptyMap(),
    val pickerOpen: Boolean = false,
    val renameOpen: Boolean = false,
    val dragging: Boolean = false,
) : UiState

sealed interface SetlistDetailIntent : UiIntent {
    data class OpenPicker(val open: Boolean) : SetlistDetailIntent
    data class AddScores(val ids: List<Long>) : SetlistDetailIntent
    data class Remove(val entry: SetlistEntry) : SetlistDetailIntent
    data class Move(val from: Int, val to: Int) : SetlistDetailIntent
    data object CommitOrder : SetlistDetailIntent
    data class SetStart(val entry: SetlistEntry, val pageId: Long?) : SetlistDetailIntent
    data class LoadBookmarks(val scoreId: Long) : SetlistDetailIntent
    data class ShowRename(val show: Boolean) : SetlistDetailIntent
    data class Rename(val name: String) : SetlistDetailIntent
    data class Play(val index: Int) : SetlistDetailIntent
}

sealed interface SetlistDetailEffect : UiEffect {
    data class Play(val setlistId: Long, val index: Int, val scoreId: Long) : SetlistDetailEffect
}

@HiltViewModel
class SetlistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: SetlistRepository,
    private val scores: ScoreRepository,
    private val annotations: AnnotationRepository,
) : MviViewModel<SetlistDetailState, SetlistDetailIntent, SetlistDetailEffect>(SetlistDetailState()) {

    private val setlistId = savedStateHandle.toRoute<SetlistDetailRoute>().setlistId

    init {
        viewModelScope.launch { repo.observeName(setlistId).collect { setState { copy(name = it.orEmpty()) } } }
        viewModelScope.launch {
            repo.observeEntries(setlistId).collect { list ->
                if (!currentState.dragging) setState { copy(entries = list) }
                list.map { it.score.id }.distinct().forEach { loadBookmarks(it) }
            }
        }
        viewModelScope.launch { scores.observeScores().collect { setState { copy(allScores = it.sortedBy { s -> s.title }) } } }
    }

    override fun handleIntent(intent: SetlistDetailIntent) {
        when (intent) {
            is SetlistDetailIntent.OpenPicker -> setState { copy(pickerOpen = intent.open) }
            is SetlistDetailIntent.AddScores -> viewModelScope.launch {
                setState { copy(pickerOpen = false) }
                if (intent.ids.isNotEmpty()) repo.addScores(setlistId, intent.ids)
            }
            is SetlistDetailIntent.Remove -> viewModelScope.launch { repo.removeItem(setlistId, intent.entry.id) }
            is SetlistDetailIntent.Move -> setState {
                val list = entries.toMutableList()
                if (intent.from in list.indices && intent.to in list.indices) list.add(intent.to, list.removeAt(intent.from))
                copy(entries = list, dragging = true)
            }
            SetlistDetailIntent.CommitOrder -> viewModelScope.launch {
                val order = currentState.entries.map { it.id }
                setState { copy(dragging = false) }
                repo.reorder(setlistId, order)
            }
            is SetlistDetailIntent.SetStart -> viewModelScope.launch { repo.setStartPage(setlistId, intent.entry.id, intent.pageId) }
            is SetlistDetailIntent.LoadBookmarks -> loadBookmarks(intent.scoreId)
            is SetlistDetailIntent.ShowRename -> setState { copy(renameOpen = intent.show) }
            is SetlistDetailIntent.Rename -> viewModelScope.launch {
                repo.rename(setlistId, intent.name)
                setState { copy(renameOpen = false) }
            }
            is SetlistDetailIntent.Play -> currentState.entries.getOrNull(intent.index)?.let {
                sendEffect(SetlistDetailEffect.Play(setlistId, intent.index, it.score.id))
            }
        }
    }

    private fun loadBookmarks(scoreId: Long) {
        if (currentState.bookmarks.containsKey(scoreId)) return
        viewModelScope.launch {
            val list = annotations.getBookmarks(scoreId)
            setState { copy(bookmarks = bookmarks + (scoreId to list)) }
        }
    }
}

package com.scorebook.feature.library

import android.net.Uri
import com.scorebook.core.model.LibrarySort
import com.scorebook.core.model.Score
import com.scorebook.core.model.Setlist
import com.scorebook.core.mvi.UiEffect
import com.scorebook.core.mvi.UiIntent
import com.scorebook.core.mvi.UiState

data class LibraryState(
    val loading: Boolean = true,
    val scores: List<Score> = emptyList(),
    val totalCount: Int = 0,
    val allTags: List<String> = emptyList(),
    val query: String = "",
    val sort: LibrarySort = LibrarySort.RECENT,
    val selectedTag: String? = null,
    val favoritesOnly: Boolean = false,
    val selection: Set<Long> = emptySet(),
    val importing: Boolean = false,
    val editing: Score? = null,
    val pendingDelete: Set<Long> = emptySet(),
    val mergeDialogOpen: Boolean = false,
    val setlistPickerOpen: Boolean = false,
    val setlists: List<Setlist> = emptyList(),
) : UiState {
    val selecting: Boolean get() = selection.isNotEmpty()
}

sealed interface LibraryIntent : UiIntent {
    data class QueryChanged(val query: String) : LibraryIntent
    data class SortChanged(val sort: LibrarySort) : LibraryIntent
    data class TagSelected(val tag: String?) : LibraryIntent
    data object ToggleFavoritesOnly : LibraryIntent

    data class ScoreClicked(val id: Long) : LibraryIntent
    data class ScoreLongPressed(val id: Long) : LibraryIntent
    data object ClearSelection : LibraryIntent
    data object SelectAll : LibraryIntent

    data class ImportPdfs(val uris: List<Uri>) : LibraryIntent
    data class ToggleFavorite(val id: Long) : LibraryIntent

    data class EditInfo(val score: Score) : LibraryIntent
    data class SaveInfo(
        val id: Long,
        val title: String,
        val composer: String,
        val genre: String,
        val key: String,
        val tags: List<String>,
    ) : LibraryIntent

    data class RequestDelete(val ids: Set<Long>) : LibraryIntent
    data object ConfirmDelete : LibraryIntent

    data object RequestMerge : LibraryIntent
    data class ConfirmMerge(val title: String) : LibraryIntent

    data class RequestAddToSetlist(val ids: Set<Long>) : LibraryIntent
    data class AddToSetlist(val setlistId: Long?, val newName: String?) : LibraryIntent

    data class OpenPageEditor(val id: Long) : LibraryIntent
    data object DismissDialog : LibraryIntent
}

sealed interface LibraryEffect : UiEffect {
    data class OpenViewer(val scoreId: Long) : LibraryEffect
    data class OpenPageEditor(val scoreId: Long) : LibraryEffect
    data class Message(val text: String) : LibraryEffect
}

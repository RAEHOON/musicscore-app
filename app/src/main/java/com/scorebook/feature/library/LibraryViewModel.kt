package com.scorebook.feature.library

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.scorebook.core.data.ScoreRepository
import com.scorebook.core.data.SetlistRepository
import com.scorebook.core.data.SettingsRepository
import com.scorebook.core.importer.ImportResult
import com.scorebook.core.importer.ScoreImporter
import com.scorebook.core.input.ExternalIntentBus
import com.scorebook.core.model.LibrarySort
import com.scorebook.core.model.Score
import com.scorebook.core.mvi.MviViewModel
import com.scorebook.core.util.KoreanSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val scores: ScoreRepository,
    private val setlists: SetlistRepository,
    private val importer: ScoreImporter,
    private val settings: SettingsRepository,
    private val externalIntents: ExternalIntentBus,
) : MviViewModel<LibraryState, LibraryIntent, LibraryEffect>(LibraryState()) {

    private data class Filters(val query: String = "", val tag: String? = null, val favoritesOnly: Boolean = false)

    private val filters = MutableStateFlow(Filters())
    private val collator = Collator.getInstance(Locale.KOREAN)

    init {
        viewModelScope.launch {
            combine(
                scores.observeScores(),
                filters,
                settings.settings.map { it.librarySort }.distinctUntilChanged(),
            ) { all, f, sort -> Triple(all, f, sort) }
                .collect { (all, f, sort) ->
                    val tags = all.flatMap { it.tags }.distinct().sortedWith(collator)
                    val visible = all
                        .filter { s -> f.tag == null || f.tag in s.tags }
                        .filter { s -> !f.favoritesOnly || s.favorite }
                        .filter { s -> matches(s, f.query) }
                        .let { sortScores(it, sort) }
                    setState {
                        copy(
                            loading = false,
                            scores = visible,
                            totalCount = all.size,
                            allTags = tags,
                            sort = sort,
                            query = f.query,
                            selectedTag = f.tag?.takeIf { it in tags },
                            favoritesOnly = f.favoritesOnly,
                            selection = selection.filterTo(mutableSetOf()) { id -> all.any { it.id == id } },
                        )
                    }
                }
        }
        viewModelScope.launch {
            setlists.observeSetlists().collect { list -> setState { copy(setlists = list) } }
        }
        viewModelScope.launch {
            externalIntents.pdfs.filter { it.isNotEmpty() }.collect {
                val open = externalIntents.openAfterImport
                importPdfs(externalIntents.takePdfs(), openAfter = open)
            }
        }
    }

    override fun handleIntent(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.QueryChanged -> filters.update { it.copy(query = intent.query) }
            is LibraryIntent.SortChanged -> viewModelScope.launch { settings.update { it.copy(librarySort = intent.sort) } }
            is LibraryIntent.TagSelected -> filters.update { it.copy(tag = intent.tag) }
            LibraryIntent.ToggleFavoritesOnly -> filters.update { it.copy(favoritesOnly = !it.favoritesOnly) }

            is LibraryIntent.ScoreClicked -> {
                if (currentState.selecting) toggleSelection(intent.id)
                else sendEffect(LibraryEffect.OpenViewer(intent.id))
            }
            is LibraryIntent.ScoreLongPressed -> toggleSelection(intent.id)
            LibraryIntent.ClearSelection -> setState { copy(selection = emptySet()) }
            LibraryIntent.SelectAll -> setState { copy(selection = scores.map { it.id }.toSet()) }

            is LibraryIntent.ImportPdfs -> importPdfs(intent.uris, openAfter = false)
            is LibraryIntent.ToggleFavorite -> viewModelScope.launch {
                val score = currentState.scores.firstOrNull { it.id == intent.id } ?: return@launch
                scores.setFavorite(score.id, !score.favorite)
            }

            is LibraryIntent.EditInfo -> setState { copy(editing = intent.score) }
            is LibraryIntent.SaveInfo -> viewModelScope.launch {
                scores.updateInfo(intent.id, intent.title, intent.composer, intent.genre, intent.key, intent.tags)
                setState { copy(editing = null) }
            }

            is LibraryIntent.RequestDelete -> setState { copy(pendingDelete = intent.ids) }
            LibraryIntent.ConfirmDelete -> viewModelScope.launch {
                val ids = currentState.pendingDelete
                setState { copy(pendingDelete = emptySet(), selection = emptySet()) }
                scores.deleteScores(ids)
                sendEffect(LibraryEffect.Message("${ids.size}개의 악보를 삭제했습니다"))
            }

            LibraryIntent.RequestMerge -> {
                if (currentState.selection.size < 2) {
                    sendEffect(LibraryEffect.Message("합칠 악보를 2개 이상 선택하세요"))
                } else {
                    setState { copy(mergeDialogOpen = true) }
                }
            }
            is LibraryIntent.ConfirmMerge -> viewModelScope.launch {
                // 선택 순서가 아닌 현재 화면 정렬 순서대로 합친다
                val ordered = currentState.scores.map { it.id }.filter { it in currentState.selection }
                setState { copy(mergeDialogOpen = false, selection = emptySet(), importing = true) }
                val id = scores.mergeScores(ordered, intent.title)
                setState { copy(importing = false) }
                sendEffect(LibraryEffect.Message("'${intent.title}' 악보를 만들었습니다"))
                sendEffect(LibraryEffect.OpenViewer(id))
            }

            is LibraryIntent.RequestAddToSetlist -> setState { copy(setlistPickerOpen = true, selection = intent.ids) }
            is LibraryIntent.AddToSetlist -> viewModelScope.launch {
                val ids = currentState.scores.map { it.id }.filter { it in currentState.selection }
                val target = intent.setlistId ?: setlists.create(intent.newName ?: "새 셋리스트")
                setlists.addScores(target, ids)
                setState { copy(setlistPickerOpen = false, selection = emptySet()) }
                sendEffect(LibraryEffect.Message("셋리스트에 ${ids.size}곡을 추가했습니다"))
            }

            is LibraryIntent.OpenPageEditor -> sendEffect(LibraryEffect.OpenPageEditor(intent.id))
            LibraryIntent.DismissDialog -> setState {
                copy(editing = null, pendingDelete = emptySet(), mergeDialogOpen = false, setlistPickerOpen = false)
            }
        }
    }

    private fun toggleSelection(id: Long) = setState {
        copy(selection = if (id in selection) selection - id else selection + id)
    }

    private fun importPdfs(uris: List<Uri>, openAfter: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            setState { copy(importing = true) }
            var imported = 0
            var lastId: Long? = null
            val problems = mutableListOf<String>()
            for (uri in uris) {
                when (val result = importer.importPdf(uri)) {
                    is ImportResult.Success -> { imported++; lastId = result.scoreId }
                    is ImportResult.Duplicate -> { problems += "이미 있는 악보: ${result.title}"; lastId = result.scoreId }
                    is ImportResult.Failure -> problems += result.message
                }
            }
            setState { copy(importing = false) }
            if (imported > 0) sendEffect(LibraryEffect.Message("악보 ${imported}개를 가져왔습니다"))
            problems.take(3).forEach { sendEffect(LibraryEffect.Message(it)) }
            if (openAfter && uris.size == 1) lastId?.let { sendEffect(LibraryEffect.OpenViewer(it)) }
        }
    }

    private fun matches(score: Score, query: String): Boolean {
        if (query.isBlank()) return true
        return KoreanSearch.matches(score.title, query) ||
            KoreanSearch.matches(score.composer, query) ||
            KoreanSearch.matches(score.genre, query) ||
            KoreanSearch.matches(score.musicalKey, query) ||
            score.tags.any { KoreanSearch.matches(it, query) }
    }

    private fun sortScores(list: List<Score>, sort: LibrarySort): List<Score> = when (sort) {
        LibrarySort.RECENT -> list.sortedByDescending { maxOf(it.lastOpenedAt, it.createdAt) }
        LibrarySort.TITLE -> list.sortedWith { a, b -> collator.compare(a.title, b.title) }
        LibrarySort.COMPOSER -> list.sortedWith { a, b ->
            val c = collator.compare(a.composer.ifBlank { "￿" }, b.composer.ifBlank { "￿" })
            if (c != 0) c else collator.compare(a.title, b.title)
        }
        LibrarySort.ADDED -> list.sortedByDescending { it.createdAt }
    }
}

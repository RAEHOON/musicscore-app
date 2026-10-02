package com.scorebook.feature.viewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.compose.ui.graphics.asImageBitmap
import com.scorebook.core.analysis.SongSplitter
import com.scorebook.core.data.AnnotationRepository
import com.scorebook.core.data.PracticeRepository
import com.scorebook.core.data.ScoreRepository
import com.scorebook.core.data.SetlistRepository
import com.scorebook.core.data.SettingsRepository
import com.scorebook.core.di.ApplicationScope
import com.scorebook.core.ink.InkBrushes
import com.scorebook.core.input.InputHub
import com.scorebook.core.ink.ItemRenderer
import com.scorebook.core.ink.StrokeGeometry
import com.scorebook.core.model.AnnotationItem
import com.scorebook.core.model.BrushKind
import com.scorebook.core.model.EraserMode
import com.scorebook.core.model.InkStroke
import com.scorebook.core.model.ItemKind
import com.scorebook.core.model.ViewMode
import com.scorebook.core.model.WorldRect
import com.scorebook.core.mvi.MviViewModel
import com.scorebook.core.pdf.PdfExporter
import com.scorebook.core.render.PageRenderer
import com.scorebook.navigation.ViewerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max

@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scores: ScoreRepository,
    private val annotations: AnnotationRepository,
    private val setlists: SetlistRepository,
    private val practice: PracticeRepository,
    private val settingsRepo: SettingsRepository,
    private val exporter: PdfExporter,
    val renderer: PageRenderer,
    private val songSplitter: SongSplitter,
    val inputHub: InputHub,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : MviViewModel<ViewerState, ViewerIntent, ViewerEffect>(ViewerState()) {

    private val route = savedStateHandle.toRoute<ViewerRoute>()
    val scoreId: Long = route.scoreId

    /** 한 번의 편집(되돌리기 단위). 이동은 (제거 = 이동 전, 추가 = 이동 후) 로 표현 */
    private data class Edit(
        val removedStrokes: List<InkStroke> = emptyList(),
        val addedStrokes: List<InkStroke> = emptyList(),
        val removedItems: List<AnnotationItem> = emptyList(),
        val addedItems: List<AnnotationItem> = emptyList(),
    ) {
        fun isEmpty() = removedStrokes.isEmpty() && addedStrokes.isEmpty() && removedItems.isEmpty() && addedItems.isEmpty()
    }

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    /** DB 쓰기는 순서를 보장하고, 화면을 닫아도 끝까지 저장되도록 앱 스코프에서 직렬 처리 */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /**
     * 지우개 한 번(펜을 대고 뗄 때까지)의 변경을 모아 되돌리기 1단위로 만든다.
     * 영역 지우개는 획을 조각내므로, 이번 제스처에서 만든 조각이 다시 지워지는 경우까지 추적한다.
     */
    private val eraseRemovedStrokes = LinkedHashMap<String, InkStroke>()
    private val eraseAddedStrokes = LinkedHashMap<String, InkStroke>()
    private val eraseRemovedItems = LinkedHashMap<String, AnnotationItem>()

    private var saveLastPageJob: Job? = null
    private var autoTurnJob: Job? = null
    private var songSplitJob: Job? = null
    private var sessionStart: Long? = null
    private var pendingExport: ExportOptions = ExportOptions()

    init {
        appScope.launch {
            for (write in writes) runCatching { write() }
        }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val settings = settingsRepo.current()
        val score = scores.getScore(scoreId)
        if (score == null) {
            setState { copy(loading = false, error = "악보를 찾을 수 없습니다") }
            return
        }
        var layers = annotations.getLayers(scoreId)
        if (layers.isEmpty()) {
            annotations.addLayer(scoreId, ScoreRepository.DEFAULT_LAYER)
            layers = annotations.getLayers(scoreId)
        }
        val pages = scores.getPages(scoreId)
        val strokes = annotations.loadStrokes(scoreId).groupBy { it.pageId }
        val items = annotations.loadItems(scoreId).groupBy { it.pageId }
        val setlist = if (route.setlistId >= 0) {
            SetlistContext(route.setlistId, route.setlistIndex, setlists.getEntries(route.setlistId))
        } else {
            null
        }
        val entry = setlist?.entries?.getOrNull(route.setlistIndex)
        val lastIndex = max(0, pages.size - 1)
        val start = entry?.startPageId?.let { id -> pages.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            ?: if (settings.rememberLastPage && setlist == null) score.lastPageIndex.coerceIn(0, lastIndex) else 0

        setState {
            copy(
                loading = false,
                score = score,
                pages = pages,
                layers = layers,
                activeLayerId = layers.first().id,
                strokes = strokes,
                items = items,
                settings = settings,
                currentPage = start,
                setlist = setlist,
            )
        }
        scores.setLastPage(scoreId, start)
        if (sessionStart == null) sessionStart = System.currentTimeMillis()

        viewModelScope.launch {
            scores.observePages(scoreId).collect { list ->
                setState { copy(pages = list, currentPage = currentPage.coerceIn(0, max(0, list.size - 1))) }
            }
        }
        viewModelScope.launch {
            scores.observeScore(scoreId).collect { s -> if (s != null) setState { copy(score = s) } }
        }
        viewModelScope.launch {
            annotations.observeLayers(scoreId).collect { list ->
                setState {
                    copy(
                        layers = list,
                        activeLayerId = if (list.any { it.id == activeLayerId }) activeLayerId else list.firstOrNull()?.id ?: -1,
                    )
                }
            }
        }
        viewModelScope.launch { annotations.observeLinks(scoreId).collect { setState { copy(links = it) } } }
        viewModelScope.launch { annotations.observeBookmarks(scoreId).collect { setState { copy(bookmarks = it) } } }
        viewModelScope.launch { settingsRepo.settings.collect { setState { copy(settings = it) } } }
    }

    override fun handleIntent(intent: ViewerIntent) {
        when (intent) {
            ViewerIntent.NextPage -> step(forward = true, fromTurnGesture = true)
            ViewerIntent.PreviousPage -> step(forward = false, fromTurnGesture = true)
            is ViewerIntent.GoToPage -> goTo(intent.index)
            is ViewerIntent.PageSettled -> if (intent.index != currentState.currentPage) goTo(intent.index)
            is ViewerIntent.Tap -> onTap(intent)
            is ViewerIntent.OrientationChanged -> setState { copy(landscape = intent.landscape) }

            ViewerIntent.ToggleChrome -> if (!currentState.performanceMode) setState { copy(chromeVisible = !chromeVisible) }
            is ViewerIntent.SetViewMode -> {
                setState { copy(halfStep = false) }
                updateSettings { it.copy(viewMode = intent.mode) }
            }
            is ViewerIntent.SetColorMode -> updateSettings { it.copy(colorMode = intent.mode) }
            ViewerIntent.ToggleAutoCrop -> updateSettings { it.copy(autoCropMargins = !it.autoCropMargins) }
            ViewerIntent.TogglePerformanceMode -> setState {
                val on = !performanceMode
                copy(performanceMode = on, annotating = false, chromeVisible = !on, panel = null, selection = null, linkMode = false)
            }
            is ViewerIntent.OpenPanel -> setState { copy(panel = intent.panel) }
            ViewerIntent.ToggleAutoTurn -> toggleAutoTurn()
            ViewerIntent.ToggleFaceGesture -> setState { copy(faceGestureActive = !faceGestureActive) }

            ViewerIntent.StylusDetected -> if (!currentState.annotating && !currentState.performanceMode) {
                setState { copy(annotating = true) }
            }
            ViewerIntent.ToggleAnnotating -> setState {
                copy(annotating = !annotating, selection = if (annotating) null else selection, fingerDrawing = if (annotating) false else fingerDrawing)
            }
            is ViewerIntent.SelectTool -> setState {
                copy(
                    toolSettings = toolSettings.copy(tool = intent.tool),
                    selection = if (intent.tool == AnnotationTool.LASSO) selection else null,
                    linkMode = false,
                )
            }
            is ViewerIntent.UpdateToolSettings -> setState { copy(toolSettings = intent.settings) }
            is ViewerIntent.SetEraserMode -> updateSettings { it.copy(eraserMode = intent.mode) }
            is ViewerIntent.SetEraserSize -> updateSettings { it.copy(eraserSize = intent.size.coerceIn(MIN_ERASER, MAX_ERASER)) }
            ViewerIntent.ToggleFingerDrawing -> setState { copy(fingerDrawing = !fingerDrawing) }
            is ViewerIntent.StrokesAdded -> commit(Edit(addedStrokes = intent.strokes))
            is ViewerIntent.EraseSegment -> erase(intent)
            ViewerIntent.EraseEnded -> {
                if (eraseRemovedStrokes.isNotEmpty() || eraseAddedStrokes.isNotEmpty() || eraseRemovedItems.isNotEmpty()) {
                    commit(
                        Edit(
                            removedStrokes = eraseRemovedStrokes.values.toList(),
                            addedStrokes = eraseAddedStrokes.values.toList(),
                            removedItems = eraseRemovedItems.values.toList(),
                        ),
                        alreadyApplied = true,
                    )
                }
                eraseRemovedStrokes.clear()
                eraseAddedStrokes.clear()
                eraseRemovedItems.clear()
            }
            is ViewerIntent.LassoSelect -> lasso(intent.pageId, intent.polygon)
            is ViewerIntent.MoveSelection -> moveSelection(intent.dx, intent.dy)
            ViewerIntent.DeleteSelection -> deleteSelection()
            ViewerIntent.ClearSelection -> setState { copy(selection = null) }
            is ViewerIntent.AddShape -> addShape(intent)
            is ViewerIntent.PlaceStamp -> placeStamp(intent)
            is ViewerIntent.TextTap -> textTap(intent)
            is ViewerIntent.ConfirmText -> confirmText(intent)
            ViewerIntent.Undo -> undo()
            ViewerIntent.Redo -> redo()
            is ViewerIntent.OpenStampPicker -> setState { copy(stampPickerOpen = intent.open) }

            is ViewerIntent.OpenLayers -> setState { copy(layersDialogOpen = intent.open) }
            is ViewerIntent.SetActiveLayer -> setState { copy(activeLayerId = intent.id) }
            is ViewerIntent.ToggleLayerVisible -> viewModelScope.launch {
                currentState.layers.firstOrNull { it.id == intent.id }?.let { annotations.updateLayer(it.copy(visible = !it.visible)) }
            }
            is ViewerIntent.AddLayer -> viewModelScope.launch {
                val id = annotations.addLayer(scoreId, intent.name)
                setState { copy(activeLayerId = id) }
            }
            is ViewerIntent.RenameLayer -> viewModelScope.launch {
                currentState.layers.firstOrNull { it.id == intent.id }?.let { annotations.updateLayer(it.copy(name = intent.name)) }
            }
            is ViewerIntent.DeleteLayer -> deleteLayer(intent.id)

            ViewerIntent.ToggleLinkMode -> setState {
                copy(linkMode = !linkMode, showLinks = true, annotating = if (!linkMode) false else annotating, selection = null)
            }
            ViewerIntent.ToggleShowLinks -> setState { copy(showLinks = !showLinks) }
            is ViewerIntent.LinkRectDrawn -> linkRectDrawn(intent.pageId, intent.rect)
            is ViewerIntent.CreateLink -> createLink(intent.targetPageIndex)
            ViewerIntent.ConfirmDeleteLink -> {
                val link = currentState.linkToDelete ?: return
                setState { copy(linkToDelete = null) }
                viewModelScope.launch { annotations.deleteLink(link.id) }
            }
            ViewerIntent.ReturnFromLink -> currentState.linkReturnPage?.let {
                goTo(it)
                setState { copy(linkReturnPage = null) }
            }
            ViewerIntent.DismissLinkReturn -> setState { copy(linkReturnPage = null) }
            is ViewerIntent.OpenLinkEditor -> {
                val link = currentState.links.firstOrNull { it.id == intent.linkId } ?: return
                val target = currentState.pages.indexOfFirst { it.id == link.toPageId }.coerceAtLeast(0)
                setState { copy(linkEditor = LinkEditor(link.id, link.label, target)) }
            }
            is ViewerIntent.SaveLinkEdit -> {
                val editor = currentState.linkEditor ?: return
                val target = currentState.pages.getOrNull(intent.targetIndex) ?: return
                setState { copy(linkEditor = null) }
                viewModelScope.launch { annotations.updateLink(editor.linkId, intent.label, target.id) }
            }
            is ViewerIntent.RequestDeleteLink -> currentState.links.firstOrNull { it.id == intent.linkId }?.let {
                setState { copy(linkToDelete = it, linkEditor = null) }
            }
            ViewerIntent.StartSongSplit -> startSongSplit()
            ViewerIntent.CancelSongSplit -> {
                songSplitJob?.cancel()
                setState { copy(songSplit = null) }
            }
            is ViewerIntent.EditSongCandidate -> setState {
                val split = songSplit ?: return@setState this
                copy(
                    songSplit = split.copy(
                        candidates = split.candidates.mapIndexed { i, c ->
                            if (i != intent.index) c else c.copy(name = intent.name ?: c.name, include = intent.include ?: c.include)
                        }
                    )
                )
            }
            ViewerIntent.ApplySongSplit -> applySongSplit()
            is ViewerIntent.OpenSongPicker -> setState { copy(songPickerOpen = intent.open, panel = if (intent.open) null else panel) }
            is ViewerIntent.JumpToSong -> {
                setState { copy(songPickerOpen = false) }
                goTo(intent.pageIndex)
            }
            is ViewerIntent.OpenBookmarkDialog -> setState { copy(bookmarkDialogOpen = intent.open) }
            is ViewerIntent.AddBookmark -> {
                val page = currentState.pages.getOrNull(currentState.currentPage) ?: return
                setState { copy(bookmarkDialogOpen = false) }
                viewModelScope.launch {
                    annotations.addBookmark(scoreId, page.id, intent.title)
                    sendEffect(ViewerEffect.Message("북마크를 추가했습니다"))
                }
            }
            is ViewerIntent.DeleteBookmark -> viewModelScope.launch { annotations.deleteBookmark(intent.id) }
            is ViewerIntent.RenameBookmark -> viewModelScope.launch { annotations.renameBookmark(intent.id, intent.title.trim()) }

            is ViewerIntent.OpenExport -> setState { copy(exportDialogOpen = intent.open) }
            is ViewerIntent.StartExport -> startExport(intent.options)
            is ViewerIntent.ExportToUri -> exportTo(intent.uri)

            ViewerIntent.DismissDialog -> setState {
                copy(
                    textEditor = null, pendingLink = null, linkToDelete = null, panel = null, layersDialogOpen = false,
                    stampPickerOpen = false, exportDialogOpen = false, bookmarkDialogOpen = false,
                    linkEditor = null, songPickerOpen = false,
                )
            }
            ViewerIntent.LifecycleStart -> if (sessionStart == null && !currentState.loading) sessionStart = System.currentTimeMillis()
            ViewerIntent.LifecycleStop -> {
                flushSession()
                saveLastPageNow()
            }
        }
    }

    // ------------------------------------------------------------------ 페이지 이동

    private fun step(forward: Boolean, fromTurnGesture: Boolean) {
        val s = currentState
        val n = s.pages.size
        if (n == 0) return
        if (fromTurnGesture && s.chromeVisible && !s.annotating) setState { copy(chromeVisible = false) }
        when (s.effectiveMode) {
            ViewMode.HALF -> if (forward) {
                when {
                    !s.halfStep && s.currentPage < n - 1 -> setState { copy(halfStep = true) }
                    s.halfStep -> goTo(s.currentPage + 1)
                    else -> atEnd()
                }
            } else {
                when {
                    s.halfStep -> setState { copy(halfStep = false) }
                    s.currentPage > 0 -> {
                        goTo(s.currentPage - 1)
                        setState { copy(halfStep = true) }
                    }
                    else -> atStart()
                }
            }
            ViewMode.TWO_PAGE -> {
                val base = s.currentPage - s.currentPage % 2
                val target = if (forward) base + 2 else base - 2
                when {
                    target in 0 until n -> goTo(target)
                    forward -> atEnd()
                    else -> atStart()
                }
            }
            else -> {
                val target = s.currentPage + if (forward) 1 else -1
                when {
                    target in 0 until n -> goTo(target)
                    forward -> atEnd()
                    else -> atStart()
                }
            }
        }
    }

    private fun atEnd() {
        val ctx = currentState.setlist
        val next = ctx?.next
        if (ctx != null && next != null) {
            sendEffect(ViewerEffect.OpenScore(next.score.id, ctx.setlistId, ctx.index + 1))
        } else {
            sendEffect(ViewerEffect.Message("마지막 페이지입니다"))
            autoTurnJob?.cancel()
            setState { copy(autoTurnActive = false) }
        }
    }

    private fun atStart() {
        val ctx = currentState.setlist
        val prev = ctx?.previous
        if (ctx != null && prev != null) sendEffect(ViewerEffect.OpenScore(prev.score.id, ctx.setlistId, ctx.index - 1))
    }

    private fun goTo(index: Int) {
        val n = currentState.pages.size
        if (n == 0) return
        val target = index.coerceIn(0, n - 1)
        setState { copy(currentPage = target, halfStep = false, selection = null) }
        saveLastPageJob?.cancel()
        saveLastPageJob = viewModelScope.launch {
            delay(600)
            scores.setLastPage(scoreId, target)
        }
    }

    private fun saveLastPageNow() {
        val page = currentState.currentPage
        if (currentState.loading) return
        appScope.launch { scores.setLastPage(scoreId, page) }
    }

    private fun onTap(tap: ViewerIntent.Tap) {
        val s = currentState
        if (s.performanceMode) {
            step(forward = tap.screenFractionX >= 0.5f, fromTurnGesture = false)
            return
        }
        if (tap.pageId != null && s.showLinks && !s.linkMode) {
            val link = s.links.firstOrNull { it.fromPageId == tap.pageId && it.rect.contains(tap.worldX, tap.worldY) }
            if (link != null) {
                val target = s.pages.indexOfFirst { it.id == link.toPageId }
                if (target >= 0) {
                    val from = s.currentPage
                    goTo(target)
                    setState { copy(linkReturnPage = from) }
                    return
                }
            }
        }
        val zone = s.settings.tapZoneFraction
        when {
            s.settings.tapZonesEnabled && tap.screenFractionX < zone -> step(false, fromTurnGesture = true)
            s.settings.tapZonesEnabled && tap.screenFractionX > 1 - zone -> step(true, fromTurnGesture = true)
            else -> setState { copy(chromeVisible = !chromeVisible) }
        }
    }

    private fun toggleAutoTurn() {
        if (currentState.autoTurnActive) {
            autoTurnJob?.cancel()
            setState { copy(autoTurnActive = false) }
            return
        }
        setState { copy(autoTurnActive = true, chromeVisible = false) }
        autoTurnJob?.cancel()
        // 세로 스크롤 모드는 화면이 직접 스크롤하므로 여기서는 페이지 모드만 처리
        if (currentState.effectiveMode == ViewMode.VERTICAL) return
        autoTurnJob = viewModelScope.launch {
            while (isActive) {
                delay(currentState.settings.autoTurnSeconds * 1000L)
                step(forward = true, fromTurnGesture = false)
            }
        }
    }

    // ------------------------------------------------------------------ 필기 편집

    private fun commit(edit: Edit, alreadyApplied: Boolean = false) {
        if (edit.isEmpty()) return
        if (!alreadyApplied) apply(edit, forward = true)
        persist(edit, forward = true)
        undoStack.addLast(edit)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        setState { copy(canUndo = true, canRedo = false) }
    }

    private fun undo() {
        val edit = undoStack.removeLastOrNull() ?: return
        apply(edit, forward = false)
        persist(edit, forward = false)
        redoStack.addLast(edit)
        setState { copy(canUndo = undoStack.isNotEmpty(), canRedo = true, selection = null) }
    }

    private fun redo() {
        val edit = redoStack.removeLastOrNull() ?: return
        apply(edit, forward = true)
        persist(edit, forward = true)
        undoStack.addLast(edit)
        setState { copy(canUndo = true, canRedo = redoStack.isNotEmpty(), selection = null) }
    }

    private fun apply(edit: Edit, forward: Boolean) {
        val removeStrokes = if (forward) edit.removedStrokes else edit.addedStrokes
        val addStrokes = if (forward) edit.addedStrokes else edit.removedStrokes
        val removeItems = if (forward) edit.removedItems else edit.addedItems
        val addItems = if (forward) edit.addedItems else edit.removedItems
        setState {
            val removeStrokeIds = removeStrokes.mapTo(HashSet()) { it.id }
            val newStrokes = if (removeStrokes.isEmpty() && addStrokes.isEmpty()) strokes else {
                val touched = (removeStrokes + addStrokes).mapTo(HashSet()) { it.pageId }
                val m = strokes.toMutableMap()
                touched.forEach { pageId ->
                    m[pageId] = m[pageId].orEmpty().filterNot { it.id in removeStrokeIds } + addStrokes.filter { it.pageId == pageId }
                }
                m
            }
            val removeItemIds = removeItems.mapTo(HashSet()) { it.id }
            val newItems = if (removeItems.isEmpty() && addItems.isEmpty()) items else {
                val touched = (removeItems + addItems).mapTo(HashSet()) { it.pageId }
                val m = items.toMutableMap()
                touched.forEach { pageId ->
                    m[pageId] = m[pageId].orEmpty().filterNot { it.id in removeItemIds } + addItems.filter { it.pageId == pageId }
                }
                m
            }
            copy(strokes = newStrokes, items = newItems)
        }
    }

    private fun persist(edit: Edit, forward: Boolean) {
        val removeStrokes = if (forward) edit.removedStrokes else edit.addedStrokes
        val addStrokes = if (forward) edit.addedStrokes else edit.removedStrokes
        val removeItems = if (forward) edit.removedItems else edit.addedItems
        val addItems = if (forward) edit.addedItems else edit.removedItems
        val addedStrokeIds = addStrokes.mapTo(HashSet()) { it.id }
        val addedItemIds = addItems.mapTo(HashSet()) { it.id }
        writes.trySend {
            annotations.deleteStrokes(removeStrokes.map { it.id }.filterNot { it in addedStrokeIds })
            annotations.saveStrokes(addStrokes)
            annotations.deleteItems(removeItems.map { it.id }.filterNot { it in addedItemIds })
            annotations.saveItems(addItems)
        }
    }

    private fun erase(e: ViewerIntent.EraseSegment) {
        val s = currentState
        val visible = s.visibleLayerIds
        val area = s.settings.eraserMode == EraserMode.AREA
        val half = (if (area) s.settings.eraserSize else STROKE_ERASER_SIZE) / 2f
        val strokeHits = s.strokes[e.pageId].orEmpty().filter {
            it.layerId in visible && StrokeGeometry.hitsSegment(it.stroke, e.x0, e.y0, e.x1, e.y1, half + it.size / 2f)
        }
        // 텍스트·스탬프는 잘라낼 수 없으므로 닿으면 통째로 지운다
        val itemHits = s.items[e.pageId].orEmpty().filter {
            it.layerId in visible && ItemRenderer.bounds(it).let { b ->
                e.x1 in (b.left - half)..(b.right + half) && e.y1 in (b.top - half)..(b.bottom + half)
            }
        }
        if (strokeHits.isEmpty() && itemHits.isEmpty()) return

        val removed = ArrayList<InkStroke>()
        val added = ArrayList<InkStroke>()
        for (hit in strokeHits) {
            if (!area) {
                removed += hit
                continue
            }
            val pieces = StrokeGeometry.eraseArea(hit.stroke, e.x0, e.y0, e.x1, e.y1, half) ?: continue
            removed += hit
            pieces.mapTo(added) { InkStroke(newId(), hit.pageId, hit.layerId, hit.kind, it) }
        }
        if (removed.isEmpty() && itemHits.isEmpty()) return

        for (r in removed) {
            // 이번 제스처에서 생긴 조각이면 기록에서만 빼고, 원래 있던 획이면 "제거됨"으로 기록
            if (eraseAddedStrokes.remove(r.id) == null) eraseRemovedStrokes[r.id] = r
        }
        added.forEach { eraseAddedStrokes[it.id] = it }
        itemHits.forEach { eraseRemovedItems[it.id] = it }
        apply(Edit(removedStrokes = removed, addedStrokes = added, removedItems = itemHits), forward = true)
    }

    private fun lasso(pageId: Long, polygon: List<Pair<Float, Float>>) {
        val s = currentState
        val visible = s.visibleLayerIds
        val strokes = s.strokes[pageId].orEmpty().filter { it.layerId in visible && StrokeGeometry.insideLasso(it.stroke, polygon) }
        val items = s.items[pageId].orEmpty().filter { it.layerId in visible && StrokeGeometry.pointInPolygon(it.x, it.y, polygon) }
        if (strokes.isEmpty() && items.isEmpty()) {
            setState { copy(selection = null) }
            return
        }
        val rects = strokes.mapNotNull { StrokeGeometry.bounds(it.stroke) } + items.map { ItemRenderer.bounds(it) }
        val bounds = WorldRect(rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom })
        setState { copy(selection = Selection(pageId, strokes.mapTo(HashSet()) { it.id }, items.mapTo(HashSet()) { it.id }, bounds)) }
    }

    private fun moveSelection(dx: Float, dy: Float) {
        val s = currentState
        val sel = s.selection ?: return
        val oldStrokes = s.strokes[sel.pageId].orEmpty().filter { it.id in sel.strokeIds }
        val oldItems = s.items[sel.pageId].orEmpty().filter { it.id in sel.itemIds }
        val newStrokes = oldStrokes.map { InkStroke(it.id, it.pageId, it.layerId, it.kind, StrokeGeometry.translate(it.stroke, dx, dy)) }
        val newItems = oldItems.map { it.copy(x = it.x + dx, y = it.y + dy) }
        commit(Edit(removedStrokes = oldStrokes, addedStrokes = newStrokes, removedItems = oldItems, addedItems = newItems))
        val b = sel.bounds
        setState { copy(selection = sel.copy(bounds = WorldRect(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy))) }
    }

    private fun deleteSelection() {
        val s = currentState
        val sel = s.selection ?: return
        commit(
            Edit(
                removedStrokes = s.strokes[sel.pageId].orEmpty().filter { it.id in sel.strokeIds },
                removedItems = s.items[sel.pageId].orEmpty().filter { it.id in sel.itemIds },
            )
        )
        setState { copy(selection = null) }
    }

    private fun addShape(intent: ViewerIntent.AddShape) {
        val t = currentState.toolSettings
        val brush = InkBrushes.brush(BrushKind.PEN, t.penColor, t.penSize)
        val stroke = StrokeGeometry.shapeStroke(t.shape, intent.x0, intent.y0, intent.x1, intent.y1, brush) ?: return
        commit(Edit(addedStrokes = listOf(InkStroke(newId(), intent.pageId, currentState.activeLayerId, BrushKind.PEN, stroke))))
    }

    private fun placeStamp(intent: ViewerIntent.PlaceStamp) {
        val t = currentState.toolSettings
        val item = AnnotationItem(
            id = newId(), pageId = intent.pageId, layerId = currentState.activeLayerId, kind = ItemKind.STAMP,
            x = intent.x, y = intent.y, size = t.stampSize, color = t.penColor, text = t.stampCode,
        )
        commit(Edit(addedItems = listOf(item)))
    }

    private fun textTap(intent: ViewerIntent.TextTap) {
        val s = currentState
        val existing = s.items[intent.pageId].orEmpty().lastOrNull {
            it.kind == ItemKind.TEXT && it.layerId in s.visibleLayerIds && ItemRenderer.bounds(it).contains(intent.x, intent.y)
        }
        val editor = if (existing != null) {
            TextEditor(intent.pageId, existing.x, existing.y, existing.id, existing.text, existing.size, existing.color)
        } else {
            TextEditor(intent.pageId, intent.x, intent.y, null, "", s.toolSettings.textSize, s.toolSettings.penColor)
        }
        setState { copy(textEditor = editor) }
    }

    private fun confirmText(intent: ViewerIntent.ConfirmText) {
        val s = currentState
        val editor = s.textEditor ?: return
        setState { copy(textEditor = null, toolSettings = toolSettings.copy(textSize = intent.size)) }
        val old = editor.itemId?.let { id -> s.items[editor.pageId].orEmpty().firstOrNull { it.id == id } }
        val text = intent.text.trim()
        when {
            text.isEmpty() && old != null -> commit(Edit(removedItems = listOf(old)))
            text.isEmpty() -> Unit
            old != null -> commit(Edit(removedItems = listOf(old), addedItems = listOf(old.copy(text = text, size = intent.size, color = intent.color))))
            else -> commit(
                Edit(
                    addedItems = listOf(
                        AnnotationItem(
                            id = newId(), pageId = editor.pageId, layerId = s.activeLayerId, kind = ItemKind.TEXT,
                            x = editor.x, y = editor.y, size = intent.size, color = intent.color, text = text,
                        )
                    )
                )
            )
        }
    }

    private fun deleteLayer(id: Long) {
        val s = currentState
        if (s.layers.size <= 1) {
            sendEffect(ViewerEffect.Message("레이어는 최소 1개가 필요합니다"))
            return
        }
        setState {
            copy(
                strokes = strokes.mapValues { (_, l) -> l.filterNot { it.layerId == id } },
                items = items.mapValues { (_, l) -> l.filterNot { it.layerId == id } },
                selection = null,
            )
        }
        // 삭제된 레이어를 참조하는 되돌리기 기록은 더 이상 유효하지 않음
        undoStack.clear()
        redoStack.clear()
        setState { copy(canUndo = false, canRedo = false) }
        writes.trySend { annotations.deleteLayer(id) }
    }

    // ------------------------------------------------------------------ 링크

    private fun linkRectDrawn(pageId: Long, rect: WorldRect) {
        if (rect.width < 10f && rect.height < 10f) {
            val cx = (rect.left + rect.right) / 2
            val cy = (rect.top + rect.bottom) / 2
            currentState.links.firstOrNull { it.fromPageId == pageId && it.rect.contains(cx, cy) }?.let {
                setState { copy(linkToDelete = it) }
            }
            return
        }
        setState { copy(pendingLink = PendingLink(pageId, rect)) }
    }

    private fun createLink(targetIndex: Int) {
        val pending = currentState.pendingLink ?: return
        val target = currentState.pages.getOrNull(targetIndex) ?: return
        setState { copy(pendingLink = null) }
        viewModelScope.launch {
            annotations.addLink(scoreId, pending.pageId, pending.rect, target.id)
            sendEffect(ViewerEffect.Message("${targetIndex + 1}쪽으로 가는 링크를 만들었습니다"))
        }
    }

    // ------------------------------------------------------------------ 곡 나누기

    private fun startSongSplit() {
        val pages = currentState.pages
        if (pages.isEmpty()) return
        songSplitJob?.cancel()
        setState { copy(songSplit = SongSplitUi(total = pages.size), panel = null, songPickerOpen = false) }
        songSplitJob = viewModelScope.launch {
            try {
                val found = songSplitter.detect(pages) { done, total ->
                    setState { copy(songSplit = songSplit?.copy(done = done, total = total)) }
                }
                val existing = currentState.bookmarks.associateBy { it.pageId }
                val ui = found.map { c ->
                    SongCandidateUi(
                        pageIndex = c.pageIndex,
                        pageId = c.pageId,
                        // 이미 이름을 붙여 둔 북마크가 있으면 그 이름을 우선
                        name = existing[c.pageId]?.title ?: c.name,
                        include = true,
                        byTitle = c.byTitle,
                        byPreviousEnd = c.byPreviousEnd,
                        preview = c.preview?.asImageBitmap(),
                    )
                }
                setState { copy(songSplit = songSplit?.copy(running = false, candidates = ui)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { copy(songSplit = songSplit?.copy(running = false, error = "분석하지 못했습니다: ${e.message}")) }
            }
        }
    }

    private fun applySongSplit() {
        val split = currentState.songSplit ?: return
        val chosen = split.candidates.filter { it.include && it.name.isNotBlank() }
        setState { copy(songSplit = null) }
        viewModelScope.launch {
            val existing = currentState.bookmarks.associateBy { it.pageId }
            for (c in chosen) {
                val old = existing[c.pageId]
                when {
                    old == null -> annotations.addBookmark(scoreId, c.pageId, c.name.trim())
                    old.title != c.name.trim() -> annotations.renameBookmark(old.id, c.name.trim())
                }
            }
            sendEffect(ViewerEffect.Message("${chosen.size}곡으로 나눴습니다. 위쪽 '곡' 버튼으로 바로 이동할 수 있어요"))
        }
    }

    // ------------------------------------------------------------------ 내보내기

    private fun startExport(options: ExportOptions) {
        pendingExport = options
        setState { copy(exportDialogOpen = false) }
        val original = currentState.score?.originalUri
        if (options.overwriteOriginal && original != null) {
            exportTo(Uri.parse(original))
        } else {
            val title = currentState.score?.title ?: "악보"
            sendEffect(ViewerEffect.CreateDocument(if (options.includeAnnotations) "$title (필기).pdf" else "$title.pdf"))
        }
    }

    private fun exportTo(uri: Uri) {
        val s = currentState
        val options = pendingExport
        val layerFilter = if (options.visibleLayersOnly) s.visibleLayerIds else s.layers.mapTo(HashSet()) { it.id }
        val strokes = if (options.includeAnnotations) s.strokes.values.flatten().filter { it.layerId in layerFilter } else emptyList()
        val items = if (options.includeAnnotations) s.items.values.flatten().filter { it.layerId in layerFilter } else emptyList()
        setState { copy(exportProgress = 0f) }
        viewModelScope.launch {
            try {
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("파일을 열 수 없습니다")
                out.use { exporter.export(s.pages, strokes, items, it) { p -> setState { copy(exportProgress = p) } } }
                sendEffect(ViewerEffect.Message(if (options.overwriteOriginal) "원본 PDF에 저장했습니다" else "PDF로 저장했습니다"))
            } catch (e: SecurityException) {
                sendEffect(ViewerEffect.Message("원본 파일에 쓸 권한이 없습니다. 새 파일로 저장해 주세요"))
            } catch (e: Exception) {
                sendEffect(ViewerEffect.Message("저장하지 못했습니다: ${e.message}"))
            } finally {
                setState { copy(exportProgress = null) }
            }
        }
    }

    // ------------------------------------------------------------------ 기타

    private fun updateSettings(transform: (com.scorebook.core.model.AppSettings) -> com.scorebook.core.model.AppSettings) {
        setState { copy(settings = transform(settings)) }
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    private fun flushSession() {
        val start = sessionStart ?: return
        sessionStart = null
        val seconds = (System.currentTimeMillis() - start) / 1000
        appScope.launch { practice.record(scoreId, start, seconds) }
    }

    override fun onCleared() {
        flushSession()
        saveLastPageNow()
        autoTurnJob?.cancel()
        writes.close()
        super.onCleared()
    }

    private fun newId() = UUID.randomUUID().toString()

    companion object {
        private const val MAX_UNDO = 200
        /** 획 지우개의 판정 크기(pt) */
        private const val STROKE_ERASER_SIZE = 10f
        const val MIN_ERASER = 4f
        const val MAX_ERASER = 60f
    }
}

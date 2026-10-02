package com.scorebook.feature.viewer

import android.net.Uri
import com.scorebook.core.ink.ShapeKind
import com.scorebook.core.model.AnnotationItem
import com.scorebook.core.model.AnnotationLayer
import com.scorebook.core.model.AppSettings
import com.scorebook.core.model.Bookmark
import com.scorebook.core.model.ColorMode
import com.scorebook.core.model.InkStroke
import com.scorebook.core.model.Score
import com.scorebook.core.model.ScoreLink
import com.scorebook.core.model.ScorePage
import com.scorebook.core.model.SetlistEntry
import com.scorebook.core.model.ViewMode
import com.scorebook.core.model.WorldRect
import com.scorebook.core.mvi.UiEffect
import com.scorebook.core.mvi.UiIntent
import com.scorebook.core.mvi.UiState

enum class AnnotationTool { PEN, HIGHLIGHTER, ERASER, LASSO, SHAPE, TEXT, STAMP }

enum class ViewerPanel { PAGES, TOOLS }

data class ToolSettings(
    val tool: AnnotationTool = AnnotationTool.PEN,
    val penColor: Int = 0xFF111111.toInt(),
    val penSize: Float = 1f,
    val highlighterColor: Int = 0x66FFEB3B,
    val highlighterSize: Float = 9f,
    val shape: ShapeKind = ShapeKind.LINE,
    val stampCode: String = "dyn:p",
    val stampSize: Float = 16f,
    val textSize: Float = 12f,
)

data class Selection(
    val pageId: Long,
    val strokeIds: Set<String>,
    val itemIds: Set<String>,
    val bounds: WorldRect,
)

data class TextEditor(
    val pageId: Long,
    val x: Float,
    val y: Float,
    val itemId: String?,
    val text: String,
    val size: Float,
    val color: Int,
)

data class PendingLink(val pageId: Long, val rect: WorldRect)

data class SetlistContext(
    val setlistId: Long,
    val index: Int,
    val entries: List<SetlistEntry>,
) {
    val next: SetlistEntry? get() = entries.getOrNull(index + 1)
    val previous: SetlistEntry? get() = entries.getOrNull(index - 1)
}

data class ExportOptions(
    val includeAnnotations: Boolean = true,
    val visibleLayersOnly: Boolean = true,
    val overwriteOriginal: Boolean = false,
)

data class ViewerState(
    val loading: Boolean = true,
    val error: String? = null,
    val score: Score? = null,
    val pages: List<ScorePage> = emptyList(),
    val layers: List<AnnotationLayer> = emptyList(),
    val activeLayerId: Long = -1,
    val strokes: Map<Long, List<InkStroke>> = emptyMap(),
    val items: Map<Long, List<AnnotationItem>> = emptyMap(),
    val links: List<ScoreLink> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val landscape: Boolean = false,
    val currentPage: Int = 0,
    val halfStep: Boolean = false,
    val chromeVisible: Boolean = true,
    val annotating: Boolean = false,
    val toolSettings: ToolSettings = ToolSettings(),
    val fingerDrawing: Boolean = false,
    val selection: Selection? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val performanceMode: Boolean = false,
    val linkMode: Boolean = false,
    val showLinks: Boolean = true,
    val linkReturnPage: Int? = null,
    val pendingLink: PendingLink? = null,
    val linkToDelete: ScoreLink? = null,
    val textEditor: TextEditor? = null,
    val panel: ViewerPanel? = null,
    val layersDialogOpen: Boolean = false,
    val stampPickerOpen: Boolean = false,
    val exportDialogOpen: Boolean = false,
    val exportProgress: Float? = null,
    val bookmarkDialogOpen: Boolean = false,
    val autoTurnActive: Boolean = false,
    val faceGestureActive: Boolean = false,
    val setlist: SetlistContext? = null,
) : UiState {
    /** 가로 모드 + 설정이 켜져 있으면 한 페이지 모드를 자동으로 두 페이지로 */
    val effectiveMode: ViewMode
        get() = when {
            settings.viewMode == ViewMode.SINGLE && landscape && settings.twoPageInLandscape -> ViewMode.TWO_PAGE
            settings.viewMode == ViewMode.TWO_PAGE && !landscape -> ViewMode.SINGLE
            else -> settings.viewMode
        }

    val colorMode: ColorMode get() = settings.colorMode

    val visibleLayerIds: Set<Long> get() = layers.filter { it.visible }.mapTo(HashSet()) { it.id }

    val anyDialogOpen: Boolean
        get() = textEditor != null || pendingLink != null || linkToDelete != null || panel != null ||
            layersDialogOpen || stampPickerOpen || exportDialogOpen || bookmarkDialogOpen

    val inkEnabled: Boolean get() = !loading && !performanceMode && !anyDialogOpen && exportProgress == null
}

sealed interface ViewerIntent : UiIntent {
    // 페이지 이동
    data object NextPage : ViewerIntent
    data object PreviousPage : ViewerIntent
    data class GoToPage(val index: Int) : ViewerIntent
    data class PageSettled(val index: Int) : ViewerIntent
    data class Tap(val pageId: Long?, val worldX: Float, val worldY: Float, val screenFractionX: Float) : ViewerIntent
    data class OrientationChanged(val landscape: Boolean) : ViewerIntent

    // 화면/보기
    data object ToggleChrome : ViewerIntent
    data class SetViewMode(val mode: ViewMode) : ViewerIntent
    data class SetColorMode(val mode: ColorMode) : ViewerIntent
    data object ToggleAutoCrop : ViewerIntent
    data object TogglePerformanceMode : ViewerIntent
    data class OpenPanel(val panel: ViewerPanel?) : ViewerIntent
    data object ToggleAutoTurn : ViewerIntent
    data object ToggleFaceGesture : ViewerIntent

    // 필기
    data object StylusDetected : ViewerIntent
    data object ToggleAnnotating : ViewerIntent
    data class SelectTool(val tool: AnnotationTool) : ViewerIntent
    data class UpdateToolSettings(val settings: ToolSettings) : ViewerIntent
    data object ToggleFingerDrawing : ViewerIntent
    data class SetEraserMode(val mode: com.scorebook.core.model.EraserMode) : ViewerIntent
    data class SetEraserSize(val size: Float) : ViewerIntent
    data class StrokesAdded(val strokes: List<InkStroke>) : ViewerIntent
    data class EraseSegment(val pageId: Long, val x0: Float, val y0: Float, val x1: Float, val y1: Float) : ViewerIntent
    data object EraseEnded : ViewerIntent
    data class LassoSelect(val pageId: Long, val polygon: List<Pair<Float, Float>>) : ViewerIntent
    data class MoveSelection(val dx: Float, val dy: Float) : ViewerIntent
    data object DeleteSelection : ViewerIntent
    data object ClearSelection : ViewerIntent
    data class AddShape(val pageId: Long, val x0: Float, val y0: Float, val x1: Float, val y1: Float) : ViewerIntent
    data class PlaceStamp(val pageId: Long, val x: Float, val y: Float) : ViewerIntent
    data class TextTap(val pageId: Long, val x: Float, val y: Float) : ViewerIntent
    data class ConfirmText(val text: String, val size: Float, val color: Int) : ViewerIntent
    data object Undo : ViewerIntent
    data object Redo : ViewerIntent
    data class OpenStampPicker(val open: Boolean) : ViewerIntent

    // 레이어
    data class OpenLayers(val open: Boolean) : ViewerIntent
    data class SetActiveLayer(val id: Long) : ViewerIntent
    data class ToggleLayerVisible(val id: Long) : ViewerIntent
    data class AddLayer(val name: String) : ViewerIntent
    data class RenameLayer(val id: Long, val name: String) : ViewerIntent
    data class DeleteLayer(val id: Long) : ViewerIntent

    // 링크/북마크
    data object ToggleLinkMode : ViewerIntent
    data object ToggleShowLinks : ViewerIntent
    data class LinkRectDrawn(val pageId: Long, val rect: WorldRect) : ViewerIntent
    data class CreateLink(val targetPageIndex: Int) : ViewerIntent
    data object ConfirmDeleteLink : ViewerIntent
    data object ReturnFromLink : ViewerIntent
    data object DismissLinkReturn : ViewerIntent
    data class OpenBookmarkDialog(val open: Boolean) : ViewerIntent
    data class AddBookmark(val title: String) : ViewerIntent
    data class DeleteBookmark(val id: Long) : ViewerIntent

    // 내보내기
    data class OpenExport(val open: Boolean) : ViewerIntent
    data class StartExport(val options: ExportOptions) : ViewerIntent
    data class ExportToUri(val uri: Uri) : ViewerIntent

    data object DismissDialog : ViewerIntent
    data object LifecycleStart : ViewerIntent
    data object LifecycleStop : ViewerIntent
}

sealed interface ViewerEffect : UiEffect {
    data class Message(val text: String) : ViewerEffect
    data class CreateDocument(val suggestedName: String) : ViewerEffect
    data class OpenScore(val scoreId: Long, val setlistId: Long, val index: Int) : ViewerEffect
    data object OpenPageEditor : ViewerEffect
    data object Close : ViewerEffect
}

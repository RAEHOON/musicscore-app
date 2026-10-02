package com.scorebook.core.model

import androidx.compose.runtime.Immutable
import androidx.ink.strokes.Stroke

@Immutable
data class Score(
    val id: Long,
    val title: String,
    val composer: String,
    val genre: String,
    val musicalKey: String,
    val tags: List<String>,
    val favorite: Boolean,
    val pageCount: Int,
    val lastPageIndex: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long,
    val thumbnailPath: String?,
    val originalUri: String?,
)

enum class PageSourceKind { PDF, IMAGE, STAFF }

/**
 * 악보의 "가상 페이지".
 *
 * 실제 PDF/이미지를 건드리지 않고 순서·회전·자르기·빈 오선지 삽입을 메타데이터로 표현한다.
 * 필기는 이 페이지의 [id] 에 붙기 때문에 페이지 순서를 바꿔도 필기가 따라간다.
 *
 * 좌표계("월드 좌표"): 원본 페이지를 PdfRenderer 가 그리는 그대로의 좌표, 단위는 PDF 포인트(1/72 inch),
 * 원점은 왼쪽 위. [rotation]/[crop] 은 화면 표시와 내보내기에만 적용되므로 필기도 함께 회전/자르기 된다.
 */
@Immutable
data class ScorePage(
    val id: Long,
    val scoreId: Long,
    val position: Int,
    val kind: PageSourceKind,
    val sourcePath: String?,
    val sourceIndex: Int,
    val width: Float,
    val height: Float,
    val rotation: Int,
    val crop: PageCrop?,
)

/** 월드 좌표 기준 비율(0..1) 자르기 영역 */
@Immutable
data class PageCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val FULL = PageCrop(0f, 0f, 1f, 1f)
    }
}

@Immutable
data class AnnotationLayer(
    val id: Long,
    val scoreId: Long,
    val name: String,
    val visible: Boolean,
    val position: Int,
)

enum class BrushKind(val code: Int) {
    PEN(0), HIGHLIGHTER(1), MARKER(2);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: PEN
    }
}

/** 페이지에 붙은 잉크 획. [stroke] 는 월드 좌표(PDF 포인트). */
class InkStroke(
    val id: String,
    val pageId: Long,
    val layerId: Long,
    val kind: BrushKind,
    val stroke: Stroke,
) {
    val color: Int get() = stroke.brush.colorIntArgb
    val size: Float get() = stroke.brush.size
}

enum class ItemKind(val code: Int) {
    TEXT(0), STAMP(1);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: TEXT
    }
}

/** 텍스트/음악기호 스탬프. (x, y) 는 중심점, [size] 는 글자 크기(pt). */
@Immutable
data class AnnotationItem(
    val id: String,
    val pageId: Long,
    val layerId: Long,
    val kind: ItemKind,
    val x: Float,
    val y: Float,
    val size: Float,
    val color: Int,
    val text: String,
)

@Immutable
data class WorldRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    companion object {
        fun of(x1: Float, y1: Float, x2: Float, y2: Float) =
            WorldRect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }
}

/** 도돌이표/D.S./Coda 점프 링크 */
@Immutable
data class ScoreLink(
    val id: Long,
    val scoreId: Long,
    val fromPageId: Long,
    val rect: WorldRect,
    val toPageId: Long,
    val label: String = "",
)

@Immutable
data class Bookmark(
    val id: Long,
    val scoreId: Long,
    val pageId: Long,
    val title: String,
)

@Immutable
data class Setlist(
    val id: Long,
    val name: String,
    val itemCount: Int,
    val updatedAt: Long,
)

@Immutable
data class SetlistEntry(
    val id: Long,
    val setlistId: Long,
    val position: Int,
    val score: Score,
    val startPageId: Long?,
)

enum class AudioKind(val code: Int) {
    ATTACHED(0), RECORDING(1);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: ATTACHED
    }
}

@Immutable
data class AudioClip(
    val id: Long,
    val scoreId: Long,
    val title: String,
    val path: String,
    val kind: AudioKind,
    val durationMs: Long,
    val createdAt: Long,
)

@Immutable
data class PracticeDay(val dayStartMillis: Long, val seconds: Long)

@Immutable
data class PracticeTotal(val scoreId: Long, val title: String, val seconds: Long, val sessions: Int)

enum class ViewMode { SINGLE, TWO_PAGE, HALF, VERTICAL }

enum class ColorMode { NORMAL, NIGHT, SEPIA }

enum class FaceGestureMode { HEAD_TURN, SMILE, MOUTH_OPEN }

enum class LibrarySort { RECENT, TITLE, COMPOSER, ADDED }

/** 획 지우개: 닿은 획 전체 삭제 / 영역 지우개: 네모 영역 안의 잉크만 잘라냄 */
enum class EraserMode { STROKE, AREA }

@Immutable
data class AppSettings(
    val viewMode: ViewMode = ViewMode.SINGLE,
    val twoPageInLandscape: Boolean = true,
    val colorMode: ColorMode = ColorMode.NORMAL,
    val autoCropMargins: Boolean = false,
    val tapZonesEnabled: Boolean = true,
    val tapZoneFraction: Float = 0.3f,
    val pageTurnAnimation: Boolean = true,
    val rememberLastPage: Boolean = true,
    val volumeKeysTurnPages: Boolean = false,
    val palmRejection: Boolean = true,
    val stylusButtonErases: Boolean = true,
    val eraserMode: EraserMode = EraserMode.STROKE,
    /** 영역 지우개 한 변의 길이 (pt) */
    val eraserSize: Float = 14f,
    val faceGestureMode: FaceGestureMode = FaceGestureMode.HEAD_TURN,
    val faceGestureSensitivity: Float = 0.5f,
    val autoTurnSeconds: Int = 20,
    val autoScrollSpeed: Float = 30f,
    val metronomeBpm: Int = 100,
    val metronomeBeats: Int = 4,
    val librarySort: LibrarySort = LibrarySort.RECENT,
)

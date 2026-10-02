package com.scorebook.core.input

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import com.scorebook.core.model.BrushKind
import javax.inject.Inject
import javax.inject.Singleton

/** 화면(윈도우) 좌표계에서 본 한 페이지의 위치 정보 */
class PageRegion(
    val pageId: Long,
    /** 실제로 보이는(잘린) 영역 */
    val windowRect: RectF,
    /** 월드(pt) → 윈도우(px) */
    val worldToWindow: Matrix,
) {
    fun windowToWorld(x: Float, y: Float): FloatArray {
        val inv = Matrix()
        worldToWindow.invert(inv)
        return floatArrayOf(x, y).also { inv.mapPoints(it) }
    }
}

enum class GestureTool { PEN, HIGHLIGHTER, ERASER, LASSO, MOVE_SELECTION, SHAPE, TEXT, STAMP, LINK }

/** 펜이 닿는 순간 결정되는 도구 정보 */
class InkToolSpec(
    val tool: GestureTool,
    val layerId: Long,
    val brush: Brush? = null,
    val brushKind: BrushKind = BrushKind.PEN,
)

class FinishedInk(val pageId: Long, val layerId: Long, val kind: BrushKind, val stroke: Stroke)

enum class GesturePhase { START, MOVE, END, CANCEL }

class ToolGesture(
    val phase: GesturePhase,
    val tool: GestureTool,
    val pageId: Long,
    val x: Float,
    val y: Float,
)

/** 뷰어 화면이 구현해서 [InputHub.target] 에 등록한다. */
interface InkTarget {
    /** 공연 모드, 다이얼로그 표시 중 등에는 false */
    fun isInkEnabled(): Boolean

    /** 손가락으로도 필기할지(필기 툴바에서 켬) */
    fun fingerDraws(): Boolean

    fun palmRejection(): Boolean

    /** 윈도우 좌표의 페이지. 툴바 등 UI 위라면 null */
    fun regionAt(x: Float, y: Float): PageRegion?

    /** 펜/손가락이 닿는 순간 사용할 도구. null 이면 이 입력은 처리하지 않음 */
    fun toolFor(pageId: Long, worldX: Float, worldY: Float, eraserTip: Boolean, stylusButton: Boolean): InkToolSpec?

    fun onStrokesFinished(strokes: List<FinishedInk>)

    fun onToolGesture(gesture: ToolGesture)

    /** 펜이 화면 근처에 왔음(호버/터치) → 필기 툴바 자동 표시 */
    fun onStylusSeen()
}

/**
 * 액티비티 수준의 입력 라우터.
 *
 * Activity.dispatchTouchEvent 에서 가장 먼저 호출되어
 * - 펜(스타일러스) 입력 → Jetpack Ink 의 InProgressStrokesView(프런트 버퍼, 예측 입력)로 직접 전달 → 최저 지연 필기
 * - 손가락 입력 → Compose 로 그대로 통과(페이지 넘김, 확대)
 * - 펜이 근처에 있을 때의 손가락/손바닥 → 무시(팜 리젝션)
 * 을 결정한다. 펜이 그리는 동안 Compose 는 이벤트를 전혀 받지 않으므로 페이저가 움직일 수 없다.
 */
@Singleton
class InputHub @Inject constructor() {

    var target: InkTarget? = null
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    enum class Decision { PASS, CONSUME, CANCEL_THEN_CONSUME }

    private enum class Mode { IDLE, PASS, INK, TOOL, SWALLOW }

    private class Meta(val pageId: Long, val layerId: Long, val kind: BrushKind)

    private var inkView: InProgressStrokesView? = null
    private var predictor: MotionEventPredictor? = null
    private val pending = HashMap<InProgressStrokeId, Meta>()

    private var mode = Mode.IDLE
    private var pointerId = -1
    private var strokeId: InProgressStrokeId? = null
    private var region: PageRegion? = null
    private var tool: InkToolSpec? = null
    private val windowToWorld = Matrix()
    private val point = FloatArray(2)
    private var lastStylusTime = 0L

    private val finishedListener = object : InProgressStrokesFinishedListener {
        override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
            val finished = strokes.mapNotNull { (id, stroke) ->
                pending.remove(id)?.let { FinishedInk(it.pageId, it.layerId, it.kind, stroke) }
            }
            // 같은 UI 프레임 안에서 Compose 상태에 추가해야 깜빡임이 없다
            if (finished.isNotEmpty()) target?.onStrokesFinished(finished)
            inkView?.removeFinishedStrokes(strokes.keys)
        }
    }

    fun attach(container: ViewGroup) {
        if (inkView != null) return
        val view = InProgressStrokesView(container.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addFinishedStrokesListener(finishedListener)
        }
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.eagerInit()
        inkView = view
        predictor = MotionEventPredictor.newInstance(view)
    }

    fun detach() {
        inkView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        inkView = null
        predictor = null
        pending.clear()
        mode = Mode.IDLE
    }

    fun onKeyEvent(event: KeyEvent): Boolean = keyHandler?.invoke(event) == true

    /** 호버 이벤트. true 를 반환하면 진행 중이던 손가락 제스처를 Compose 에서 취소해야 한다. */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val type = event.getToolType(0)
        if (type != MotionEvent.TOOL_TYPE_STYLUS && type != MotionEvent.TOOL_TYPE_ERASER) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                lastStylusTime = event.eventTime
                target?.onStylusSeen()
            }
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> lastStylusTime = event.eventTime
        }
        val t = target ?: return false
        if (mode == Mode.PASS && t.isInkEnabled() && t.palmRejection()) {
            // 손바닥이 먼저 닿은 상태에서 펜이 다가옴 → 손바닥 제스처 취소
            mode = Mode.SWALLOW
            return true
        }
        return false
    }

    fun onTouchEvent(event: MotionEvent, decorView: View): Decision {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event, 0, decorView)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(event, decorView)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> onCancel(event)
            else -> if (mode == Mode.PASS || mode == Mode.IDLE) Decision.PASS else Decision.CONSUME
        }
    }

    // ---------------------------------------------------------------------

    private fun isStylus(event: MotionEvent, index: Int): Boolean {
        val type = event.getToolType(index)
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun onDown(event: MotionEvent, index: Int, decorView: View): Decision {
        val t = target
        val stylus = isStylus(event, index)
        if (stylus) {
            lastStylusTime = event.eventTime
            t?.onStylusSeen()
        }
        if (t == null || !t.isInkEnabled()) {
            mode = Mode.PASS
            return Decision.PASS
        }
        val x = event.getX(index)
        val y = event.getY(index)
        val r = t.regionAt(x, y)
        if (r != null && (stylus || t.fingerDraws())) {
            if (begin(event, index, r, t, decorView)) return Decision.CONSUME
        }
        if (!stylus && r != null && t.palmRejection() && event.eventTime - lastStylusTime < PALM_WINDOW_MS) {
            mode = Mode.SWALLOW
            return Decision.CONSUME
        }
        mode = Mode.PASS
        return Decision.PASS
    }

    private fun onPointerDown(event: MotionEvent, decorView: View): Decision = when (mode) {
        Mode.PASS -> {
            val index = event.actionIndex
            val t = target
            if (isStylus(event, index) && t != null && t.isInkEnabled()) {
                lastStylusTime = event.eventTime
                t.onStylusSeen()
                val r = t.regionAt(event.getX(index), event.getY(index))
                if (r != null && begin(event, index, r, t, decorView)) {
                    Decision.CANCEL_THEN_CONSUME
                } else {
                    Decision.PASS
                }
            } else {
                Decision.PASS
            }
        }
        Mode.IDLE -> Decision.PASS
        else -> Decision.CONSUME
    }

    private fun begin(event: MotionEvent, index: Int, r: PageRegion, t: InkTarget, decorView: View): Boolean {
        r.worldToWindow.invert(windowToWorld)
        point[0] = event.getX(index); point[1] = event.getY(index)
        windowToWorld.mapPoints(point)
        val eraserTip = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        val button = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val spec = t.toolFor(r.pageId, point[0], point[1], eraserTip, button) ?: return false

        pointerId = event.getPointerId(index)
        region = r
        tool = spec
        if (Build.VERSION.SDK_INT >= 30) decorView.requestUnbufferedDispatch(event)

        val view = inkView
        if ((spec.tool == GestureTool.PEN || spec.tool == GestureTool.HIGHLIGHTER) && spec.brush != null && view != null) {
            val loc = IntArray(2).also { view.getLocationInWindow(it) }
            view.motionEventToViewTransform = Matrix().apply { setTranslate(-loc[0].toFloat(), -loc[1].toFloat()) }
            view.maskPath = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, view.width.toFloat(), view.height.toFloat(), Path.Direction.CW)
                addRect(RectF(r.windowRect).apply { offset(-loc[0].toFloat(), -loc[1].toFloat()) }, Path.Direction.CW)
            }
            val id = view.startStroke(event, pointerId, spec.brush, Matrix(windowToWorld))
            pending[id] = Meta(r.pageId, spec.layerId, spec.brushKind)
            strokeId = id
            predictor?.record(event)
            mode = Mode.INK
        } else {
            mode = Mode.TOOL
            t.onToolGesture(ToolGesture(GesturePhase.START, spec.tool, r.pageId, point[0], point[1]))
        }
        return true
    }

    private fun onMove(event: MotionEvent): Decision {
        when (mode) {
            Mode.PASS, Mode.IDLE -> return Decision.PASS
            Mode.SWALLOW -> return Decision.CONSUME
            Mode.INK -> {
                val view = inkView ?: return Decision.CONSUME
                val id = strokeId ?: return Decision.CONSUME
                if (event.findPointerIndex(pointerId) < 0) return Decision.CONSUME
                predictor?.record(event)
                val predicted = predictor?.predict()
                try {
                    view.addToStroke(event, pointerId, id, predicted)
                } catch (_: IllegalArgumentException) {
                    // 일부 기기의 중복/역순 입력 → 무시
                } finally {
                    predicted?.recycle()
                }
            }
            Mode.TOOL -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return Decision.CONSUME
                val r = region ?: return Decision.CONSUME
                val spec = tool ?: return Decision.CONSUME
                for (h in 0 until event.historySize) {
                    emit(GesturePhase.MOVE, spec, r, event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                }
                emit(GesturePhase.MOVE, spec, r, event.getX(index), event.getY(index))
            }
        }
        return Decision.CONSUME
    }

    private fun onPointerUp(event: MotionEvent): Decision {
        if (mode == Mode.PASS || mode == Mode.IDLE) return Decision.PASS
        if (event.getPointerId(event.actionIndex) == pointerId) {
            finish(event, event.actionIndex, mode)
            mode = Mode.SWALLOW
        }
        return Decision.CONSUME
    }

    private fun onUp(event: MotionEvent): Decision {
        val wasMode = mode
        mode = Mode.IDLE
        return when (wasMode) {
            Mode.PASS, Mode.IDLE -> Decision.PASS
            Mode.INK, Mode.TOOL -> {
                if (event.getPointerId(0) == pointerId) finish(event, 0, wasMode)
                reset()
                Decision.CONSUME
            }
            Mode.SWALLOW -> Decision.CONSUME
        }
    }

    private fun onCancel(event: MotionEvent): Decision {
        val wasMode = mode
        mode = Mode.IDLE
        when (wasMode) {
            Mode.INK -> strokeId?.let { id ->
                pending.remove(id)
                inkView?.cancelStroke(id, event)
            }
            Mode.TOOL -> {
                val r = region
                val spec = tool
                if (r != null && spec != null) target?.onToolGesture(ToolGesture(GesturePhase.CANCEL, spec.tool, r.pageId, 0f, 0f))
            }
            else -> Unit
        }
        reset()
        return if (wasMode == Mode.PASS || wasMode == Mode.IDLE) Decision.PASS else Decision.CONSUME
    }

    private fun finish(event: MotionEvent, index: Int, finishingMode: Mode) {
        when (finishingMode) {
            Mode.INK -> strokeId?.let { id ->
                try {
                    inkView?.finishStroke(event, pointerId, id)
                } catch (_: IllegalArgumentException) {
                    pending.remove(id)
                    inkView?.cancelStroke(id, event)
                }
            }
            Mode.TOOL -> {
                val r = region ?: return
                val spec = tool ?: return
                emit(GesturePhase.END, spec, r, event.getX(index), event.getY(index))
            }
            else -> Unit
        }
        strokeId = null
    }

    private fun emit(phase: GesturePhase, spec: InkToolSpec, r: PageRegion, x: Float, y: Float) {
        point[0] = x; point[1] = y
        windowToWorld.mapPoints(point)
        target?.onToolGesture(ToolGesture(phase, spec.tool, r.pageId, point[0], point[1]))
    }

    private fun reset() {
        strokeId = null
        region = null
        tool = null
        pointerId = -1
    }

    companion object {
        /** 펜을 마지막으로 감지한 뒤 이 시간 동안 손가락 입력을 손바닥으로 간주 */
        private const val PALM_WINDOW_MS = 450L
    }
}

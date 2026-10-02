package com.scorebook.core.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.scorebook.core.model.WorldRect
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

enum class ShapeKind { LINE, ARROW, RECT, ELLIPSE }

/** 지우개/올가미/이동/도형 생성 등 획 단위 기하 연산. 모두 월드 좌표(pt). */
object StrokeGeometry {

    fun bounds(stroke: Stroke): WorldRect? =
        stroke.shape.computeBoundingBox()?.let { WorldRect(it.xMin, it.yMin, it.xMax, it.yMax) }

    /** 지우개 경로(선분 + 반경)와 획이 겹치는지 */
    fun hitsSegment(stroke: Stroke, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float): Boolean {
        val shape = stroke.shape
        return if (hypot(x1 - x0, y1 - y0) < 0.01f) {
            ImmutableBox.fromCenterAndDimensions(ImmutableVec(x1, y1), radius * 2, radius * 2)
                .intersects(shape, AffineTransform.IDENTITY)
        } else {
            val segment = ImmutableSegment(ImmutableVec(x0, y0), ImmutableVec(x1, y1))
            ImmutableParallelogram.fromSegmentAndPadding(segment, radius)
                .intersects(shape, AffineTransform.IDENTITY)
        }
    }

    /**
     * 영역 지우개: 정사각형 지우개가 (x0,y0)→(x1,y1) 로 지나간 자리의 잉크만 잘라낸다.
     *
     * 잘린 경계가 지우개 모서리에 맞도록 입력점을 촘촘하게 보간한 뒤, 지우개(+획 두께의 절반) 안에 든 점을 버리고
     * 남은 연속 구간을 각각 새 획으로 만든다.
     * @return 실제로 지워진 부분이 없으면 null, 있으면 남은 조각들(모두 지워졌으면 빈 리스트)
     */
    fun eraseArea(stroke: Stroke, x0: Float, y0: Float, x1: Float, y1: Float, halfSize: Float): List<Stroke>? {
        val src = stroke.inputs
        if (src.size == 0) return null
        val reach = halfSize + stroke.brush.size / 2f

        // 지우개 경로를 반 변 간격으로 샘플링 (빠르게 문질러도 빈틈 없이)
        val pathLen = hypot(x1 - x0, y1 - y0)
        val pathSteps = max(1, kotlin.math.ceil(pathLen / max(halfSize * 0.5f, 0.5f)).toInt())
        val cx = FloatArray(pathSteps + 1) { x0 + (x1 - x0) * it / pathSteps }
        val cy = FloatArray(pathSteps + 1) { y0 + (y1 - y0) * it / pathSteps }
        val minX = minOf(x0, x1) - reach
        val maxX = maxOf(x0, x1) + reach
        val minY = minOf(y0, y1) - reach
        val maxY = maxOf(y0, y1) + reach
        fun erased(x: Float, y: Float): Boolean {
            if (x < minX || x > maxX || y < minY || y > maxY) return false
            for (i in cx.indices) {
                if (kotlin.math.abs(x - cx[i]) <= reach && kotlin.math.abs(y - cy[i]) <= reach) return true
            }
            return false
        }

        // 입력점 복사 + 경계 근처가 매끄럽게 잘리도록 보간
        val step = max(0.4f, reach / 4f)
        val pts = ArrayList<StrokeInput>(src.size * 2)
        val prev = StrokeInput()
        val cur = StrokeInput()
        for (i in 0 until src.size) {
            src.populate(i, cur)
            if (i > 0) {
                val d = hypot(cur.x - prev.x, cur.y - prev.y)
                val n = (d / step).toInt()
                for (k in 1 until n) pts += lerp(prev, cur, k.toFloat() / n)
            }
            pts += copy(cur)
            prev.update(
                cur.x, cur.y, cur.elapsedTimeMillis, cur.toolType, cur.strokeUnitLengthCm,
                cur.pressure, cur.tiltRadians, cur.orientationRadians,
            )
        }

        val keep = BooleanArray(pts.size) { !erased(pts[it].x, pts[it].y) }
        if (keep.all { it }) return null

        val pieces = ArrayList<Stroke>()
        var start = -1
        for (i in 0..pts.size) {
            val k = i < pts.size && keep[i]
            if (k && start < 0) start = i
            if (!k && start >= 0) {
                buildPiece(stroke, pts, start, i)?.let(pieces::add)
                start = -1
            }
        }
        return pieces
    }

    private fun buildPiece(original: Stroke, pts: List<StrokeInput>, from: Int, until: Int): Stroke? {
        if (until - from < 2) return null
        val batch = MutableStrokeInputBatch()
        val t0 = pts[from].elapsedTimeMillis
        var lastX = Float.NaN
        var lastY = Float.NaN
        var lastT = 0L
        for (i in from until until) {
            val p = pts[i]
            if (p.x == lastX && p.y == lastY) continue
            val t = max(lastT, p.elapsedTimeMillis - t0)
            runCatching {
                batch.add(p.toolType, p.x, p.y, t, p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians)
                lastX = p.x
                lastY = p.y
                lastT = t
            }
        }
        if (batch.size < 2) return null
        return runCatching { Stroke(original.brush, batch) }.getOrNull()
    }

    private fun copy(s: StrokeInput) = StrokeInput().apply {
        update(s.x, s.y, s.elapsedTimeMillis, s.toolType, s.strokeUnitLengthCm, s.pressure, s.tiltRadians, s.orientationRadians)
    }

    private fun lerp(a: StrokeInput, b: StrokeInput, f: Float) = StrokeInput().apply {
        fun l(x: Float, y: Float) = x + (y - x) * f
        update(
            l(a.x, b.x), l(a.y, b.y),
            a.elapsedTimeMillis + ((b.elapsedTimeMillis - a.elapsedTimeMillis) * f).toLong(),
            a.toolType, a.strokeUnitLengthCm,
            l(a.pressure, b.pressure), l(a.tiltRadians, b.tiltRadians), a.orientationRadians,
        )
    }

    /** 올가미 다각형 안에 획 입력점의 [threshold] 이상이 들어오면 선택 */
    fun insideLasso(stroke: Stroke, polygon: List<Pair<Float, Float>>, threshold: Float = 0.6f): Boolean {
        if (polygon.size < 3) return false
        val inputs = stroke.inputs
        if (inputs.size == 0) return false
        val scratch = StrokeInput()
        val step = max(1, inputs.size / 40)
        var total = 0
        var inside = 0
        var i = 0
        while (i < inputs.size) {
            inputs.populate(i, scratch)
            total++
            if (pointInPolygon(scratch.x, scratch.y, polygon)) inside++
            i += step
        }
        return inside >= total * threshold
    }

    fun pointInPolygon(x: Float, y: Float, polygon: List<Pair<Float, Float>>): Boolean {
        var result = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val (xi, yi) = polygon[i]
            val (xj, yj) = polygon[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) result = !result
            j = i
        }
        return result
    }

    /** 획을 (dx, dy) 만큼 평행이동한 새 획 */
    fun translate(stroke: Stroke, dx: Float, dy: Float): Stroke {
        val src = stroke.inputs
        val batch = MutableStrokeInputBatch()
        val scratch = StrokeInput()
        for (i in 0 until src.size) {
            src.populate(i, scratch)
            batch.add(
                type = scratch.toolType,
                x = scratch.x + dx,
                y = scratch.y + dy,
                elapsedTimeMillis = scratch.elapsedTimeMillis,
                strokeUnitLengthCm = scratch.strokeUnitLengthCm,
                pressure = scratch.pressure,
                tiltRadians = scratch.tiltRadians,
                orientationRadians = scratch.orientationRadians,
            )
        }
        return Stroke(stroke.brush, batch)
    }

    /** 도형 도구: 시작/끝 점으로부터 합성 입력을 만들어 일반 잉크 획으로 저장(지우개·올가미가 그대로 동작). */
    fun shapeStroke(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float, brush: Brush): Stroke? {
        val points = shapePoints(kind, x0, y0, x1, y1)
        if (points.size < 2) return null
        val batch = MutableStrokeInputBatch()
        var t = 0L
        var lastX = Float.NaN
        var lastY = Float.NaN
        for ((x, y) in points) {
            if (x == lastX && y == lastY) continue
            runCatching {
                batch.add(InputToolType.STYLUS, x, y, t, pressure = 0.7f)
                t += 4
                lastX = x
                lastY = y
            }
        }
        if (batch.size < 2) return null
        return Stroke(brush, batch)
    }

    fun shapePoints(kind: ShapeKind, x0: Float, y0: Float, x1: Float, y1: Float): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        fun line(ax: Float, ay: Float, bx: Float, by: Float) {
            val len = hypot(bx - ax, by - ay)
            val n = max(2, (len / 2f).toInt())
            for (i in 0..n) {
                val f = i.toFloat() / n
                out += (ax + (bx - ax) * f) to (ay + (by - ay) * f)
            }
        }
        when (kind) {
            ShapeKind.LINE -> line(x0, y0, x1, y1)
            ShapeKind.ARROW -> {
                line(x0, y0, x1, y1)
                val angle = atan2(y1 - y0, x1 - x0)
                val head = max(6f, hypot(x1 - x0, y1 - y0) * 0.18f).coerceAtMost(24f)
                val a1 = angle + PI.toFloat() * 0.82f
                val a2 = angle - PI.toFloat() * 0.82f
                val w1x = x1 + cos(a1) * head
                val w1y = y1 + sin(a1) * head
                val w2x = x1 + cos(a2) * head
                val w2y = y1 + sin(a2) * head
                line(x1, y1, w1x, w1y)
                line(w1x, w1y, x1, y1)
                line(x1, y1, w2x, w2y)
            }
            ShapeKind.RECT -> {
                line(x0, y0, x1, y0)
                line(x1, y0, x1, y1)
                line(x1, y1, x0, y1)
                line(x0, y1, x0, y0)
            }
            ShapeKind.ELLIPSE -> {
                val cx = (x0 + x1) / 2
                val cy = (y0 + y1) / 2
                val rx = kotlin.math.abs(x1 - x0) / 2
                val ry = kotlin.math.abs(y1 - y0) / 2
                val n = max(24, ((rx + ry) * 1.6f).toInt())
                for (i in 0..n) {
                    val a = 2 * PI.toFloat() * i / n
                    out += (cx + cos(a) * rx) to (cy + sin(a) * ry)
                }
            }
        }
        return out
    }
}

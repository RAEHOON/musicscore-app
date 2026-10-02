package com.scorebook.core.analysis

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/** 페이지 위쪽에서 찾은 큰 제목 줄 (분석 이미지 대비 비율 좌표) */
class TitleLine(val left: Float, val top: Float, val right: Float, val bottom: Float)

class PageAnalysis(
    /** 페이지 마지막 보표(들)이 끝세로줄(얇은선+굵은선)로 끝남 */
    val endsWithFinalBarline: Boolean,
    /** 첫 보표 윗줄 위치 (높이 대비 비율), 보표가 없으면 null */
    val firstStaffTop: Float?,
    /** 큰 제목 후보 (검증 통과 여부와 무관) */
    val title: TitleLine?,
    /** 곡의 첫 페이지로 볼 만큼 확실한 제목 */
    val strongTitle: Boolean,
)

/**
 * 스캔/사진 악보 페이지의 레이아웃 분석.
 *
 * 실제 악보집(사진 촬영, 기울어짐·휘어짐·그림자·뒷면 비침 포함 166쪽)으로 튜닝한 규칙:
 * 1. 적응형 이진화(국소 평균) → 그림자에 강함
 * 2. 기울기 보정(투영 분산 최대화, ±3°)
 * 3. 오선: 긴 가로 구간의 행 투영 봉우리를 5개씩 등간격으로 묶음
 * 4. 끝세로줄: 보표 오른쪽 끝에서 행마다 "얇은선-틈-굵은선" 패턴 비율 (기울어진 세로줄도 인식)
 * 5. 제목: 첫 보표 위 영역의 글자(연결 성분)를 줄로 묶어, 큰 글자의 가운데 정렬 줄을 찾고
 *    주변에 오선이 없고(놓친 보표의 가사 줄이 아님) 첫 보표와 충분히 떨어져 있으면 제목으로 확정
 *
 * 입력 이미지 폭은 [WIDTH] 기준 (픽셀 임계값들이 이 크기에 맞춰져 있음).
 */
object ScoreLayoutAnalyzer {

    const val WIDTH = 1000

    private class Staff(val top: Float, val bottom: Float, val spacing: Float)

    fun analyze(gray: IntArray, w: Int, h: Int): PageAnalysis {
        val raw = binarize(gray, w, h)
        val b = deskew(raw, w, h)
        val lineMask = horizontalRuns(b, w, h, 30)

        var staves = findStaves(lineMask, w, h)
        val extents = staves.map { staffExtent(b, w, h, it) }
        val valid = staves.indices.filter { extents[it].second - extents[it].first >= 0.4f * w }
        staves = valid.map { staves[it] }
        val validExt = valid.map { extents[it] }
        val spacing = if (staves.isEmpty()) 7.5f else staves.map { it.spacing }.sorted()[staves.size / 2]

        val finals = staves.indices.map { i -> finalBarline(b, w, h, staves[i], validExt[i].second) }
        val tail = finals.takeLast(4)
        val ends = tail.isNotEmpty() && (tail.last() || tail.count { it } >= 2)

        val firstTopPx = staves.firstOrNull()?.top
        val titleBox = findTitle(b, w, h, firstTopPx, spacing)
        var strong = false
        if (titleBox != null) {
            val firstPx = firstTopPx ?: (0.45f * h)
            val gap = firstPx - titleBox[3]
            strong = gap >= 0.1f * w && staffLikeRows(lineMask, w, h, (0.02f * h).toInt(), (titleBox[3] + 6 * 7.5f).toInt()) <= 1
        }
        return PageAnalysis(
            endsWithFinalBarline = ends,
            firstStaffTop = firstTopPx?.div(h),
            title = titleBox?.let { TitleLine(it[0] / w, it[1] / h, it[2] / w, it[3] / h) },
            strongTitle = strong,
        )
    }

    /** 곡 시작 페이지 판정: 확실한 제목이 있거나, 앞 페이지가 끝세로줄로 끝나고 이 페이지 위쪽이 비어 있으면 */
    fun songStarts(analyses: List<PageAnalysis?>): List<Int> = buildList {
        analyses.forEachIndexed { i, a ->
            if (i == 0) {
                add(0)
                return@forEachIndexed
            }
            a ?: return@forEachIndexed
            val prevEnd = analyses[i - 1]?.endsWithFinalBarline == true
            val roomyTop = a.firstStaffTop == null || a.firstStaffTop >= 0.14f
            if (a.strongTitle || (prevEnd && roomyTop)) add(i)
        }
    }

    // ------------------------------------------------------------------ 이진화 / 기울기

    private fun binarize(gray: IntArray, w: Int, h: Int): BooleanArray {
        val stride = w + 1
        val integral = IntArray(stride * (h + 1))
        for (y in 0 until h) {
            var row = 0
            val base = y * w
            for (x in 0 until w) {
                row += gray[base + x]
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
            }
        }
        val r = 40
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w, x + r + 1)
                val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] - integral[y1 * stride + x0] + integral[y0 * stride + x0]
                val mean = sum.toFloat() / ((y1 - y0) * (x1 - x0))
                val g = gray[y * w + x]
                out[y * w + x] = (g < mean * 0.86f && g < 215) || g < 120
            }
        }
        return out
    }

    private fun deskew(b: BooleanArray, w: Int, h: Int): BooleanArray {
        var count = 0
        for (i in b.indices) if (b[i] && (i % w) % 2 == 0) count++
        val step = max(1, count / 200_000)
        val xs = IntArray(count / step + 1)
        val ys = IntArray(count / step + 1)
        var n = 0
        var k = 0
        for (y in 0 until h) for (x in 0 until w step 2) {
            if (b[y * w + x]) {
                if (k++ % step == 0 && n < xs.size) {
                    xs[n] = x; ys[n] = y; n++
                }
            }
        }
        var bestScore = -1.0
        var bestTan = 0.0
        val hist = IntArray(h)
        for (tenth in -30..30) {
            val t = tan(Math.toRadians(tenth / 10.0))
            hist.fill(0)
            for (i in 0 until n) {
                val yy = (ys[i] - xs[i] * t).roundToInt()
                if (yy in 0 until h) hist[yy]++
            }
            var score = 0.0
            for (v in hist) score += v.toDouble() * v
            if (score > bestScore) {
                bestScore = score
                bestTan = t
            }
        }
        if (bestTan == 0.0) return b
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            val s = (x * bestTan).roundToInt()
            for (y in 0 until h) {
                val src = y + s
                if (src in 0 until h) out[y * w + x] = b[src * w + x]
            }
        }
        return out
    }

    private fun horizontalRuns(b: BooleanArray, w: Int, h: Int, minLen: Int): BooleanArray {
        val m = BooleanArray(w * h)
        for (y in 0 until h) {
            val base = y * w
            var x = 0
            while (x < w) {
                if (b[base + x]) {
                    var e = x
                    while (e + 1 < w && b[base + e + 1]) e++
                    if (e - x + 1 >= minLen) for (i in x..e) m[base + i] = true
                    x = e + 1
                } else {
                    x++
                }
            }
        }
        return m
    }

    /** 3행 창(위·아래 1행)에서 선이 있는 열 수 */
    private fun lineProfile(lm: BooleanArray, w: Int, h: Int, y0: Int, y1: Int): IntArray {
        val prof = IntArray(h)
        for (y in max(0, y0) until min(h, y1)) {
            var c = 0
            for (x in 0 until w) {
                if (lm[y * w + x] || (y > 0 && lm[(y - 1) * w + x]) || (y + 1 < h && lm[(y + 1) * w + x])) c++
            }
            prof[y] = c
        }
        return prof
    }

    // ------------------------------------------------------------------ 오선

    private fun findStaves(lm: BooleanArray, w: Int, h: Int): List<Staff> {
        val prof = lineProfile(lm, w, h, 0, h)
        val thr = max(0.13f * w, 60f)
        val centers = ArrayList<Float>()
        val win = 3
        for (y in win until h - win) {
            val v = prof[y]
            if (v <= thr) continue
            var isMax = true
            for (j in y - win..y + win) if (prof[j] > v) { isMax = false; break }
            if (!isMax) continue
            var dupBefore = false
            for (j in y - win until y) if (prof[j] == v) { dupBefore = true; break }
            if (!dupBefore) centers += y.toFloat()
        }
        val staves = ArrayList<Staff>()
        var i = 0
        while (i + 4 < centers.size) {
            val d = (0 until 4).map { centers[i + it + 1] - centers[i + it] }
            val s = d.average().toFloat()
            if (s in 5f..30f && d.max() - d.min() <= max(2.5f, 0.3f * s)) {
                staves += Staff(centers[i], centers[i + 4], s)
                i += 5
            } else {
                i++
            }
        }
        return staves
    }

    /** 다섯 줄 중 4줄 이상에 잉크가 있는 열이 이어지는 범위 (음표로 줄이 끊겨 보여도 견딤) */
    private fun staffExtent(b: BooleanArray, w: Int, h: Int, st: Staff): Pair<Int, Int> {
        val col = IntArray(w)
        for (k in 0 until 5) {
            val r = (st.top + k * st.spacing).roundToInt()
            for (x in 0 until w) {
                var any = false
                for (yy in r - 1..r + 1) if (yy in 0 until h && b[yy * w + x]) { any = true; break }
                if (any) col[x]++
            }
        }
        val win = max((2 * st.spacing).toInt(), 4)
        if (win >= w) return 0 to 0
        val prefix = IntArray(w + 1)
        for (x in 0 until w) prefix[x + 1] = prefix[x] + if (col[x] >= 4) 1 else 0
        var lo = -1
        var hi = -1
        for (x in 0..w - win) {
            if ((prefix[x + win] - prefix[x]).toFloat() / win >= 0.6f) {
                if (lo < 0) lo = x
                hi = x
            }
        }
        return if (lo < 0) 0 to 0 else lo to (hi + win - 1)
    }

    /** 끝세로줄: 오선 줄을 뺀 행마다 오른쪽 끝 잉크가 "얇은선 + 틈 + 굵은선" 인지. 도돌이표(:‖)는 제외 */
    private fun finalBarline(b: BooleanArray, w: Int, h: Int, st: Staff, xe: Int): Boolean {
        val s = st.spacing
        val x0 = max((xe - 3 * s).toInt(), 0)
        val x1 = min((xe + 2.5f * s).toInt(), w)
        if (x1 - x0 < 4) return false
        val lineRows = HashSet<Int>()
        for (k in 0 until 5) {
            val r = (st.top + k * s).roundToInt()
            lineRows += r - 1; lineRows += r; lineRows += r + 1
        }
        val thickMin = max(2.5f, 0.3f * s)
        var rows = 0
        var ok = 0
        var repeat = 0
        val runs = IntArray(64)
        for (y in st.top.roundToInt()..st.bottom.roundToInt()) {
            if (y !in 0 until h || y in lineRows) continue
            rows++
            var nRuns = 0
            var x = x0
            while (x < x1 && nRuns < 31) {
                if (b[y * w + x]) {
                    var e = x
                    while (e + 1 < x1 && b[y * w + e + 1]) e++
                    runs[nRuns * 2] = x; runs[nRuns * 2 + 1] = e
                    nRuns++
                    x = e + 1
                } else {
                    x++
                }
            }
            if (nRuns < 2) continue
            val a0 = runs[(nRuns - 2) * 2]; val a1 = runs[(nRuns - 2) * 2 + 1]
            val b0 = runs[(nRuns - 1) * 2]; val b1 = runs[(nRuns - 1) * 2 + 1]
            val thick = b1 - b0 + 1
            val thin = a1 - a0 + 1
            val gap = b0 - a1 - 1
            if (thick >= thickMin && thin <= 0.75f * thick && gap >= 1 && gap <= 1.2f * s) {
                ok++
                if (nRuns >= 3) {
                    val c0 = runs[(nRuns - 3) * 2]; val c1 = runs[(nRuns - 3) * 2 + 1]
                    if (a0 - c1 - 1 <= 1.2f * s && c1 - c0 + 1 >= 0.3f * s) repeat++
                }
            }
        }
        return rows > 0 && ok.toFloat() / rows >= 0.6f && repeat < 2
    }

    // ------------------------------------------------------------------ 제목

    /** @return [left, top, right, bottom] px 또는 null */
    private fun findTitle(b: BooleanArray, w: Int, h: Int, firstTop: Float?, s: Float): FloatArray? {
        val bottom = if (firstTop != null) (firstTop - 1.5f * s).toInt() else (0.45f * h).toInt()
        val top = (0.015f * h).toInt()
        if (bottom <= top + 10) return null
        val left = (0.03f * w).toInt()
        val right = (0.97f * w).toInt()
        val rw = right - left
        val rh = bottom - top

        // 4-연결 성분 라벨링 (두 번 훑기 + union-find)
        val labels = IntArray(rw * rh)
        val parent = IntArray(rw * rh / 2 + 2)
        var next = 1
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (y in 0 until rh) for (x in 0 until rw) {
            if (!b[(y + top) * w + x + left]) continue
            val up = if (y > 0) labels[(y - 1) * rw + x] else 0
            val lf = if (x > 0) labels[y * rw + x - 1] else 0
            labels[y * rw + x] = when {
                up != 0 && lf != 0 -> {
                    val a = find(up); val c = find(lf)
                    if (a != c) parent[max(a, c)] = min(a, c)
                    min(a, c)
                }
                up != 0 -> up
                lf != 0 -> lf
                else -> {
                    if (next >= parent.size) return null
                    parent[next] = next
                    next++
                }
            }
        }
        val minY = IntArray(next) { Int.MAX_VALUE }
        val maxY = IntArray(next) { -1 }
        val minX = IntArray(next) { Int.MAX_VALUE }
        val maxX = IntArray(next) { -1 }
        val area = IntArray(next)
        for (y in 0 until rh) for (x in 0 until rw) {
            val l = labels[y * rw + x]
            if (l == 0) continue
            val r = find(l)
            if (y < minY[r]) minY[r] = y
            if (y > maxY[r]) maxY[r] = y
            if (x < minX[r]) minX[r] = x
            if (x > maxX[r]) maxX[r] = x
            area[r]++
        }

        // 글자처럼 보이는 성분만
        class Glyph(val y0: Int, val y1: Int, val x0: Int, val x1: Int)
        val glyphs = ArrayList<Glyph>()
        for (r in 1 until next) {
            if (area[r] == 0) continue
            val gh = maxY[r] - minY[r] + 1
            val gw = maxX[r] - minX[r] + 1
            if (gh < 0.9f * s || gh > 9 * s || gw > 12 * s) continue
            val fill = area[r].toFloat() / (gh * gw)
            if (fill < 0.06f || fill > 0.92f) continue
            glyphs += Glyph(minY[r] + top, maxY[r] + top, minX[r] + left, maxX[r] + left)
        }
        glyphs.sortBy { (it.y0 + it.y1) / 2f }

        // 세로로 겹치는 글자끼리 한 줄로
        class Line(var y0: Int, var y1: Int, val glyphs: MutableList<Glyph>)
        val lines = ArrayList<Line>()
        for (g in glyphs) {
            val line = lines.firstOrNull { ln ->
                val ov = min(ln.y1, g.y1) - max(ln.y0, g.y0)
                ov > 0.5f * min(ln.y1 - ln.y0, g.y1 - g.y0)
            }
            if (line != null) {
                line.glyphs += g
                line.y0 = min(line.y0, g.y0)
                line.y1 = max(line.y1, g.y1)
            } else {
                lines += Line(g.y0, g.y1, mutableListOf(g))
            }
        }

        var best: FloatArray? = null
        var bestHeight = 0f
        for (ln in lines) {
            if (ln.glyphs.size < 2) continue
            val heights = ln.glyphs.map { it.y1 - it.y0 + 1 }.sorted()
            val median = heights[heights.size / 2] / s
            val lx0 = ln.glyphs.minOf { it.x0 }
            val lx1 = ln.glyphs.maxOf { it.x1 }
            val cx = (lx0 + lx1) / 2f / w
            if (median >= 2.3f && cx in 0.3f..0.7f && median > bestHeight) {
                bestHeight = median
                best = floatArrayOf(lx0.toFloat(), ln.y0.toFloat(), lx1.toFloat(), ln.y1.toFloat())
            }
        }
        return best
    }

    /** 범위 안에 오선처럼 보이는 행 수 (놓친 보표 위의 가사/머리말을 제목으로 착각하지 않도록) */
    private fun staffLikeRows(lm: BooleanArray, w: Int, h: Int, y0: Int, y1: Int): Int {
        val prof = lineProfile(lm, w, h, y0, y1)
        var n = 0
        for (y in max(0, y0) until min(h, y1)) if (prof[y] > 0.2f * w) n++
        return n
    }
}


package com.scorebook.core.render

/** 빈 오선지(A4) 레이아웃. 렌더러와 PDF 내보내기가 같은 좌표를 쓴다. */
object StaffPaper {
    const val WIDTH = 595f
    const val HEIGHT = 842f
    private const val MARGIN_X = 42f
    private const val MARGIN_TOP = 64f
    private const val LINE_GAP = 7f
    private const val STAFF_COUNT = 12

    /** 각 오선의 (y, x1, x2) 목록 */
    fun lines(): List<Triple<Float, Float, Float>> {
        val staffHeight = LINE_GAP * 4
        val usable = HEIGHT - MARGIN_TOP * 2
        val step = (usable - staffHeight) / (STAFF_COUNT - 1)
        return buildList {
            for (s in 0 until STAFF_COUNT) {
                val top = MARGIN_TOP + s * step
                for (l in 0 until 5) add(Triple(top + l * LINE_GAP, MARGIN_X, WIDTH - MARGIN_X))
            }
        }
    }

    const val LINE_WIDTH = 0.6f
}

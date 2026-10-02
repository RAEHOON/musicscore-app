package com.scorebook.core.render

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.runtime.Immutable
import com.scorebook.core.model.PageCrop
import com.scorebook.core.model.ScorePage

/**
 * 월드 좌표(PDF 포인트) ↔ 화면 표시 좌표 변환.
 *
 * 표시 = (자르기 영역) → (시계방향 회전) → (스케일).
 * 이미지/PDF/필기 모두 같은 행렬을 쓰므로 회전·자르기를 해도 필기가 정확히 따라간다.
 */
@Immutable
data class PageGeometry(
    val worldWidth: Float,
    val worldHeight: Float,
    val rotation: Int,
    val crop: PageCrop,
) {
    val cropLeft get() = crop.left * worldWidth
    val cropTop get() = crop.top * worldHeight
    val cropWidth get() = (crop.right - crop.left) * worldWidth
    val cropHeight get() = (crop.bottom - crop.top) * worldHeight

    private val quarterTurned get() = (rotation / 90) % 2 == 1

    /** 회전/자르기 적용 후 표시 크기(pt) */
    val displayWidth: Float get() = if (quarterTurned) cropHeight else cropWidth
    val displayHeight: Float get() = if (quarterTurned) cropWidth else cropHeight
    val aspectRatio: Float get() = displayWidth / displayHeight

    /** 월드 → 표시 픽셀. 표시 폭 = displayWidth * scale */
    fun worldToDisplay(scale: Float, out: Matrix = Matrix()): Matrix {
        out.setTranslate(-cropLeft, -cropTop)
        when (((rotation % 360) + 360) % 360) {
            90 -> { out.postRotate(90f); out.postTranslate(cropHeight, 0f) }
            180 -> { out.postRotate(180f); out.postTranslate(cropWidth, cropHeight) }
            270 -> { out.postRotate(270f); out.postTranslate(0f, cropWidth) }
        }
        out.postScale(scale, scale)
        return out
    }

    /** 표시 픽셀 폭 [widthPx] 에 맞춘 월드 → 로컬 행렬 */
    fun worldToLocal(widthPx: Float, out: Matrix = Matrix()): Matrix =
        worldToDisplay(widthPx / displayWidth, out)

    /** 표시 비율 영역(0..1) → 월드 사각형 */
    fun displayFractionToWorld(fraction: RectF): RectF {
        val m = worldToDisplay(1f)
        val inv = Matrix().also { m.invert(it) }
        val r = RectF(
            fraction.left * displayWidth, fraction.top * displayHeight,
            fraction.right * displayWidth, fraction.bottom * displayHeight,
        )
        inv.mapRect(r)
        return r
    }

    companion object {
        fun of(page: ScorePage, autoCrop: PageCrop? = null): PageGeometry =
            PageGeometry(page.width, page.height, page.rotation, page.crop ?: autoCrop ?: PageCrop.FULL)
    }
}

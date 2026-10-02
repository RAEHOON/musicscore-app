package com.scorebook.feature.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.max

@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var size = IntSize.Zero

    val zoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    internal fun onSize(s: IntSize) {
        size = s
    }

    /** 핀치 중심(centroid)을 고정한 채 확대 + 이동 */
    internal fun transform(centroid: Offset, zoom: Float, pan: Offset) {
        val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
        val center = Offset(size.width / 2f, size.height / 2f)
        val c = centroid - center
        var newOffset = c - (c - offset) * (newScale / scale) + pan
        val maxX = max(0f, size.width * (newScale - 1) / 2)
        val maxY = max(0f, size.height * (newScale - 1) / 2)
        newOffset = Offset(newOffset.x.coerceIn(-maxX, maxX), newOffset.y.coerceIn(-maxY, maxY))
        scale = newScale
        offset = if (newScale <= 1.001f) Offset.Zero else newOffset
    }

    companion object {
        const val MAX_SCALE = 5f
    }
}

/**
 * 두 손가락 = 확대/이동, 확대된 상태의 한 손가락 = 이동.
 * 확대되지 않은 상태의 한 손가락은 소비하지 않으므로 상위 HorizontalPager 가 페이지를 넘긴다.
 */
fun Modifier.zoomable(state: ZoomState, enabled: Boolean = true): Modifier =
    this
        .pointerInput(state, enabled) {
            if (!enabled) return@pointerInput
            state.onSize(size)
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    state.onSize(size)
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2 || state.zoomed) {
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) {
                            state.transform(event.calculateCentroid(useCurrent = true), zoom, pan)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .graphicsLayer {
            scaleX = state.scale
            scaleY = state.scale
            translationX = state.offset.x
            translationY = state.offset.y
        }

package com.scorebook.feature.viewer

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.scorebook.core.input.PageRegion
import com.scorebook.core.render.PageGeometry

/**
 * 화면에 보이는 페이지들의 위치를 추적한다.
 * LayoutCoordinates 는 살아있는 객체라서 질의 시점의 확대/스크롤 상태가 그대로 반영된다.
 * → 펜 입력 라우팅(InputHub), 탭 위치 → 월드 좌표 변환에 사용.
 */
class PageRegistry {

    private class Entry(
        val pageId: Long,
        var geometry: PageGeometry,
        var clipTop: Float,
        var clipBottom: Float,
        var coords: LayoutCoordinates? = null,
    )

    private val entries = LinkedHashMap<Any, Entry>()
    private val blockers = LinkedHashMap<Any, LayoutCoordinates>()

    fun update(key: Any, pageId: Long, geometry: PageGeometry, clipTop: Float, clipBottom: Float, coords: LayoutCoordinates) {
        val e = entries.getOrPut(key) { Entry(pageId, geometry, clipTop, clipBottom) }
        e.geometry = geometry
        e.clipTop = clipTop
        e.clipBottom = clipBottom
        e.coords = coords
    }

    fun remove(key: Any) {
        entries.remove(key)
    }

    fun setBlocker(key: Any, coords: LayoutCoordinates?) {
        if (coords == null) blockers.remove(key) else blockers[key] = coords
    }

    fun isBlocked(x: Float, y: Float): Boolean = blockers.values.any { c ->
        c.isAttached && c.boundsInWindow().contains(Offset(x, y))
    }

    fun regionAt(x: Float, y: Float): PageRegion? {
        if (isBlocked(x, y)) return null
        // 나중에 그려진(위에 있는) 항목 우선
        for (e in entries.values.reversed()) {
            val region = regionOf(e) ?: continue
            if (region.windowRect.contains(x, y)) return region
        }
        return null
    }

    fun region(pageId: Long): PageRegion? =
        entries.values.reversed().firstNotNullOfOrNull { e -> if (e.pageId == pageId) regionOf(e) else null }

    private fun regionOf(e: Entry): PageRegion? {
        val c = e.coords ?: return null
        if (!c.isAttached) return null
        val w = c.size.width.toFloat()
        val h = c.size.height.toFloat()
        if (w <= 0f || h <= 0f) return null

        // 로컬 → 윈도우 (확대 graphicsLayer 포함) 를 3점으로 구한다
        val p0 = c.localToWindow(Offset.Zero)
        val p1 = c.localToWindow(Offset(w, 0f))
        val p2 = c.localToWindow(Offset(0f, h))
        val localToWindow = Matrix().apply {
            setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, 0f, h), 0, floatArrayOf(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y), 0, 3)
        }
        val worldToWindow = e.geometry.worldToLocal(w).apply { postConcat(localToWindow) }

        val clipLocal = RectF(0f, e.clipTop * h, w, e.clipBottom * h)
        localToWindow.mapRect(clipLocal)
        val bounds = c.boundsInWindow()
        val visible = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
        if (!visible.intersect(clipLocal)) return null
        return PageRegion(e.pageId, visible, worldToWindow)
    }
}

/** 페이지 컴포저블에 붙여 위치를 등록 */
@Composable
fun Modifier.registerPage(
    registry: PageRegistry,
    pageId: Long,
    geometry: PageGeometry,
    clipTop: Float = 0f,
    clipBottom: Float = 1f,
): Modifier {
    val key = remember { Any() }
    DisposableEffect(registry, key) { onDispose { registry.remove(key) } }
    return this.onGloballyPositioned { registry.update(key, pageId, geometry, clipTop, clipBottom, it) }
}

/** 툴바/패널 위에서는 펜이 필기하지 않도록 차단 영역 등록 */
@Composable
fun Modifier.inkBlocker(registry: PageRegistry): Modifier {
    val key = remember { Any() }
    DisposableEffect(registry, key) { onDispose { registry.setBlocker(key, null) } }
    return this.onGloballyPositioned { registry.setBlocker(key, it) }
}

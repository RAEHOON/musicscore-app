package com.scorebook.feature.viewer

import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.scorebook.core.ink.ItemRenderer
import com.scorebook.core.model.AnnotationItem
import com.scorebook.core.model.ColorMode
import com.scorebook.core.model.InkStroke
import com.scorebook.core.model.ScoreLink
import com.scorebook.core.model.ScorePage
import com.scorebook.core.render.PageGeometry
import com.scorebook.core.render.PageRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 모든 페이지가 공유하는 의존성 */
@Stable
class PageEnvironment(
    val renderer: PageRenderer,
    val strokeRenderer: CanvasStrokeRenderer,
    val registry: PageRegistry,
    val overlay: GestureOverlay,
    val density: Float,
)

/** 페이지 표시 옵션 (불변 → 변경 시에만 재구성) */
@Immutable
data class PageDecor(
    val colorMode: ColorMode = ColorMode.NORMAL,
    val showLinks: Boolean = true,
    val linkMode: Boolean = false,
    val selection: Selection? = null,
    val pageNumbers: Map<Long, Int> = emptyMap(),
)

private val invertMatrix = floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
)
private val nightFilter = ColorFilter.colorMatrix(ColorMatrix(invertMatrix))
private val sepiaFilter = ColorFilter.tint(Color(0xFFF3E6C8), BlendMode.Multiply)

fun ColorMode.pageFilter(): ColorFilter? = when (this) {
    ColorMode.NORMAL -> null
    ColorMode.NIGHT -> nightFilter
    ColorMode.SEPIA -> sepiaFilter
}

fun ColorMode.background(): Color = when (this) {
    ColorMode.NORMAL -> Color.White
    ColorMode.NIGHT -> Color.Black
    ColorMode.SEPIA -> Color(0xFFF3E6C8)
}

@Composable
fun PageView(
    page: ScorePage,
    geometry: PageGeometry,
    strokes: List<InkStroke>,
    items: List<AnnotationItem>,
    links: List<ScoreLink>,
    env: PageEnvironment,
    decor: PageDecor,
    modifier: Modifier = Modifier,
    clipTop: Float = 0f,
    clipBottom: Float = 1f,
    zoomState: ZoomState? = null,
    onWidthKnown: (Int) -> Unit = {},
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // 기본 비트맵: 캐시에 있으면 즉시, 없으면 백그라운드 렌더 후 교체(이전 그림은 유지해 깜빡임 방지)
    var shown by remember(page.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(page.id, geometry, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        val cached = env.renderer.cached(page, geometry, widthPx)
        shown = (cached ?: env.renderer.render(page, geometry, widthPx))?.asImageBitmap() ?: shown
    }

    // 확대 시 보이는 영역만 고해상도로 다시 그림
    var detail by remember(page.id, geometry) { mutableStateOf<Pair<ImageBitmap, Rect>?>(null) }
    if (zoomState != null) {
        LaunchedEffect(page.id, geometry, widthPx) {
            snapshotFlow { zoomState.scale to zoomState.offset }.collectLatest { (scale, _) ->
                if (scale < 1.3f || widthPx <= 0) {
                    detail = null
                    return@collectLatest
                }
                delay(160)
                val c = coords?.takeIf { it.isAttached } ?: return@collectLatest
                val b = c.boundsInWindow()
                val tl = c.windowToLocal(b.topLeft)
                val br = c.windowToLocal(b.bottomRight)
                val local = Rect(
                    max(0f, min(tl.x, br.x)), max(0f, min(tl.y, br.y)),
                    min(c.size.width.toFloat(), max(tl.x, br.x)), min(c.size.height.toFloat(), max(tl.y, br.y)),
                )
                if (local.width <= 1f || local.height <= 1f) return@collectLatest
                val region = RectF(local.left * scale, local.top * scale, local.right * scale, local.bottom * scale)
                val bmp = env.renderer.renderRegion(page, geometry, c.size.width * scale, region) ?: return@collectLatest
                detail = bmp.asImageBitmap() to local
            }
        }
    }

    val filter = decor.colorMode.pageFilter()
    Box(
        modifier
            .aspectRatio(geometry.aspectRatio)
            .onSizeChanged {
                if (it.width != widthPx) {
                    widthPx = it.width
                    onWidthKnown(it.width)
                }
            }
            .onGloballyPositioned { coords = it }
            .registerPage(env.registry, page.id, geometry, clipTop, clipBottom)
            .clipVertical(clipTop, clipBottom),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(decor.colorMode.background())
            shown?.let {
                drawImage(
                    it,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    colorFilter = filter,
                )
            }
            detail?.let { (bmp, r) ->
                drawImage(
                    bmp,
                    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                    colorFilter = filter,
                )
            }
        }
        InkLayer(geometry, strokes, items, env, decor, page.id)
        OverlayLayer(geometry, links, env, decor, page.id)
    }
}

private fun Modifier.clipVertical(top: Float, bottom: Float): Modifier =
    if (top <= 0f && bottom >= 1f) this else drawWithContent {
        clipRect(top = size.height * top, bottom = size.height * bottom) { this@drawWithContent.drawContent() }
    }

@Composable
private fun InkLayer(
    geometry: PageGeometry,
    strokes: List<InkStroke>,
    items: List<AnnotationItem>,
    env: PageEnvironment,
    decor: PageDecor,
    pageId: Long,
) {
    val night = decor.colorMode == ColorMode.NIGHT
    val invertPaint = remember { Paint().apply { colorFilter = ColorMatrixColorFilter(invertMatrix) } }
    val matrix = remember { android.graphics.Matrix() }
    val selection = decor.selection?.takeIf { it.pageId == pageId }
    Canvas(Modifier.fillMaxSize()) {
        if (strokes.isEmpty() && items.isEmpty()) return@Canvas
        val overlay = env.overlay
        val moving = selection != null && overlay.kind == GestureOverlay.Kind.MOVE && overlay.pageId == pageId
        val delta = if (moving) overlay.moveDelta else Offset.Zero
        geometry.worldToLocal(size.width, matrix)
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            if (night) nc.saveLayer(null, invertPaint)
            nc.save()
            nc.concat(matrix)
            for (s in strokes) {
                if (moving && s.id in selection.strokeIds) {
                    nc.save()
                    nc.translate(delta.x, delta.y)
                    env.strokeRenderer.draw(nc, s.stroke, matrix)
                    nc.restore()
                } else {
                    env.strokeRenderer.draw(nc, s.stroke, matrix)
                }
            }
            for (item in items) {
                if (moving && item.id in selection.itemIds) {
                    nc.save()
                    nc.translate(delta.x, delta.y)
                    ItemRenderer.draw(nc, item)
                    nc.restore()
                } else {
                    ItemRenderer.draw(nc, item)
                }
            }
            nc.restore()
            if (night) nc.restore()
        }
    }
}

@Composable
private fun OverlayLayer(
    geometry: PageGeometry,
    links: List<ScoreLink>,
    env: PageEnvironment,
    decor: PageDecor,
    pageId: Long,
) {
    val linkFill = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332196F3; style = Paint.Style.FILL } }
    val linkStroke = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC2196F3.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f } }
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1565C0.toInt(); textSize = 9f; isFakeBoldText = true } }
    val dashed = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEF8354.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f
            pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
        }
    }
    val previewPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA2B2D42.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f; strokeCap = Paint.Cap.ROUND }
    }
    val eraserFill = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33EF5350; style = Paint.Style.FILL } }
    val eraserEdge = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCEF5350.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.8f }
    }
    val matrix = remember { android.graphics.Matrix() }
    val path = remember { Path() }
    val pageLinks = remember(links, pageId) { links.filter { it.fromPageId == pageId } }

    Canvas(Modifier.fillMaxSize()) {
        val overlay = env.overlay
        val active = overlay.pageId == pageId
        val selection = decor.selection?.takeIf { it.pageId == pageId }
        if (!active && selection == null && (pageLinks.isEmpty() || !(decor.showLinks || decor.linkMode))) return@Canvas
        geometry.worldToLocal(size.width, matrix)
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.save()
            nc.concat(matrix)
            if (decor.showLinks || decor.linkMode) {
                for (link in pageLinks) {
                    val r = RectF(link.rect.left, link.rect.top, link.rect.right, link.rect.bottom)
                    nc.drawRoundRect(r, 4f, 4f, linkFill)
                    nc.drawRoundRect(r, 4f, 4f, linkStroke)
                    val target = decor.pageNumbers[link.toPageId]?.let { "→ ${it}쪽" }.orEmpty()
                    val label = if (link.label.isNotBlank()) "${link.label} $target" else target
                    if (label.isNotBlank()) nc.drawText(label, r.left + 2f, r.top - 3f, labelPaint)
                }
            }
            if (selection != null) {
                val d = if (active && overlay.kind == GestureOverlay.Kind.MOVE) overlay.moveDelta else Offset.Zero
                val b = selection.bounds
                nc.drawRect(b.left - 4 + d.x, b.top - 4 + d.y, b.right + 4 + d.x, b.bottom + 4 + d.y, dashed)
            }
            if (active) {
                when (overlay.kind) {
                    GestureOverlay.Kind.LASSO -> if (overlay.lasso.size > 1) {
                        path.reset()
                        overlay.lasso.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                        nc.drawPath(path, dashed)
                    }
                    GestureOverlay.Kind.SHAPE, GestureOverlay.Kind.LINK -> {
                        val s = overlay.start
                        val e = overlay.end
                        if (s != null && e != null) {
                            if (overlay.kind == GestureOverlay.Kind.LINK) {
                                val r = RectF(min(s.x, e.x), min(s.y, e.y), max(s.x, e.x), max(s.y, e.y))
                                nc.drawRect(r, linkFill)
                                nc.drawRect(r, linkStroke)
                            } else {
                                nc.drawLine(s.x, s.y, e.x, e.y, previewPaint)
                                nc.drawRect(min(s.x, e.x), min(s.y, e.y), max(s.x, e.x), max(s.y, e.y), dashed)
                            }
                        }
                    }
                    GestureOverlay.Kind.ERASER -> overlay.eraser?.let {
                        // 네모난 지우개 커서: 실제로 지워지는 범위와 같은 크기
                        val h = overlay.eraserSize / 2f
                        nc.drawRect(it.x - h, it.y - h, it.x + h, it.y + h, eraserFill)
                        nc.drawRect(it.x - h, it.y - h, it.x + h, it.y + h, eraserEdge)
                    }
                    else -> Unit
                }
            }
            nc.restore()
        }
    }
}

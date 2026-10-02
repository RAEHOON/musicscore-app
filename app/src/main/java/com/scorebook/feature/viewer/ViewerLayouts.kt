package com.scorebook.feature.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.scorebook.core.model.PageCrop
import com.scorebook.core.model.ScorePage
import com.scorebook.core.render.PageGeometry
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlin.math.abs

/** 여백 자동 자르기를 반영한 페이지 형상 */
@Composable
fun rememberGeometry(page: ScorePage, autoCrop: Boolean, env: PageEnvironment): PageGeometry {
    val crop by produceState<PageCrop?>(initialValue = null, page.id, page.sourcePath, page.sourceIndex, autoCrop) {
        value = if (autoCrop && page.crop == null) env.renderer.contentBounds(page) else null
    }
    return remember(page, crop) { PageGeometry.of(page, crop) }
}

/**
 * 저장된 획 + 아직 상태에 반영되지 않은 방금 그린 획(펜을 뗀 직후 한 프레임) 을 합친다.
 * 키가 같으면 같은 리스트를 돌려줘 불필요한 다시 그리기를 막는다.
 */
@Composable
private fun mergedStrokes(
    state: ViewerState,
    pageId: Long,
    visible: Set<Long>,
    pending: List<com.scorebook.core.model.InkStroke>,
): List<com.scorebook.core.model.InkStroke> {
    val base = state.strokes[pageId]
    return remember(base, visible, pending) {
        val saved = base.orEmpty()
        val extra = if (pending.isEmpty()) pending else {
            val ids = saved.mapTo(HashSet()) { it.id }
            pending.filterNot { it.id in ids }
        }
        (saved + extra).filter { it.layerId in visible }
    }
}

/** 부모 영역 안에 비율을 유지하며 최대로 맞춤 */
@Composable
fun FitBox(aspectRatio: Float, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val containerRatio = if (constraints.maxHeight == 0) 1f else constraints.maxWidth.toFloat() / constraints.maxHeight
        content(Modifier.aspectRatio(aspectRatio, matchHeightConstraintsFirst = containerRatio > aspectRatio))
    }
}

@Composable
fun PageSlot(
    index: Int,
    state: ViewerState,
    env: PageEnvironment,
    decor: PageDecor,
    modifier: Modifier,
    clipTop: Float = 0f,
    clipBottom: Float = 1f,
    zoomState: ZoomState? = null,
    pendingFor: (Long) -> List<com.scorebook.core.model.InkStroke> = { emptyList() },
    onWidthKnown: (Int) -> Unit = {},
) {
    val page = state.pages.getOrNull(index) ?: return
    val geometry = rememberGeometry(page, state.settings.autoCropMargins, env)
    val visible = state.visibleLayerIds
    val strokes = mergedStrokes(state, page.id, visible, pendingFor(page.id))
    val items = remember(state.items[page.id], visible) { state.items[page.id].orEmpty().filter { it.layerId in visible } }
    FitBox(geometry.aspectRatio, modifier) { fit ->
        PageView(
            page = page,
            geometry = geometry,
            strokes = strokes,
            items = items,
            links = state.links,
            env = env,
            decor = decor,
            modifier = fit,
            clipTop = clipTop,
            clipBottom = clipBottom,
            zoomState = zoomState,
            onWidthKnown = onWidthKnown,
        )
    }
}

/** 한 페이지 / 두 페이지 모드: 좌우로 부드럽게 넘기는 페이저 */
@Composable
fun PagedViewer(
    state: ViewerState,
    twoPage: Boolean,
    env: PageEnvironment,
    decor: PageDecor,
    pendingFor: (Long) -> List<com.scorebook.core.model.InkStroke>,
    onIntent: (ViewerIntent) -> Unit,
    onWidthKnown: (Int) -> Unit,
) {
    val per = if (twoPage) 2 else 1
    val count = (state.pages.size + per - 1) / per
    val pagerState = rememberPagerState(initialPage = state.currentPage / per) { count }
    val animate = state.settings.pageTurnAnimation
    val currentOnIntent by rememberUpdatedState(onIntent)

    LaunchedEffect(state.currentPage, per) {
        val target = state.currentPage / per
        if (target != pagerState.currentPage && target in 0 until count) {
            if (animate && abs(target - pagerState.currentPage) == 1) pagerState.animateScrollToPage(target)
            else pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(pagerState, per) {
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().drop(1).collect {
            currentOnIntent(ViewerIntent.PageSettled(it * per))
        }
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        key = { it },
        modifier = Modifier.fillMaxSize(),
    ) { spread ->
        val zoom = remember { ZoomState() }
        Box(Modifier.fillMaxSize().zoomable(zoom), contentAlignment = Alignment.Center) {
            if (twoPage) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    PageSlot(spread * 2, state, env, decor, Modifier.weight(1f).fillMaxHeight(), zoomState = zoom, pendingFor = pendingFor, onWidthKnown = onWidthKnown)
                    if (spread * 2 + 1 < state.pages.size) {
                        PageSlot(spread * 2 + 1, state, env, decor, Modifier.weight(1f).fillMaxHeight(), zoomState = zoom, pendingFor = pendingFor)
                    } else {
                        Box(Modifier.weight(1f))
                    }
                }
            } else {
                PageSlot(spread, state, env, decor, Modifier.fillMaxSize(), zoomState = zoom, pendingFor = pendingFor, onWidthKnown = onWidthKnown)
            }
        }
    }
}

/**
 * 반 페이지 넘김: 다음 페이지의 위쪽 절반이 먼저 바뀌고, 아래쪽은 현재 페이지를 끝까지 보여준다.
 * 연주하면서 위쪽을 미리 읽을 수 있어 넘기는 타이밍에 쫓기지 않는다.
 */
@Composable
fun HalfPageViewer(
    state: ViewerState,
    env: PageEnvironment,
    decor: PageDecor,
    pendingFor: (Long) -> List<com.scorebook.core.model.InkStroke>,
    onWidthKnown: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        val cur = state.currentPage
        if (state.halfStep && cur + 1 < state.pages.size) {
            PageSlot(cur, state, env, decor, Modifier.fillMaxSize(), clipTop = 0.5f, pendingFor = pendingFor, onWidthKnown = onWidthKnown)
            PageSlot(cur + 1, state, env, decor, Modifier.fillMaxSize(), clipBottom = 0.5f, pendingFor = pendingFor)
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height / 2
                drawLine(Color(0xFFEF8354), Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx())
            }
        } else {
            PageSlot(cur, state, env, decor, Modifier.fillMaxSize(), pendingFor = pendingFor, onWidthKnown = onWidthKnown)
        }
    }
}

/** 세로 스크롤 모드(+자동 스크롤) */
@Composable
fun VerticalViewer(
    state: ViewerState,
    env: PageEnvironment,
    decor: PageDecor,
    pendingFor: (Long) -> List<com.scorebook.core.model.InkStroke>,
    onIntent: (ViewerIntent) -> Unit,
    onWidthKnown: (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPage)
    val currentOnIntent by rememberUpdatedState(onIntent)

    LaunchedEffect(state.currentPage) {
        if (listState.firstVisibleItemIndex != state.currentPage && !listState.isScrollInProgress) {
            listState.animateScrollToItem(state.currentPage)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().drop(1).collect {
            currentOnIntent(ViewerIntent.PageSettled(it))
        }
    }
    // 자동 스크롤: 초당 autoScrollSpeed dp
    val speed = state.settings.autoScrollSpeed
    LaunchedEffect(state.autoTurnActive, speed) {
        if (!state.autoTurnActive) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            val now = withFrameNanos { it }
            if (last != 0L) {
                val dt = (now - last) / 1_000_000_000f
                listState.scrollBy(speed * dt * env.density)
            }
            last = now
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(state.pages, key = { _, p -> p.id }) { index, page ->
            val geometry = rememberGeometry(page, state.settings.autoCropMargins, env)
            val visible = state.visibleLayerIds
            val strokes = mergedStrokes(state, page.id, visible, pendingFor(page.id))
            val items = state.items[page.id].orEmpty().filter { it.layerId in visible }
            PageView(
                page = page,
                geometry = geometry,
                strokes = strokes,
                items = items,
                links = state.links,
                env = env,
                decor = decor,
                modifier = Modifier.fillMaxWidth(),
                onWidthKnown = if (index == 0) onWidthKnown else ({}),
            )
        }
        item { Box(Modifier.fillMaxWidth().height(200.dp)) }
    }
}

package com.scorebook.feature.viewer

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorebook.core.face.FaceGestureController
import com.scorebook.core.ink.InkBrushes
import com.scorebook.core.input.FinishedInk
import com.scorebook.core.input.GesturePhase
import com.scorebook.core.input.GestureTool
import com.scorebook.core.input.InkTarget
import com.scorebook.core.input.InkToolSpec
import com.scorebook.core.input.PageRegion
import com.scorebook.core.input.ToolGesture
import com.scorebook.core.model.BrushKind
import com.scorebook.core.model.ColorMode
import com.scorebook.core.model.EraserMode
import com.scorebook.core.model.InkStroke
import com.scorebook.core.model.ViewMode
import com.scorebook.core.model.WorldRect
import com.scorebook.core.render.PageGeometry
import com.scorebook.feature.practicetools.PracticeToolsPanel
import kotlinx.coroutines.launch
import java.util.UUID

private val NEXT_KEYS = setOf(
    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_N,
)
private val PREV_KEYS = setOf(
    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_P,
)

@Composable
fun ViewerScreen(
    onBack: () -> Unit,
    onOpenScore: (scoreId: Long, setlistId: Long, index: Int) -> Unit,
    onOpenPageEditor: () -> Unit,
    viewModel: ViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latestState = rememberUpdatedState(state)
    val onIntent: (ViewerIntent) -> Unit = remember(viewModel) { { viewModel.onIntent(it) } }
    val density = LocalDensity.current.density
    val env = remember(viewModel) {
        PageEnvironment(viewModel.renderer, CanvasStrokeRenderer.create(), PageRegistry(), GestureOverlay(), density)
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // ---------- 펜을 뗀 직후 한 프레임 동안 보여줄 획 (깜빡임 방지 핸드오프)
    val pending = remember { mutableStateListOf<InkStroke>() }
    LaunchedEffect(state.strokes) {
        if (pending.isNotEmpty()) pending.removeAll { p -> state.strokes[p.pageId]?.any { it.id == p.id } == true }
    }
    val pendingFor: (Long) -> List<InkStroke> = remember {
        { pageId -> if (pending.isEmpty()) emptyList() else pending.filter { it.pageId == pageId } }
    }

    // ---------- 펜 입력 연결
    val inkTarget = remember(viewModel) {
        ViewerInkTarget(latestState::value, env, onIntent) { inks -> pending.addAll(inks) }
    }
    DisposableEffect(inkTarget) {
        val hub = viewModel.inputHub
        hub.target = inkTarget
        onDispose { if (hub.target === inkTarget) hub.target = null }
    }

    // ---------- 블루투스 페달 / 키보드
    DisposableEffect(viewModel) {
        val hub = viewModel.inputHub
        val handler: (KeyEvent) -> Boolean = handler@{ e ->
            val s = latestState.value
            if (s.anyDialogOpen || s.loading || TextFieldFocus.active) return@handler false
            val code = e.keyCode
            val volume = s.settings.volumeKeysTurnPages
            val next = code in NEXT_KEYS || (volume && code == KeyEvent.KEYCODE_VOLUME_DOWN)
            val prev = code in PREV_KEYS || (volume && code == KeyEvent.KEYCODE_VOLUME_UP)
            if (!next && !prev) return@handler false
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                onIntent(if (next) ViewerIntent.NextPage else ViewerIntent.PreviousPage)
            }
            true
        }
        hub.keyHandler = handler
        onDispose { if (hub.keyHandler === handler) hub.keyHandler = null }
    }

    // ---------- 화면 방향, 수명주기, 화면 켜짐 유지, 몰입 모드
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(landscape) { onIntent(ViewerIntent.OrientationChanged(landscape)) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { onIntent(ViewerIntent.LifecycleStart) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onIntent(ViewerIntent.LifecycleStop) }

    val view = LocalView.current
    val activity = LocalActivity.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val immersive = !state.chromeVisible || state.performanceMode
    DisposableEffect(immersive, activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller?.hide(WindowInsetsCompat.Type.systemBars()) else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // ---------- 미리 렌더링 (다음 페이지가 이미 준비되어 있도록)
    var pageWidthPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.currentPage, pageWidthPx, state.pages, state.settings.autoCropMargins) {
        if (pageWidthPx <= 0) return@LaunchedEffect
        val step = if (state.effectiveMode == ViewMode.TWO_PAGE) 2 else 1
        for (offset in listOf(step, step + 1, 2 * step, -step)) {
            val page = state.pages.getOrNull(state.currentPage + offset) ?: continue
            val crop = if (state.settings.autoCropMargins && page.crop == null) env.renderer.contentBounds(page) else null
            env.renderer.render(page, PageGeometry.of(page, crop), pageWidthPx)
        }
    }

    // ---------- 얼굴 제스처
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val face = remember { FaceGestureController(context.applicationContext) }
    var cameraGrantedTick by remember { mutableIntStateOf(0) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraGrantedTick++
        } else {
            onIntent(ViewerIntent.ToggleFaceGesture)
            scope.launch { snackbar.showSnackbar("얼굴 제스처를 쓰려면 카메라 권한이 필요합니다") }
        }
    }
    LaunchedEffect(state.faceGestureActive, state.settings.faceGestureMode, state.settings.faceGestureSensitivity, cameraGrantedTick) {
        if (state.faceGestureActive) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                face.start(
                    lifecycleOwner, state.settings.faceGestureMode, state.settings.faceGestureSensitivity,
                    onNext = { onIntent(ViewerIntent.NextPage) },
                    onPrevious = { onIntent(ViewerIntent.PreviousPage) },
                )
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        } else {
            face.stop()
        }
    }
    DisposableEffect(face) { onDispose { face.stop() } }
    val faceStatus by face.status.collectAsStateWithLifecycle()

    // ---------- 1회성 이펙트
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) onIntent(ViewerIntent.ExportToUri(uri))
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ViewerEffect.Message -> launch { snackbar.showSnackbar(effect.text) }
                is ViewerEffect.CreateDocument -> createDocument.launch(effect.suggestedName)
                is ViewerEffect.OpenScore -> onOpenScore(effect.scoreId, effect.setlistId, effect.index)
                ViewerEffect.OpenPageEditor -> onOpenPageEditor()
                ViewerEffect.Close -> onBack()
            }
        }
    }

    BackHandler(enabled = state.panel != null || state.selection != null || state.linkMode || state.annotating) {
        when {
            state.panel != null -> onIntent(ViewerIntent.OpenPanel(null))
            state.selection != null -> onIntent(ViewerIntent.ClearSelection)
            state.linkMode -> onIntent(ViewerIntent.ToggleLinkMode)
            else -> onIntent(ViewerIntent.ToggleAnnotating)
        }
    }

    val decor = remember(state.colorMode, state.showLinks, state.linkMode, state.selection, state.pages) {
        PageDecor(
            colorMode = state.colorMode,
            showLinks = state.showLinks,
            linkMode = state.linkMode,
            selection = state.selection,
            pageNumbers = state.pages.mapIndexed { i, p -> p.id to i + 1 }.toMap(),
        )
    }
    val surround = when (state.colorMode) {
        ColorMode.NIGHT -> Color.Black
        ColorMode.SEPIA -> Color(0xFF3B352C)
        ColorMode.NORMAL -> Color(0xFF2F3033)
    }

    Box(Modifier.fillMaxSize().background(surround)) {
        // ---------------- 악보
        var containerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { containerCoords = it }
                .pointerInput(env) {
                    detectTapGestures(onTap = { offset ->
                        val c = containerCoords ?: return@detectTapGestures
                        val win = c.localToWindow(offset)
                        val region: PageRegion? = env.registry.regionAt(win.x, win.y)
                        val world = region?.windowToWorld(win.x, win.y)
                        onIntent(
                            ViewerIntent.Tap(
                                pageId = region?.pageId,
                                worldX = world?.get(0) ?: 0f,
                                worldY = world?.get(1) ?: 0f,
                                screenFractionX = offset.x / c.size.width.coerceAtLeast(1),
                            )
                        )
                    })
                },
        ) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(state.error!!, color = Color.White, modifier = Modifier.align(Alignment.Center))
                state.pages.isEmpty() -> Text("페이지가 없습니다", color = Color.White, modifier = Modifier.align(Alignment.Center))
                else -> when (state.effectiveMode) {
                    ViewMode.SINGLE -> PagedViewer(state, false, env, decor, pendingFor, onIntent) { pageWidthPx = it }
                    ViewMode.TWO_PAGE -> PagedViewer(state, true, env, decor, pendingFor, onIntent) { pageWidthPx = it }
                    ViewMode.HALF -> HalfPageViewer(state, env, decor, pendingFor) { pageWidthPx = it }
                    ViewMode.VERTICAL -> VerticalViewer(state, env, decor, pendingFor, onIntent) { pageWidthPx = it }
                }
            }
        }

        // ---------------- 상단 크롬 + 필기 툴바
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(
                visible = state.chromeVisible && !state.performanceMode,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                ViewerTopBar(state, env.registry, onIntent, onBack, onOpenPageEditor)
            }
            val toolbarTopPadding = if (state.chromeVisible) Modifier.padding(top = 8.dp) else Modifier.statusBarsPadding().padding(top = 8.dp)
            AnimatedVisibility(visible = state.annotating && !state.performanceMode, enter = fadeIn(), exit = fadeOut()) {
                AnnotationToolbar(state, env.registry, onIntent, toolbarTopPadding.padding(horizontal = 12.dp))
            }
            if (state.linkMode) {
                LinkModeBanner(env.registry, onIntent, toolbarTopPadding)
            }
        }

        // ---------------- 하단: 선택 동작, 링크 복귀, 상태 칩, 스크러버
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.selection != null) {
                SelectionActions(env.registry, onIntent, Modifier.padding(bottom = 12.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.linkReturnPage?.let { page ->
                    LinkReturnChip(
                        page = page,
                        registry = env.registry,
                        onReturn = { onIntent(ViewerIntent.ReturnFromLink) },
                        onDismiss = { onIntent(ViewerIntent.DismissLinkReturn) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.autoTurnActive) {
                    StatusChip(Icons.Default.Timer, "자동 넘김 중", env.registry) { onIntent(ViewerIntent.ToggleAutoTurn) }
                }
                if (state.faceGestureActive) {
                    val label = when (faceStatus) {
                        FaceGestureController.Status.TRACKING -> "얼굴 인식 중"
                        FaceGestureController.Status.SEARCHING -> "얼굴을 찾는 중"
                        FaceGestureController.Status.OFF -> "카메라 준비 중"
                    }
                    StatusChip(Icons.Default.Face, label, env.registry) { onIntent(ViewerIntent.ToggleFaceGesture) }
                }
            }
            AnimatedVisibility(
                visible = state.chromeVisible && !state.performanceMode,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                BottomScrubber(state, env.registry, onIntent)
            }
        }

        if (state.performanceMode) {
            PerformanceLock(
                env.registry,
                onUnlock = { onIntent(ViewerIntent.TogglePerformanceMode) },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            )
        }

        // ---------------- 사이드 패널
        val panelOpen = state.panel != null
        if (panelOpen) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onIntent(ViewerIntent.OpenPanel(null)) },
            )
        }
        AnimatedVisibility(
            visible = state.panel == ViewerPanel.PAGES,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            PagesPanel(state, env.renderer, onIntent)
        }
        AnimatedVisibility(
            visible = state.panel == ViewerPanel.TOOLS,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            PracticeToolsPanel(
                onClose = { onIntent(ViewerIntent.OpenPanel(null)) },
                onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
            )
        }

        // ---------------- PDF 저장 진행
        state.exportProgress?.let { progress ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                Card {
                    Column(Modifier.padding(24.dp).width(280.dp)) {
                        Text("PDF로 저장하는 중…", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
    }

    ViewerDialogs(state, env.renderer, onIntent)
}

@Composable
private fun LinkModeBanner(registry: PageRegistry, onIntent: (ViewerIntent) -> Unit, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFE3F2FD),
        shadowElevation = 6.dp,
        modifier = modifier.padding(horizontal = 12.dp).inkBlocker(registry),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Icon(Icons.Default.Link, null, tint = Color(0xFF1565C0))
            Text(
                "도돌이표·D.S.·Coda 위치를 드래그해 링크를 만들고 이동할 페이지를 고르세요. 기존 링크를 탭하면 삭제합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF0D47A1),
                modifier = Modifier.padding(horizontal = 12.dp).weight(1f, fill = false),
            )
            IconButton(onClick = { onIntent(ViewerIntent.ToggleLinkMode) }) { Icon(Icons.Default.Close, "링크 편집 종료") }
        }
    }
}

/** 링크로 이동한 뒤 "돌아가기" — 오른쪽 X 로 돌아가지 않고 닫을 수 있다 */
@Composable
private fun LinkReturnChip(page: Int, registry: PageRegistry, onReturn: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
        modifier = Modifier.inkBlocker(registry),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onReturn).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.padding(end = 6.dp))
                Text("${page + 1}쪽으로 돌아가기", style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "돌아가기 닫기") }
        }
    }
}

@Composable
private fun StatusChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, registry: PageRegistry, onStop: () -> Unit) {
    AssistChip(
        onClick = onStop,
        label = { Text(text) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = { Icon(Icons.Default.Close, "끄기") },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.inkBlocker(registry),
    )
}

/**
 * InputHub ↔ 뷰어 연결. 펜이 닿는 순간의 도구를 정하고, 도구 제스처를 미리보기/Intent 로 바꾼다.
 */
private class ViewerInkTarget(
    private val state: () -> ViewerState,
    private val env: PageEnvironment,
    private val onIntent: (ViewerIntent) -> Unit,
    private val onPending: (List<InkStroke>) -> Unit,
) : InkTarget {

    private var lastErase: Offset? = null

    override fun isInkEnabled(): Boolean = state().inkEnabled

    override fun fingerDraws(): Boolean = state().let { (it.annotating && it.fingerDrawing) || it.linkMode }

    override fun palmRejection(): Boolean = state().settings.palmRejection

    override fun regionAt(x: Float, y: Float): PageRegion? = env.registry.regionAt(x, y)

    override fun toolFor(pageId: Long, worldX: Float, worldY: Float, eraserTip: Boolean, stylusButton: Boolean): InkToolSpec? {
        val s = state()
        val layer = s.activeLayerId
        if (layer < 0) return null
        if (s.linkMode) return InkToolSpec(GestureTool.LINK, layer)
        s.selection?.let { sel ->
            val b = sel.bounds
            if (sel.pageId == pageId && worldX in (b.left - 8)..(b.right + 8) && worldY in (b.top - 8)..(b.bottom + 8)) {
                return InkToolSpec(GestureTool.MOVE_SELECTION, layer)
            }
        }
        if (eraserTip || (stylusButton && s.settings.stylusButtonErases)) return InkToolSpec(GestureTool.ERASER, layer)
        val t = s.toolSettings
        return when (t.tool) {
            AnnotationTool.PEN -> InkToolSpec(GestureTool.PEN, layer, InkBrushes.brush(BrushKind.PEN, t.penColor, t.penSize), BrushKind.PEN)
            AnnotationTool.HIGHLIGHTER -> InkToolSpec(
                GestureTool.HIGHLIGHTER, layer,
                InkBrushes.brush(BrushKind.HIGHLIGHTER, t.highlighterColor, t.highlighterSize), BrushKind.HIGHLIGHTER,
            )
            AnnotationTool.ERASER -> InkToolSpec(GestureTool.ERASER, layer)
            AnnotationTool.LASSO -> InkToolSpec(GestureTool.LASSO, layer)
            AnnotationTool.SHAPE -> InkToolSpec(GestureTool.SHAPE, layer)
            AnnotationTool.TEXT -> InkToolSpec(GestureTool.TEXT, layer)
            AnnotationTool.STAMP -> InkToolSpec(GestureTool.STAMP, layer)
        }
    }

    override fun onStrokesFinished(strokes: List<FinishedInk>) {
        val inks = strokes.map { InkStroke(UUID.randomUUID().toString(), it.pageId, it.layerId, it.kind, it.stroke) }
        onPending(inks)
        onIntent(ViewerIntent.StrokesAdded(inks))
    }

    override fun onStylusSeen() {
        val s = state()
        if (!s.annotating && !s.performanceMode && !s.loading) onIntent(ViewerIntent.StylusDetected)
    }

    override fun onToolGesture(gesture: ToolGesture) {
        val o = env.overlay
        val p = Offset(gesture.x, gesture.y)
        val page = gesture.pageId
        when (gesture.tool) {
            GestureTool.ERASER -> when (gesture.phase) {
                GesturePhase.START -> {
                    o.clear(); o.kind = GestureOverlay.Kind.ERASER; o.pageId = page; o.eraser = p
                    val settings = state().settings
                    o.eraserSize = if (settings.eraserMode == EraserMode.AREA) settings.eraserSize else 10f
                    lastErase = p
                    onIntent(ViewerIntent.EraseSegment(page, p.x, p.y, p.x, p.y))
                }
                GesturePhase.MOVE -> {
                    val last = lastErase ?: p
                    if ((p - last).getDistance() >= 1.5f) {
                        onIntent(ViewerIntent.EraseSegment(page, last.x, last.y, p.x, p.y))
                        lastErase = p
                    }
                    o.eraser = p
                }
                GesturePhase.END, GesturePhase.CANCEL -> {
                    o.clear()
                    lastErase = null
                    onIntent(ViewerIntent.EraseEnded)
                }
            }
            GestureTool.LASSO -> when (gesture.phase) {
                GesturePhase.START -> { o.clear(); o.kind = GestureOverlay.Kind.LASSO; o.pageId = page; o.lasso.add(p) }
                GesturePhase.MOVE -> {
                    val last = o.lasso.lastOrNull()
                    if (last == null || (p - last).getDistance() >= 2f) o.lasso.add(p)
                }
                GesturePhase.END -> {
                    o.lasso.add(p)
                    if (o.lasso.size >= 3) onIntent(ViewerIntent.LassoSelect(page, o.lasso.map { it.x to it.y }))
                    o.clear()
                }
                GesturePhase.CANCEL -> o.clear()
            }
            GestureTool.SHAPE, GestureTool.LINK -> when (gesture.phase) {
                GesturePhase.START -> {
                    o.clear()
                    o.kind = if (gesture.tool == GestureTool.SHAPE) GestureOverlay.Kind.SHAPE else GestureOverlay.Kind.LINK
                    o.pageId = page; o.start = p; o.end = p
                }
                GesturePhase.MOVE -> o.end = p
                GesturePhase.END -> {
                    val s = o.start ?: p
                    if (gesture.tool == GestureTool.SHAPE) {
                        if ((p - s).getDistance() >= 3f) onIntent(ViewerIntent.AddShape(page, s.x, s.y, p.x, p.y))
                    } else {
                        onIntent(ViewerIntent.LinkRectDrawn(page, WorldRect.of(s.x, s.y, p.x, p.y)))
                    }
                    o.clear()
                }
                GesturePhase.CANCEL -> o.clear()
            }
            GestureTool.MOVE_SELECTION -> when (gesture.phase) {
                GesturePhase.START -> { o.clear(); o.kind = GestureOverlay.Kind.MOVE; o.pageId = page; o.start = p }
                GesturePhase.MOVE -> o.start?.let { o.moveDelta = p - it }
                GesturePhase.END -> {
                    val d = o.start?.let { p - it } ?: Offset.Zero
                    if (d.getDistance() > 0.5f) onIntent(ViewerIntent.MoveSelection(d.x, d.y))
                    o.clear()
                }
                GesturePhase.CANCEL -> o.clear()
            }
            GestureTool.TEXT -> if (gesture.phase == GesturePhase.END) onIntent(ViewerIntent.TextTap(page, p.x, p.y))
            GestureTool.STAMP -> if (gesture.phase == GesturePhase.END) onIntent(ViewerIntent.PlaceStamp(page, p.x, p.y))
            GestureTool.PEN, GestureTool.HIGHLIGHTER -> Unit
        }
    }
}

package com.scorebook.feature.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scorebook.core.ink.InkBrushes
import com.scorebook.core.ink.ItemRenderer
import com.scorebook.core.ink.ShapeKind
import com.scorebook.core.model.AnnotationItem
import com.scorebook.core.model.ColorMode
import com.scorebook.core.model.EraserMode
import com.scorebook.core.model.ItemKind
import com.scorebook.ui.icons.EraserIcon
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.scorebook.core.model.ViewMode
import kotlin.math.roundToInt

@Composable
fun ViewerTopBar(
    state: ViewerState,
    registry: PageRegistry,
    onIntent: (ViewerIntent) -> Unit,
    onBack: () -> Unit,
    onOpenPageEditor: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().inkBlocker(registry),
    ) {
        Row(
            Modifier.statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    state.score?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val pageText = if (state.pages.isEmpty()) "" else pageLabel(state)
                val setlistText = state.setlist?.let { " · 셋리스트 ${it.index + 1}/${it.entries.size}" }.orEmpty()
                Text(pageText + setlistText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onIntent(ViewerIntent.OpenPanel(ViewerPanel.PAGES)) }) { Icon(Icons.Default.GridView, "페이지·북마크") }
            IconButton(onClick = { onIntent(ViewerIntent.OpenBookmarkDialog(true)) }) { Icon(Icons.Default.BookmarkAdd, "북마크 추가") }
            ToggleIcon(state.annotating, Icons.Default.Edit, "필기") { onIntent(ViewerIntent.ToggleAnnotating) }
            ToggleIcon(state.linkMode, Icons.Default.Link, "링크 편집") { onIntent(ViewerIntent.ToggleLinkMode) }
            ViewMenu(state, onIntent)
            IconButton(onClick = { onIntent(ViewerIntent.OpenPanel(ViewerPanel.TOOLS)) }) { Icon(Icons.Default.MusicNote, "연습 도구") }
            MoreMenu(state, onIntent, onOpenPageEditor)
        }
    }
}

private fun pageLabel(state: ViewerState): String {
    val n = state.pages.size
    val cur = state.currentPage + 1
    return when (state.effectiveMode) {
        ViewMode.TWO_PAGE -> {
            val base = state.currentPage - state.currentPage % 2 + 1
            if (base + 1 <= n) "$base-${base + 1} / ${n}쪽" else "$base / ${n}쪽"
        }
        ViewMode.HALF -> if (state.halfStep) "$cur½ / ${n}쪽" else "$cur / ${n}쪽"
        else -> "$cur / ${n}쪽"
    }
}

@Composable
private fun ToggleIcon(checked: Boolean, icon: ImageVector, description: String, onClick: () -> Unit) {
    FilledIconToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        colors = IconButtonDefaults.filledIconToggleButtonColors(containerColor = Color.Transparent),
    ) { Icon(icon, description) }
}

@Composable
private fun ViewMenu(state: ViewerState, onIntent: (ViewerIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.AutoStories, "보기") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeader("페이지 보기")
            listOf(
                ViewMode.SINGLE to "한 페이지 (가로 화면은 두 페이지)",
                ViewMode.TWO_PAGE to "두 페이지 (가로 화면)",
                ViewMode.HALF to "반 페이지 넘김",
                ViewMode.VERTICAL to "세로 스크롤",
            ).forEach { (mode, label) ->
                CheckItem(label, state.settings.viewMode == mode) { open = false; onIntent(ViewerIntent.SetViewMode(mode)) }
            }
            HorizontalDivider()
            MenuHeader("색상")
            listOf(ColorMode.NORMAL to "기본", ColorMode.NIGHT to "야간 (반전)", ColorMode.SEPIA to "세피아").forEach { (mode, label) ->
                CheckItem(label, state.colorMode == mode) { open = false; onIntent(ViewerIntent.SetColorMode(mode)) }
            }
            HorizontalDivider()
            CheckItem("여백 자동 자르기", state.settings.autoCropMargins) { open = false; onIntent(ViewerIntent.ToggleAutoCrop) }
            CheckItem("링크 표시", state.showLinks) { open = false; onIntent(ViewerIntent.ToggleShowLinks) }
        }
    }
}

@Composable
private fun MoreMenu(state: ViewerState, onIntent: (ViewerIntent) -> Unit, onOpenPageEditor: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "더보기") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (state.autoTurnActive) "자동 넘김 멈추기" else if (state.effectiveMode == ViewMode.VERTICAL) "자동 스크롤" else "자동 넘김 (${state.settings.autoTurnSeconds}초)") },
                leadingIcon = { Icon(if (state.autoTurnActive) Icons.Default.StopCircle else Icons.Default.PlayCircle, null) },
                onClick = { open = false; onIntent(ViewerIntent.ToggleAutoTurn) },
            )
            DropdownMenuItem(
                text = { Text(if (state.faceGestureActive) "얼굴 제스처 끄기" else "얼굴 제스처로 넘기기") },
                leadingIcon = { Icon(Icons.Default.Face, null) },
                onClick = { open = false; onIntent(ViewerIntent.ToggleFaceGesture) },
            )
            DropdownMenuItem(
                text = { Text("공연 모드") },
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                onClick = { open = false; onIntent(ViewerIntent.TogglePerformanceMode) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("PDF로 저장 (필기 포함)") },
                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                onClick = { open = false; onIntent(ViewerIntent.OpenExport(true)) },
            )
            DropdownMenuItem(
                text = { Text("페이지 편집") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ViewQuilt, null) },
                onClick = { open = false; onOpenPageEditor() },
            )
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun CheckItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal) },
        trailingIcon = { if (checked) Text("✓", color = MaterialTheme.colorScheme.secondary) },
        onClick = onClick,
    )
}

// ---------------------------------------------------------------------- 필기 툴바

@Composable
fun AnnotationToolbar(state: ViewerState, registry: PageRegistry, onIntent: (ViewerIntent) -> Unit, modifier: Modifier = Modifier) {
    val t = state.toolSettings
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(registry) {
            ToolButton(t.tool == AnnotationTool.PEN, Icons.Default.Edit, "펜") { onIntent(ViewerIntent.SelectTool(AnnotationTool.PEN)) }
            ToolButton(t.tool == AnnotationTool.HIGHLIGHTER, Icons.Default.BorderColor, "형광펜") { onIntent(ViewerIntent.SelectTool(AnnotationTool.HIGHLIGHTER)) }
            ToolButton(t.tool == AnnotationTool.ERASER, EraserIcon, "지우개") { onIntent(ViewerIntent.SelectTool(AnnotationTool.ERASER)) }
            ToolButton(t.tool == AnnotationTool.LASSO, Icons.Default.Gesture, "올가미") { onIntent(ViewerIntent.SelectTool(AnnotationTool.LASSO)) }
            ToolButton(t.tool == AnnotationTool.SHAPE, Icons.Default.Category, "도형") { onIntent(ViewerIntent.SelectTool(AnnotationTool.SHAPE)) }
            ToolButton(t.tool == AnnotationTool.TEXT, Icons.Default.TextFields, "텍스트") { onIntent(ViewerIntent.SelectTool(AnnotationTool.TEXT)) }
            ToolButton(t.tool == AnnotationTool.STAMP, Icons.Default.Approval, "음악 기호") {
                if (t.tool == AnnotationTool.STAMP) onIntent(ViewerIntent.OpenStampPicker(true))
                else onIntent(ViewerIntent.SelectTool(AnnotationTool.STAMP))
            }
            VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp))
            IconButton(onClick = { onIntent(ViewerIntent.OpenLayers(true)) }) {
                Icon(Icons.Default.Layers, "레이어")
            }
            ToolButton(state.fingerDrawing, Icons.Default.TouchApp, "손가락으로 쓰기") { onIntent(ViewerIntent.ToggleFingerDrawing) }
            VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp))
            IconButton(onClick = { onIntent(ViewerIntent.Undo) }, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "실행 취소") }
            IconButton(onClick = { onIntent(ViewerIntent.Redo) }, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "다시 실행") }
            IconButton(onClick = { onIntent(ViewerIntent.ToggleAnnotating) }) { Icon(Icons.Default.Close, "필기 닫기") }
        }
        ToolOptions(state, registry, onIntent)
    }
}

@Composable
private fun Pill(registry: PageRegistry, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.inkBlocker(registry),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun ToolButton(selected: Boolean, icon: ImageVector, description: String, onClick: () -> Unit) {
    FilledIconToggleButton(checked = selected, onCheckedChange = { onClick() }) { Icon(icon, description) }
}

@Composable
private fun ToolOptions(state: ViewerState, registry: PageRegistry, onIntent: (ViewerIntent) -> Unit) {
    val t = state.toolSettings
    fun update(new: ToolSettings) = onIntent(ViewerIntent.UpdateToolSettings(new))
    when (t.tool) {
        AnnotationTool.PEN, AnnotationTool.SHAPE, AnnotationTool.TEXT, AnnotationTool.STAMP -> Pill(registry) {
            if (t.tool == AnnotationTool.SHAPE) {
                listOf(ShapeKind.LINE to "─", ShapeKind.ARROW to "→", ShapeKind.RECT to "□", ShapeKind.ELLIPSE to "○").forEach { (k, label) ->
                    ChipText(label, t.shape == k) { update(t.copy(shape = k)) }
                }
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            }
            if (t.tool == AnnotationTool.STAMP) {
                StampPreviewButton(t.stampCode, t.penColor) { onIntent(ViewerIntent.OpenStampPicker(true)) }
                listOf(12f, 16f, 22f, 30f).forEach { s -> ChipText("${s.roundToInt()}", t.stampSize == s) { update(t.copy(stampSize = s)) } }
                NumberSizeInput(t.stampSize, 4f..120f) { update(t.copy(stampSize = it)) }
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            }
            InkBrushes.PEN_COLORS.forEach { c -> ColorDot(c, t.penColor == c) { update(t.copy(penColor = c)) } }
            if (t.tool == AnnotationTool.PEN || t.tool == AnnotationTool.SHAPE) {
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                InkBrushes.PEN_SIZES.forEach { s -> SizeDot(s, 1.2f, t.penSize == s) { update(t.copy(penSize = s)) } }
            }
            if (t.tool == AnnotationTool.TEXT) {
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                listOf(9f, 12f, 16f, 22f).forEach { s -> ChipText("${s.roundToInt()}pt", t.textSize == s) { update(t.copy(textSize = s)) } }
                NumberSizeInput(t.textSize, 4f..96f) { update(t.copy(textSize = it)) }
            }
        }
        AnnotationTool.HIGHLIGHTER -> Pill(registry) {
            InkBrushes.HIGHLIGHTER_COLORS.forEach { c -> ColorDot(c, t.highlighterColor == c) { update(t.copy(highlighterColor = c)) } }
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            InkBrushes.HIGHLIGHTER_SIZES.forEach { s -> SizeDot(s, 0.6f, t.highlighterSize == s) { update(t.copy(highlighterSize = s)) } }
        }
        AnnotationTool.ERASER -> Pill(registry) {
            val st = state.settings
            ChipText("획 지우개", st.eraserMode == EraserMode.STROKE) { onIntent(ViewerIntent.SetEraserMode(EraserMode.STROKE)) }
            ChipText("영역 지우개", st.eraserMode == EraserMode.AREA) { onIntent(ViewerIntent.SetEraserMode(EraserMode.AREA)) }
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
            if (st.eraserMode == EraserMode.AREA) {
                ERASER_PRESETS.forEach { s -> SquareDot(s, st.eraserSize == s) { onIntent(ViewerIntent.SetEraserSize(s)) } }
                EraserSizeSlider(st.eraserSize) { onIntent(ViewerIntent.SetEraserSize(it)) }
            } else {
                HintText("닿은 획을 통째로 지웁니다 · S펜 버튼을 누른 채 써도 지우개")
            }
        }
        AnnotationTool.LASSO -> Pill(registry) { HintText("둘러서 선택 → 선택 영역을 끌어 이동") }
    }
}

private val ERASER_PRESETS = listOf(6f, 10f, 16f, 24f, 36f)

/** 영역 지우개 크기 미리보기: 실제 크기에 비례하는 네모 */
@Composable
private fun SquareDot(size: Float, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(3.dp)
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size((size * 0.6f).coerceIn(5f, 26f).dp)
                .border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun EraserSizeSlider(value: Float, onCommit: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onCommit(local) },
        valueRange = ViewerViewModel.MIN_ERASER..ViewerViewModel.MAX_ERASER,
        modifier = Modifier.width(170.dp).padding(horizontal = 8.dp),
    )
    Text("${local.roundToInt()}pt", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
}

/** 텍스트 칸에 크기를 직접 입력 (± 버튼으로 1pt 씩 조절) */
@Composable
private fun NumberSizeInput(value: Float, range: ClosedFloatingPointRange<Float>, onCommit: (Float) -> Unit) {
    fun format(v: Float) = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)
    var text by remember(value) { mutableStateOf(format(value)) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    fun commit() {
        val parsed = text.replace(',', '.').toFloatOrNull()?.coerceIn(range)
        if (parsed == null) {
            text = format(value)
        } else {
            if (parsed != value) onCommit(parsed)
            text = format(parsed)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        IconButton(onClick = { onCommit((value - 1f).coerceIn(range)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Remove, "작게", Modifier.size(18.dp))
        }
        BasicTextField(
            value = text,
            onValueChange = { new -> text = new.filter { it.isDigit() || it == '.' || it == ',' }.take(5) },
            singleLine = true,
            textStyle = MaterialTheme.typography.labelLarge.copy(
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                commit()
                focusManager.clearFocus()
            }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    // 누르면 칸을 비우고 현재 값은 흐리게 보여줌 → 바로 새 숫자 입력
                    if (text.isEmpty()) {
                        Text(
                            format(value),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .width(52.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(vertical = 8.dp)
                .onFocusChanged { f ->
                    TextFieldFocus.active = f.isFocused
                    if (f.isFocused) {
                        focused = true
                        text = ""
                    } else if (focused) {
                        focused = false
                        commit()
                    }
                },
        )
        Text("pt", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 2.dp))
        IconButton(onClick = { onCommit((value + 1f).coerceIn(range)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Add, "크게", Modifier.size(18.dp))
        }
    }
}

/** 툴바의 숫자 입력칸에 커서가 있는 동안 페달용 키(Enter·Space 등)가 페이지를 넘기지 않도록 */
internal object TextFieldFocus {
    @Volatile var active: Boolean = false
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
private fun ChipText(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun ColorDot(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(5.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(color).copy(alpha = 1f))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.secondary else Color.Black.copy(alpha = 0.2f), CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun SizeDot(size: Float, factor: Float, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(3.dp)
            .size(34.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size((size * factor * 3).coerceIn(3f, 24f).dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
    }
}

@Composable
fun StampPreviewButton(code: String, color: Int, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = Color.White,
        modifier = Modifier.padding(4.dp).size(44.dp).clickable(onClick = onClick),
    ) {
        StampGlyph(code, color)
    }
}

@Composable
fun StampGlyph(code: String, color: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.size(44.dp)) {
        // 긴 지시어(예: D.S. al Coda)도 칸 안에 들어오도록 실제 크기를 재서 맞춘다
        val b = ItemRenderer.bounds(AnnotationItem("", 0, 0, ItemKind.STAMP, 0f, 0f, 16f, color, code))
        val scale = minOf(size.width * 0.86f / b.width, size.height * 0.86f / b.height, size.minDimension / 26f)
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate(size.width / 2f, size.height / 2f)
            nc.scale(scale, scale)
            ItemRenderer.drawStamp(nc, code, 0f, 0f, 16f, color)
            nc.restore()
        }
    }
}

// ---------------------------------------------------------------------- 선택 / 하단 바 / 공연 모드

@Composable
fun SelectionActions(registry: PageRegistry, onIntent: (ViewerIntent) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 6.dp,
        modifier = modifier.inkBlocker(registry),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("선택됨 · 끌어서 이동", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = { onIntent(ViewerIntent.DeleteSelection) }) { Icon(Icons.Default.Delete, "삭제") }
            IconButton(onClick = { onIntent(ViewerIntent.ClearSelection) }) { Icon(Icons.Default.Close, "선택 해제") }
        }
    }
}

@Composable
fun BottomScrubber(state: ViewerState, registry: PageRegistry, onIntent: (ViewerIntent) -> Unit) {
    val n = state.pages.size
    if (n <= 1) return
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) value else state.currentPage.toFloat()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        modifier = Modifier.fillMaxWidth().inkBlocker(registry),
    ) {
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${shown.roundToInt() + 1}", modifier = Modifier.width(40.dp), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = shown,
                onValueChange = { dragging = true; value = it },
                onValueChangeFinished = {
                    dragging = false
                    onIntent(ViewerIntent.GoToPage(value.roundToInt()))
                },
                valueRange = 0f..(n - 1).toFloat(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            state.setlist?.next?.let {
                Text("다음 곡: ${it.score.title}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(180.dp))
            }
        }
    }
}

@Composable
fun PerformanceLock(registry: PageRegistry, onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.25f),
        modifier = modifier
            .inkBlocker(registry)
            .size(44.dp)
            .clip(CircleShape)
            .combinedClickable(onClick = {}, onLongClick = onUnlock),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Lock, "길게 눌러 공연 모드 해제", tint = Color.White)
        }
    }
}

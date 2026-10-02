package com.scorebook.feature.pageeditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorebook.core.model.PageCrop
import com.scorebook.core.model.PageSourceKind
import com.scorebook.core.model.ScorePage
import com.scorebook.core.render.PageRenderer
import com.scorebook.ui.components.ConfirmDialog
import com.scorebook.ui.components.PageThumbnail
import com.scorebook.ui.components.TextInputDialog
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(
    onBack: () -> Unit,
    onOpenScore: (Long) -> Unit,
    viewModel: PageEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect {
            when (it) {
                is PageEditorEffect.Message -> launch { snackbar.showSnackbar(it.text) }
                is PageEditorEffect.OpenScore -> onOpenScore(it.scoreId)
            }
        }
    }
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        viewModel.onIntent(PageEditorIntent.Move(from.index, to.index))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                title = {
                    Column {
                        Text("페이지 편집")
                        Text(
                            if (state.selection.isEmpty()) "${state.score?.title.orEmpty()} · ${state.pages.size}쪽 · 길게 눌러 끌면 순서 변경"
                            else "${state.selection.size}쪽 선택됨",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.onIntent(if (state.selection.size == state.pages.size) PageEditorIntent.ClearSelection else PageEditorIntent.SelectAll)
                    }) { Icon(Icons.Default.SelectAll, "모두 선택") }
                    IconButton(onClick = { viewModel.onIntent(PageEditorIntent.Rotate(-90)) }) { Icon(Icons.AutoMirrored.Filled.RotateLeft, "왼쪽으로 회전") }
                    IconButton(onClick = { viewModel.onIntent(PageEditorIntent.Rotate(90)) }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "오른쪽으로 회전") }
                    IconButton(onClick = { viewModel.onIntent(PageEditorIntent.OpenCrop(null)) }) { Icon(Icons.Default.Crop, "자르기") }
                    IconButton(onClick = { viewModel.onIntent(PageEditorIntent.InsertStaff) }) { Icon(Icons.Default.LibraryAdd, "빈 오선지 넣기") }
                    IconButton(onClick = { viewModel.onIntent(PageEditorIntent.RequestSplit) }) { Icon(Icons.AutoMirrored.Filled.CallSplit, "새 악보로 나누기") }
                    IconButton(onClick = { viewModel.onIntent(PageEditorIntent.RequestDelete) }) { Icon(Icons.Default.Delete, "삭제") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(state.pages, key = { _, p -> p.id }) { index, page ->
                    ReorderableItem(reorderState, key = page.id) { dragging ->
                        val selected = page.id in state.selection
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .longPressDraggableHandle(onDragStopped = { viewModel.onIntent(PageEditorIntent.CommitOrder) })
                                .clickable { viewModel.onIntent(PageEditorIntent.Toggle(page.id)) },
                        ) {
                            Box {
                                PageThumbnail(
                                    page, viewModel.renderer,
                                    Modifier.fillMaxWidth().border(
                                        if (selected || dragging) 3.dp else 1.dp,
                                        if (selected || dragging) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                )
                                if (selected) {
                                    Icon(
                                        Icons.Default.CheckCircle, null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp),
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.DragIndicator, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                                Text(
                                    "${index + 1}" + when (page.kind) {
                                        PageSourceKind.STAFF -> " · 오선지"
                                        PageSourceKind.IMAGE -> " · 사진"
                                        PageSourceKind.PDF -> ""
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    state.cropTarget?.let { page ->
        CropDialog(
            page = page,
            renderer = viewModel.renderer,
            multiple = state.selection.size > 1,
            onApply = { crop, all -> viewModel.onIntent(PageEditorIntent.ApplyCrop(crop, all)) },
            onDismiss = { viewModel.onIntent(PageEditorIntent.DismissDialog) },
        )
    }
    if (state.confirmDelete) {
        ConfirmDialog(
            title = "페이지 삭제",
            message = "선택한 ${state.selection.size}쪽과 그 페이지의 필기가 삭제됩니다.",
            confirmText = "삭제",
            destructive = true,
            onConfirm = { viewModel.onIntent(PageEditorIntent.ConfirmDelete) },
            onDismiss = { viewModel.onIntent(PageEditorIntent.DismissDialog) },
        )
    }
    if (state.splitDialog) {
        TextInputDialog(
            title = "선택한 ${state.selection.size}쪽으로 새 악보 만들기",
            initial = "${state.score?.title.orEmpty()} (일부)",
            label = "새 악보 이름",
            confirmText = "만들기",
            onConfirm = { viewModel.onIntent(PageEditorIntent.ConfirmSplit(it)) },
            onDismiss = { viewModel.onIntent(PageEditorIntent.DismissDialog) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CropDialog(
    page: ScorePage,
    renderer: PageRenderer,
    multiple: Boolean,
    onApply: (PageCrop?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = page.crop ?: PageCrop.FULL
    var horizontal by remember { mutableStateOf(initial.left..initial.right) }
    var vertical by remember { mutableStateOf(initial.top..initial.bottom) }
    var applyAll by remember { mutableStateOf(multiple) }
    val scope = rememberCoroutineScope()
    val preview = remember(page) { page.copy(rotation = 0, crop = null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("페이지 자르기") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().padding(horizontal = 48.dp)) {
                    PageThumbnail(preview, renderer, Modifier.fillMaxWidth(), widthPx = 480)
                    Canvas(Modifier.matchParentSize()) {
                        val l = horizontal.start * size.width
                        val r = horizontal.endInclusive * size.width
                        val t = vertical.start * size.height
                        val b = vertical.endInclusive * size.height
                        val shade = Color.Black.copy(alpha = 0.45f)
                        drawRect(shade, Offset.Zero, Size(size.width, t))
                        drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                        drawRect(shade, Offset(0f, t), Size(l, b - t))
                        drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
                        drawRect(Color(0xFFEF8354), Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("좌우", style = MaterialTheme.typography.labelMedium)
                RangeSlider(value = horizontal, onValueChange = { horizontal = it }, valueRange = 0f..1f)
                Text("위아래", style = MaterialTheme.typography.labelMedium)
                RangeSlider(value = vertical, onValueChange = { vertical = it }, valueRange = 0f..1f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        scope.launch {
                            renderer.contentBounds(page)?.let {
                                horizontal = it.left..it.right
                                vertical = it.top..it.bottom
                            }
                        }
                    }) { Text("여백 자동 감지") }
                    Spacer(Modifier.width(8.dp))
                    if (multiple) {
                        Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                        Text("선택한 모든 페이지에 적용")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val crop = PageCrop(horizontal.start, vertical.start, horizontal.endInclusive, vertical.endInclusive)
                val full = crop.left <= 0.001f && crop.top <= 0.001f && crop.right >= 0.999f && crop.bottom >= 0.999f
                onApply(if (full || crop.right - crop.left < 0.05f || crop.bottom - crop.top < 0.05f) null else crop, applyAll)
            }) { Text("적용") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onApply(null, applyAll) }) { Text("자르기 해제") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}


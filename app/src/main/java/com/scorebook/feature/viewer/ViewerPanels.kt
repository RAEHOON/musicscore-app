package com.scorebook.feature.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scorebook.core.ink.InkBrushes
import com.scorebook.core.ink.Stamps
import com.scorebook.core.model.AnnotationLayer
import com.scorebook.core.render.PageRenderer
import com.scorebook.ui.components.ConfirmDialog
import com.scorebook.ui.components.PageThumbnail
import com.scorebook.ui.components.TextInputDialog
import kotlin.math.roundToInt

/** 왼쪽에서 열리는 페이지 썸네일 + 북마크 패널 */
@Composable
fun PagesPanel(state: ViewerState, renderer: PageRenderer, onIntent: (ViewerIntent) -> Unit, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    Surface(
        modifier = modifier.fillMaxHeight().width(340.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp)) {
                Text("페이지", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onIntent(ViewerIntent.OpenPanel(null)) }) { Icon(Icons.Default.Close, "닫기") }
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("전체 페이지") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("북마크 ${state.bookmarks.size}") })
            }
            if (tab == 0) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(state.pages, key = { _, p -> p.id }) { index, page ->
                        val current = index == state.currentPage
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                onIntent(ViewerIntent.GoToPage(index))
                                onIntent(ViewerIntent.OpenPanel(null))
                            },
                        ) {
                            PageThumbnail(
                                page, renderer,
                                Modifier.fillMaxWidth().border(
                                    if (current) 3.dp else 1.dp,
                                    if (current) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            )
                            val mark = state.bookmarks.firstOrNull { it.pageId == page.id }
                            Text(
                                (if (mark != null) "🔖 " else "") + "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            } else {
                if (state.bookmarks.isEmpty()) {
                    Text(
                        "상단의 북마크 버튼으로 현재 페이지를 북마크할 수 있습니다.\n악보집 안의 곡마다 북마크를 만들어 두면 셋리스트에서 바로 그 곡부터 열 수 있어요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn {
                    items(state.bookmarks, key = { it.id }) { b ->
                        val index = state.pages.indexOfFirst { it.id == b.pageId }
                        ListItem(
                            headlineContent = { Text(b.title) },
                            supportingContent = { Text("${index + 1}쪽") },
                            leadingContent = { Icon(Icons.Default.Bookmark, null, tint = MaterialTheme.colorScheme.secondary) },
                            trailingContent = {
                                IconButton(onClick = { onIntent(ViewerIntent.DeleteBookmark(b.id)) }) { Icon(Icons.Default.Delete, "삭제") }
                            },
                            modifier = Modifier.clickable {
                                if (index >= 0) onIntent(ViewerIntent.GoToPage(index))
                                onIntent(ViewerIntent.OpenPanel(null))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LayersDialog(state: ViewerState, onIntent: (ViewerIntent) -> Unit) {
    var renaming by remember { mutableStateOf<AnnotationLayer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AnnotationLayer?>(null) }
    AlertDialog(
        onDismissRequest = { onIntent(ViewerIntent.OpenLayers(false)) },
        title = { Text("필기 레이어") },
        text = {
            Column {
                Text(
                    "선생님 지시, 내 연습 메모, 공연용 표시를 레이어로 나눠 두고 필요할 때만 켜 보세요. 새 필기는 선택된 레이어에 들어갑니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(state.layers, key = { it.id }) { layer ->
                        ListItem(
                            leadingContent = {
                                RadioButton(selected = layer.id == state.activeLayerId, onClick = { onIntent(ViewerIntent.SetActiveLayer(layer.id)) })
                            },
                            headlineContent = { Text(layer.name) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { onIntent(ViewerIntent.ToggleLayerVisible(layer.id)) }) {
                                        Icon(if (layer.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff, "표시")
                                    }
                                    IconButton(onClick = { renaming = layer }) { Icon(Icons.Default.Edit, "이름 변경") }
                                    IconButton(onClick = { deleting = layer }, enabled = state.layers.size > 1) { Icon(Icons.Default.Delete, "삭제") }
                                }
                            },
                            modifier = Modifier.clickable { onIntent(ViewerIntent.SetActiveLayer(layer.id)) },
                        )
                    }
                }
                TextButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Text("레이어 추가") }
            }
        },
        confirmButton = { TextButton(onClick = { onIntent(ViewerIntent.OpenLayers(false)) }) { Text("닫기") } },
    )
    renaming?.let { layer ->
        TextInputDialog("레이어 이름", layer.name, onConfirm = { onIntent(ViewerIntent.RenameLayer(layer.id, it)); renaming = null }, onDismiss = { renaming = null })
    }
    if (adding) {
        TextInputDialog(
            "새 레이어", "레이어 ${state.layers.size + 1}",
            onConfirm = { onIntent(ViewerIntent.AddLayer(it)); adding = false },
            onDismiss = { adding = false },
        )
    }
    deleting?.let { layer ->
        ConfirmDialog(
            title = "레이어 삭제",
            message = "'${layer.name}' 레이어와 그 안의 모든 필기가 삭제됩니다.",
            confirmText = "삭제",
            destructive = true,
            onConfirm = { onIntent(ViewerIntent.DeleteLayer(layer.id)); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StampPickerDialog(state: ViewerState, onIntent: (ViewerIntent) -> Unit) {
    val t = state.toolSettings
    AlertDialog(
        onDismissRequest = { onIntent(ViewerIntent.OpenStampPicker(false)) },
        title = { Text("음악 기호") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Stamps.categories.forEach { category ->
                    Text(category, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Stamps.all.filter { it.category == category }.forEach { stamp ->
                            val selected = stamp.code == t.stampCode
                            val pick = {
                                onIntent(ViewerIntent.UpdateToolSettings(t.copy(stampCode = stamp.code, tool = AnnotationTool.STAMP)))
                                onIntent(ViewerIntent.OpenStampPicker(false))
                            }
                            if (Stamps.isTextual(stamp.code)) {
                                // 지시어는 글자 그대로 넓은 칩으로
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.White,
                                    border = BorderStroke(
                                        if (selected) 3.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                    modifier = Modifier.clickable(onClick = pick),
                                ) {
                                    Text(
                                        stamp.label,
                                        color = Color(0xFF111111),
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = if (stamp.code.startsWith("tempo:")) FontStyle.Normal else FontStyle.Italic,
                                        fontWeight = if (stamp.code.startsWith("tempo:")) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    )
                                }
                                return@forEach
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White,
                                border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.size(52.dp).clickable {
                                    onIntent(ViewerIntent.UpdateToolSettings(t.copy(stampCode = stamp.code, tool = AnnotationTool.STAMP)))
                                    onIntent(ViewerIntent.OpenStampPicker(false))
                                },
                            ) {
                                Box(contentAlignment = Alignment.Center) { StampGlyph(stamp.code, 0xFF111111.toInt()) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onIntent(ViewerIntent.OpenStampPicker(false)) }) { Text("닫기") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextEditorDialog(editor: TextEditor, onIntent: (ViewerIntent) -> Unit) {
    var text by remember { mutableStateOf(editor.text) }
    var size by remember { mutableFloatStateOf(editor.size) }
    var color by remember { mutableIntStateOf(editor.color) }
    AlertDialog(
        onDismissRequest = { onIntent(ViewerIntent.DismissDialog) },
        title = { Text(if (editor.itemId == null) "텍스트 추가" else "텍스트 편집") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth(), placeholder = { Text("예: 여기서 숨쉬기, 활 아끼기") })
                Spacer(Modifier.height(8.dp))
                Text("크기 ${size.roundToInt()}pt", style = MaterialTheme.typography.labelMedium)
                Slider(value = size, onValueChange = { size = it }, valueRange = 6f..36f)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InkBrushes.PEN_COLORS.forEach { c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.secondary, CircleShape)
                                .clickable { color = c },
                        )
                    }
                }
                if (editor.itemId != null) {
                    Text("내용을 모두 지우고 저장하면 텍스트가 삭제됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onIntent(ViewerIntent.ConfirmText(text, size, color)) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = { onIntent(ViewerIntent.DismissDialog) }) { Text("취소") } },
    )
}

@Composable
fun ExportDialog(state: ViewerState, onIntent: (ViewerIntent) -> Unit) {
    var include by remember { mutableStateOf(true) }
    var visibleOnly by remember { mutableStateOf(true) }
    var overwrite by remember { mutableStateOf(false) }
    val canOverwrite = state.score?.originalUri != null
    AlertDialog(
        onDismissRequest = { onIntent(ViewerIntent.OpenExport(false)) },
        title = { Text("PDF로 저장") },
        text = {
            Column {
                CheckRow("필기 포함", include) { include = it }
                CheckRow("보이는 레이어만", visibleOnly, enabled = include) { visibleOnly = it }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { overwrite = false }) {
                    RadioButton(selected = !overwrite, onClick = { overwrite = false }); Text("새 PDF 파일로 저장")
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = canOverwrite) { overwrite = true }) {
                    RadioButton(selected = overwrite, onClick = { overwrite = true }, enabled = canOverwrite)
                    Text("가져왔던 원본 PDF에 덮어쓰기", color = if (canOverwrite) Color.Unspecified else MaterialTheme.colorScheme.outline)
                }
                Text(
                    "필기는 벡터로 저장되어 확대해도 선명하고, 다른 PDF 앱에서도 보입니다. 앱 안의 필기는 그대로 남아 계속 수정할 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onIntent(ViewerIntent.StartExport(ExportOptions(include, visibleOnly, overwrite))) }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = { onIntent(ViewerIntent.OpenExport(false)) }) { Text("취소") } },
    )
}

@Composable
private fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(text)
    }
}

/** 링크 대상 페이지 선택 */
@Composable
fun LinkTargetDialog(state: ViewerState, renderer: PageRenderer, onIntent: (ViewerIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(ViewerIntent.DismissDialog) },
        title = { Text("어느 페이지로 이동할까요?") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 480.dp),
            ) {
                itemsIndexed(state.pages, key = { _, p -> p.id }) { index, page ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onIntent(ViewerIntent.CreateLink(index)) }) {
                        PageThumbnail(page, renderer, Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant))
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onIntent(ViewerIntent.DismissDialog) }) { Text("취소") } },
    )
}

@Composable
fun ViewerDialogs(state: ViewerState, renderer: PageRenderer, onIntent: (ViewerIntent) -> Unit) {
    state.textEditor?.let { TextEditorDialog(it, onIntent) }
    if (state.layersDialogOpen) LayersDialog(state, onIntent)
    if (state.stampPickerOpen) StampPickerDialog(state, onIntent)
    if (state.exportDialogOpen) ExportDialog(state, onIntent)
    if (state.pendingLink != null) LinkTargetDialog(state, renderer, onIntent)
    state.linkToDelete?.let {
        ConfirmDialog(
            title = "링크 삭제",
            message = "이 링크를 삭제할까요?",
            confirmText = "삭제",
            destructive = true,
            onConfirm = { onIntent(ViewerIntent.ConfirmDeleteLink) },
            onDismiss = { onIntent(ViewerIntent.DismissDialog) },
        )
    }
    if (state.bookmarkDialogOpen) {
        TextInputDialog(
            title = "북마크 추가 (${state.currentPage + 1}쪽)",
            initial = "${state.currentPage + 1}쪽",
            label = "이름 (예: 2악장, 앙코르곡)",
            onConfirm = { onIntent(ViewerIntent.AddBookmark(it)) },
            onDismiss = { onIntent(ViewerIntent.OpenBookmarkDialog(false)) },
        )
    }
}


package com.scorebook.feature.viewer

import com.scorebook.ui.components.isCompactWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.scorebook.core.render.PageRenderer
import com.scorebook.core.util.KoreanSearch
import com.scorebook.ui.components.PageThumbnail

/** 자동 곡 나누기: 분석 진행 → 결과 확인(이름 고치기·빼기) → 적용 */
@Composable
fun SongSplitDialog(split: SongSplitUi, onIntent: (ViewerIntent) -> Unit) {
    Dialog(
        onDismissRequest = { onIntent(ViewerIntent.CancelSongSplit) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(0.92f).heightIn(max = 900.dp).fillMaxSize(0.9f),
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("곡 자동 나누기", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onIntent(ViewerIntent.CancelSongSplit) }) { Icon(Icons.Default.Close, "닫기") }
                }
                when {
                    split.running -> Column(Modifier.fillMaxWidth().padding(vertical = 32.dp)) {
                        Text("악보를 살펴보는 중… ${split.done} / ${split.total}쪽")
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { if (split.total == 0) 0f else split.done.toFloat() / split.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "페이지마다 끝세로줄(얇은 선 + 굵은 선)로 끝나는지, 큰 제목으로 시작하는지 확인합니다. " +
                                "곡을 찾은 뒤에는 제목 글자를 읽어 곡 이름을 붙여요. 모든 처리는 이 기기 안에서만 이루어집니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    split.error != null -> Text(split.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 24.dp))
                    else -> SongCandidateList(split, onIntent, Modifier.weight(1f))
                }
                if (!split.running && split.error == null) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val n = split.candidates.count { it.include }
                        Text(
                            if (isCompactWidth()) "" else "체크한 ${n}곡이 북마크(곡 목록)로 저장됩니다",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onIntent(ViewerIntent.CancelSongSplit) }) { Text("취소") }
                        Button(onClick = { onIntent(ViewerIntent.ApplySongSplit) }, enabled = n > 0) { Text("${n}곡으로 나누기") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongCandidateList(split: SongSplitUi, onIntent: (ViewerIntent) -> Unit, modifier: Modifier) {
    val compact = isCompactWidth()
    Column(modifier) {
        Text(
            "${split.candidates.size}곡을 찾았습니다. 이름이 틀렸으면 고치고, 곡이 아닌 것은 체크를 빼 주세요.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(split.candidates, key = { _, c -> c.pageId }) { i, c ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (c.include) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                ) {
                    val preview: @Composable (Modifier) -> Unit = { m ->
                        c.preview?.let { img ->
                            Box(
                                m
                                    .height(64.dp)
                                    .background(Color.White, RoundedCornerShape(6.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                            ) {
                                Image(img, contentDescription = "제목 미리보기", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                            }
                        }
                    }
                    Row(Modifier.padding(if (compact) 6.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.include, onCheckedChange = { onIntent(ViewerIntent.EditSongCandidate(i, include = it)) })
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!compact) Text("${i + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
                                OutlinedTextField(
                                    value = c.name,
                                    onValueChange = { onIntent(ViewerIntent.EditSongCandidate(i, name = it)) },
                                    singleLine = true,
                                    enabled = c.include,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(start = if (compact) 0.dp else 32.dp, top = 4.dp),
                            ) {
                                Text("${c.pageIndex + 1}쪽", style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically))
                                if (c.byTitle) Reason("큰 제목")
                                if (c.byPreviousEnd) Reason("앞 페이지 끝세로줄")
                                if (c.pageIndex == 0) Reason("첫 페이지")
                            }
                            // 좁은 화면: 미리보기를 이름 아래에
                            if (compact) preview(Modifier.padding(top = 6.dp).fillMaxWidth())
                        }
                        if (!compact) preview(Modifier.padding(start = 10.dp).width(220.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Reason(text: String) {
    AssistChip(onClick = {}, label = { Text(text, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(26.dp))
}

/** 곡 바로가기: 곡 이름을 눌러 바로 이동 (곡이 많으면 검색) */
@Composable
fun SongPickerDialog(state: ViewerState, onIntent: (ViewerIntent) -> Unit) {
    var query by remember { mutableStateOf("") }
    val songs = state.songs
    val currentId = state.currentSong?.bookmark?.id
    val shown = songs.withIndex().filter { KoreanSearch.matches(it.value.bookmark.title, query) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val idx = shown.indexOfFirst { it.value.bookmark.id == currentId }
        if (idx > 2) listState.scrollToItem(idx - 2)
    }
    AlertDialog(
        onDismissRequest = { onIntent(ViewerIntent.OpenSongPicker(false)) },
        title = { Text("곡 바로가기") },
        text = {
            Column {
                if (songs.size > 8) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("곡 이름 검색 (초성 가능)") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                if (songs.isEmpty()) {
                    Text(
                        "아직 곡 목록이 없습니다. 여러 곡이 붙어 있는 악보집이면 '자동으로 곡 나누기'로 곡마다 바로가기를 만들 수 있어요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(shown, key = { _, s -> s.value.bookmark.id }) { _, item ->
                        val song = item.value
                        val current = song.bookmark.id == currentId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable { onIntent(ViewerIntent.JumpToSong(song.pageIndex)) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        ) {
                            Text("${item.index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(32.dp))
                            Text(
                                song.bookmark.title,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${song.pageIndex + 1}쪽", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onIntent(ViewerIntent.OpenSongPicker(false)) }) { Text("닫기") } },
        dismissButton = {
            TextButton(onClick = { onIntent(ViewerIntent.StartSongSplit) }) {
                Text(if (songs.isEmpty()) "자동으로 곡 나누기" else "다시 자동 나누기")
            }
        },
    )
}

/** 링크 이름과 도착 페이지 바꾸기 */
@Composable
fun LinkEditDialog(editor: LinkEditor, state: ViewerState, renderer: PageRenderer, onIntent: (ViewerIntent) -> Unit) {
    var label by remember { mutableStateOf(editor.label) }
    var target by remember { mutableIntStateOf(editor.targetIndex) }
    AlertDialog(
        onDismissRequest = { onIntent(ViewerIntent.DismissDialog) },
        title = { Text("링크 편집") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("이름 (예: D.S. → 세뇨, 2절로)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("도착 페이지: ${target + 1}쪽", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 380.dp),
                ) {
                    itemsIndexed(state.pages, key = { _, p -> p.id }) { index, page ->
                        val selected = index == target
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { target = index }) {
                            PageThumbnail(
                                page, renderer,
                                Modifier.fillMaxWidth().border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            )
                            Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onIntent(ViewerIntent.SaveLinkEdit(label, target)) }) { Text("저장") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onIntent(ViewerIntent.RequestDeleteLink(editor.linkId)) }) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { onIntent(ViewerIntent.DismissDialog) }) { Text("취소") }
            }
        },
    )
}


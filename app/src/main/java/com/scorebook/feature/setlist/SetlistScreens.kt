package com.scorebook.feature.setlist

import com.scorebook.ui.components.isCompactWidth
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.scorebook.core.model.Score
import com.scorebook.core.model.SetlistEntry
import com.scorebook.core.util.KoreanSearch
import com.scorebook.ui.components.ConfirmDialog
import com.scorebook.ui.components.TextInputDialog
import com.scorebook.ui.components.rememberLibraryFile
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetlistsScreen(onOpen: (Long) -> Unit, viewModel: SetlistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { if (it is SetlistsEffect.Open) onOpen(it.id) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("셋리스트", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.onIntent(SetlistsIntent.ShowCreate(true)) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("새 셋리스트") },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (!state.loading && state.setlists.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("공연·레슨 순서대로 악보를 묶어 두세요", style = MaterialTheme.typography.titleMedium)
                Text(
                    "셋리스트로 열면 마지막 페이지에서 다음으로 넘길 때 다음 곡이 바로 열립니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(280.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding),
        ) {
            items(state.setlists, key = { it.id }) { s ->
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().clickable { onOpen(s.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(36.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${s.itemCount}곡 · " + SimpleDateFormat("yyyy.M.d", Locale.KOREA).format(Date(s.updatedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "더보기") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("이름 변경") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = {
                                    menu = false; viewModel.onIntent(SetlistsIntent.RequestRename(s))
                                })
                                DropdownMenuItem(text = { Text("삭제") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = {
                                    menu = false; viewModel.onIntent(SetlistsIntent.RequestDelete(s))
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.creating) {
        TextInputDialog(
            "새 셋리스트", "", label = "이름 (예: 10월 정기연주회)",
            onConfirm = { viewModel.onIntent(SetlistsIntent.Create(it)) },
            onDismiss = { viewModel.onIntent(SetlistsIntent.ShowCreate(false)) },
        )
    }
    state.renaming?.let { s ->
        TextInputDialog(
            "이름 변경", s.name,
            onConfirm = { viewModel.onIntent(SetlistsIntent.Rename(it)) },
            onDismiss = { viewModel.onIntent(SetlistsIntent.RequestRename(null)) },
        )
    }
    state.deleting?.let { s ->
        ConfirmDialog(
            "셋리스트 삭제", "'${s.name}' 셋리스트를 삭제할까요? 악보는 지워지지 않습니다.",
            confirmText = "삭제", destructive = true,
            onConfirm = { viewModel.onIntent(SetlistsIntent.ConfirmDelete) },
            onDismiss = { viewModel.onIntent(SetlistsIntent.RequestDelete(null)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetlistDetailScreen(
    onBack: () -> Unit,
    onPlay: (setlistId: Long, index: Int, scoreId: Long) -> Unit,
    viewModel: SetlistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { if (it is SetlistDetailEffect.Play) onPlay(it.setlistId, it.index, it.scoreId) }
    }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        viewModel.onIntent(SetlistDetailIntent.Move(from.index, to.index))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                title = {
                    Text(
                        state.name,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { viewModel.onIntent(SetlistDetailIntent.ShowRename(true)) },
                    )
                },
                actions = {
                    // 좁은 화면에서는 글자 없이 아이콘만 (제목 자리를 남긴다)
                    val compact = isCompactWidth()
                    TextButton(onClick = { viewModel.onIntent(SetlistDetailIntent.OpenPicker(true)) }) {
                        Icon(Icons.Default.Add, "곡 추가"); if (!compact) Text("곡 추가")
                    }
                    Button(
                        onClick = { viewModel.onIntent(SetlistDetailIntent.Play(0)) },
                        enabled = state.entries.isNotEmpty(),
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Icon(Icons.Default.PlayArrow, "처음부터 연주"); Text(if (compact) "연주" else "처음부터 연주") }
                },
            )
        },
    ) { padding ->
        if (state.entries.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("'곡 추가'로 악보를 넣어 보세요", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding),
        ) {
            itemsIndexed(state.entries, key = { _, e -> e.id }) { index, entry ->
                ReorderableItem(reorder, key = entry.id) { dragging ->
                    SetlistEntryRow(
                        index = index,
                        entry = entry,
                        dragging = dragging,
                        bookmarks = state.bookmarks[entry.score.id].orEmpty(),
                        onPlay = { viewModel.onIntent(SetlistDetailIntent.Play(index)) },
                        onRemove = { viewModel.onIntent(SetlistDetailIntent.Remove(entry)) },
                        onStart = { viewModel.onIntent(SetlistDetailIntent.SetStart(entry, it)) },
                        handle = Modifier.draggableHandle(onDragStopped = { viewModel.onIntent(SetlistDetailIntent.CommitOrder) }),
                    )
                }
            }
        }
    }
    if (state.pickerOpen) {
        ScorePickerDialog(
            scores = state.allScores,
            onConfirm = { viewModel.onIntent(SetlistDetailIntent.AddScores(it)) },
            onDismiss = { viewModel.onIntent(SetlistDetailIntent.OpenPicker(false)) },
        )
    }
    if (state.renameOpen) {
        TextInputDialog(
            "셋리스트 이름", state.name,
            onConfirm = { viewModel.onIntent(SetlistDetailIntent.Rename(it)) },
            onDismiss = { viewModel.onIntent(SetlistDetailIntent.ShowRename(false)) },
        )
    }
}

@Composable
private fun SetlistEntryRow(
    index: Int,
    entry: SetlistEntry,
    dragging: Boolean,
    bookmarks: List<com.scorebook.core.model.Bookmark>,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onStart: (Long?) -> Unit,
    handle: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = if (dragging) 10.dp else 1.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DragHandle, "순서 변경", modifier = handle.padding(8.dp))
            Text("${index + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp))
            val thumb = rememberLibraryFile(entry.score.thumbnailPath)
            Box(Modifier.size(48.dp, 64.dp).clip(RoundedCornerShape(4.dp))) {
                if (thumb != null) AsyncImage(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {}
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(entry.score.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val start = bookmarks.firstOrNull { it.pageId == entry.startPageId }
                Text(
                    listOfNotNull(entry.score.composer.ifBlank { null }, start?.let { "시작: ${it.title}" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.Bookmark, "시작 위치") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("처음부터") }, onClick = { menu = false; onStart(null) })
                    bookmarks.forEach { b ->
                        DropdownMenuItem(text = { Text(b.title) }, onClick = { menu = false; onStart(b.pageId) })
                    }
                    if (bookmarks.isEmpty()) {
                        DropdownMenuItem(text = { Text("악보에 북마크를 만들면 시작 위치로 고를 수 있어요") }, onClick = { menu = false }, enabled = false)
                    }
                }
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Remove, "빼기") }
        }
    }
}

@Composable
private fun ScorePickerDialog(scores: List<Score>, onConfirm: (List<Long>) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Long>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("곡 추가") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("검색") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(scores.filter { KoreanSearch.matches(it.title, query) || KoreanSearch.matches(it.composer, query) }, key = { it.id }) { s ->
                        val checked = s.id in picked
                        ListItem(
                            leadingContent = { Checkbox(checked, { if (checked) picked.remove(s.id) else picked.add(s.id) }) },
                            headlineContent = { Text(s.title) },
                            supportingContent = { if (s.composer.isNotBlank()) Text(s.composer) },
                            trailingContent = { if (checked) Text("${picked.indexOf(s.id) + 1}") },
                            modifier = Modifier.clickable { if (checked) picked.remove(s.id) else picked.add(s.id) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked.toList()) }, enabled = picked.isNotEmpty()) { Text("${picked.size}곡 추가") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

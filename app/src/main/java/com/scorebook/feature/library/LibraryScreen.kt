package com.scorebook.feature.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.scorebook.core.model.LibrarySort
import com.scorebook.core.model.Score
import com.scorebook.ui.components.ConfirmDialog
import com.scorebook.ui.components.TextInputDialog
import com.scorebook.ui.components.rememberLibraryFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenScore: (Long) -> Unit,
    onOpenPageEditor: (Long) -> Unit,
    onCreateFromImages: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val pickPdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.onIntent(LibraryIntent.ImportPdfs(uris))
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is LibraryEffect.OpenViewer -> onOpenScore(effect.scoreId)
                is LibraryEffect.OpenPageEditor -> onOpenPageEditor(effect.scoreId)
                is LibraryEffect.Message -> snackbar.showSnackbar(effect.text)
            }
        }
    }

    BackHandler(enabled = state.selecting) { viewModel.onIntent(LibraryIntent.ClearSelection) }

    var importMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (state.selecting) {
                SelectionTopBar(state, viewModel::onIntent)
            } else {
                TopAppBar(
                    title = { Text("Hoon's뷰어", fontWeight = FontWeight.Bold) },
                    actions = { SortMenu(state.sort) { viewModel.onIntent(LibraryIntent.SortChanged(it)) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { importMenu = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("악보 추가") },
                )
                DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("PDF 파일 불러오기") },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                        onClick = { importMenu = false; pickPdfs.launch(arrayOf("application/pdf")) },
                    )
                    DropdownMenuItem(
                        text = { Text("사진으로 악보 만들기") },
                        leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                        onClick = { importMenu = false; onCreateFromImages() },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            SearchRow(state, viewModel::onIntent)
            if (state.allTags.isNotEmpty() || state.favoritesOnly) TagRow(state, viewModel::onIntent)
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.totalCount == 0 -> EmptyLibrary(
                    onImport = { pickPdfs.launch(arrayOf("application/pdf")) },
                    onImages = onCreateFromImages,
                )
                state.scores.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("조건에 맞는 악보가 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.scores, key = { it.id }) { score ->
                        ScoreCard(
                            score = score,
                            selected = score.id in state.selection,
                            selecting = state.selecting,
                            onIntent = viewModel::onIntent,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    state.editing?.let { score ->
        ScoreInfoDialog(
            score = score,
            knownTags = state.allTags,
            onSave = { t, c, g, k, tags -> viewModel.onIntent(LibraryIntent.SaveInfo(score.id, t, c, g, k, tags)) },
            onDismiss = { viewModel.onIntent(LibraryIntent.DismissDialog) },
        )
    }
    if (state.pendingDelete.isNotEmpty()) {
        ConfirmDialog(
            title = "악보 삭제",
            message = "${state.pendingDelete.size}개의 악보와 필기, 녹음이 모두 삭제됩니다. 되돌릴 수 없습니다.",
            confirmText = "삭제",
            destructive = true,
            onConfirm = { viewModel.onIntent(LibraryIntent.ConfirmDelete) },
            onDismiss = { viewModel.onIntent(LibraryIntent.DismissDialog) },
        )
    }
    if (state.mergeDialogOpen) {
        TextInputDialog(
            title = "악보 ${state.selection.size}개 합치기",
            initial = state.scores.firstOrNull { it.id in state.selection }?.title?.let { "$it 외" } ?: "합친 악보",
            label = "새 악보 이름",
            confirmText = "합치기",
            onConfirm = { viewModel.onIntent(LibraryIntent.ConfirmMerge(it)) },
            onDismiss = { viewModel.onIntent(LibraryIntent.DismissDialog) },
        )
    }
    if (state.setlistPickerOpen) {
        SetlistPickerDialog(
            setlists = state.setlists,
            onPick = { viewModel.onIntent(LibraryIntent.AddToSetlist(it, null)) },
            onCreate = { viewModel.onIntent(LibraryIntent.AddToSetlist(null, it)) },
            onDismiss = { viewModel.onIntent(LibraryIntent.DismissDialog) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(state: LibraryState, onIntent: (LibraryIntent) -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = { onIntent(LibraryIntent.ClearSelection) }) { Icon(Icons.Default.Close, "선택 해제") }
        },
        title = { Text("${state.selection.size}개 선택") },
        actions = {
            IconButton(onClick = { onIntent(LibraryIntent.SelectAll) }) { Icon(Icons.Default.SelectAll, "모두 선택") }
            IconButton(onClick = { onIntent(LibraryIntent.RequestMerge) }) { Icon(Icons.AutoMirrored.Filled.CallMerge, "합치기") }
            IconButton(onClick = { onIntent(LibraryIntent.RequestAddToSetlist(state.selection)) }) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "셋리스트에 추가")
            }
            IconButton(onClick = { onIntent(LibraryIntent.RequestDelete(state.selection)) }) { Icon(Icons.Default.Delete, "삭제") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

@Composable
private fun SortMenu(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "정렬") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                LibrarySort.RECENT to "최근에 연 순",
                LibrarySort.TITLE to "제목순",
                LibrarySort.COMPOSER to "작곡가순",
                LibrarySort.ADDED to "추가한 순",
            ).forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, fontWeight = if (value == sort) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onSort(value) },
                )
            }
        }
    }
}

@Composable
private fun SearchRow(state: LibraryState, onIntent: (LibraryIntent) -> Unit) {
    OutlinedTextField(
        value = state.query,
        onValueChange = { onIntent(LibraryIntent.QueryChanged(it)) },
        placeholder = { Text("제목, 작곡가, 태그 검색 (초성 가능)") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (state.query.isNotEmpty()) {
                IconButton(onClick = { onIntent(LibraryIntent.QueryChanged("")) }) { Icon(Icons.Default.Close, "지우기") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun TagRow(state: LibraryState, onIntent: (LibraryIntent) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = state.favoritesOnly,
                onClick = { onIntent(LibraryIntent.ToggleFavoritesOnly) },
                label = { Text("즐겨찾기") },
                leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(18.dp)) },
            )
        }
        items(state.allTags) { tag ->
            FilterChip(
                selected = state.selectedTag == tag,
                onClick = { onIntent(LibraryIntent.TagSelected(if (state.selectedTag == tag) null else tag)) },
                label = { Text(tag) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScoreCard(
    score: Score,
    selected: Boolean,
    selecting: Boolean,
    onIntent: (LibraryIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { onIntent(LibraryIntent.ScoreClicked(score.id)) },
                onLongClick = { onIntent(LibraryIntent.ScoreLongPressed(score.id)) },
            )
            .padding(4.dp),
    ) {
        Box {
            val thumb = rememberLibraryFile(score.thumbnailPath)
            Surface(
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 3.dp,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .then(
                        if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(8.dp))
                        else Modifier
                    ),
            ) {
                if (thumb != null) {
                    AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.MusicNote, null, tint = Color.LightGray, modifier = Modifier.size(48.dp))
                    }
                }
            }
            if (selecting) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.secondary else Color.Black.copy(alpha = 0.2f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp)
                        .background(Color.White, CircleShape),
                )
            } else if (score.favorite) {
                Icon(
                    Icons.Default.Star, null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(score.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = listOf(score.composer, "${score.pageCount}쪽").filter { it.isNotBlank() }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, "더보기", Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem("정보 편집", Icons.Default.Edit) { menu = false; onIntent(LibraryIntent.EditInfo(score)) }
                    MenuItem("페이지 편집", Icons.AutoMirrored.Filled.ViewQuilt) { menu = false; onIntent(LibraryIntent.OpenPageEditor(score.id)) }
                    MenuItem(if (score.favorite) "즐겨찾기 해제" else "즐겨찾기", if (score.favorite) Icons.Default.StarBorder else Icons.Default.Star) {
                        menu = false; onIntent(LibraryIntent.ToggleFavorite(score.id))
                    }
                    MenuItem("셋리스트에 추가", Icons.AutoMirrored.Filled.PlaylistAdd) {
                        menu = false; onIntent(LibraryIntent.RequestAddToSetlist(setOf(score.id)))
                    }
                    MenuItem("삭제", Icons.Default.Delete) { menu = false; onIntent(LibraryIntent.RequestDelete(setOf(score.id))) }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, onImages: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.MusicNote, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text("아직 악보가 없습니다", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "PDF 악보를 불러오거나, 종이 악보를 찍은 사진으로 악보를 만들어 보세요.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Button(onClick = onImport) {
                Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("PDF 불러오기")
            }
            androidx.compose.material3.OutlinedButton(onClick = onImages) {
                Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("사진으로 만들기")
            }
        }
    }
}

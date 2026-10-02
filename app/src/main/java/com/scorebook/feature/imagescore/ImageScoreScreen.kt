package com.scorebook.feature.imagescore

import com.scorebook.ui.components.isCompactWidth
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageScoreScreen(
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
    viewModel: ImageScoreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) viewModel.onIntent(ImageScoreIntent.AddImages(uris))
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.onIntent(ImageScoreIntent.AddImages(uris))
    }
    val createPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) viewModel.onIntent(ImageScoreIntent.SavePdf(uri))
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect {
            when (it) {
                is ImageScoreEffect.Created -> onCreated(it.scoreId)
                is ImageScoreEffect.CreateDocument -> createPdf.launch(it.name)
                is ImageScoreEffect.Message -> launch { snackbar.showSnackbar(it.text) }
            }
        }
    }
    val gridState = rememberLazyGridState()
    val reorder = rememberReorderableLazyGridState(gridState) { from, to ->
        viewModel.onIntent(ImageScoreIntent.Move(from.index, to.index))
    }

    val compact = isCompactWidth()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                title = {
                    Text(
                        if (compact) "사진 악보" else "사진으로 악보 만들기",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    TextButton(onClick = { viewModel.onIntent(ImageScoreIntent.RequestPdf) }, enabled = !state.busy && state.items.isNotEmpty()) {
                        Icon(Icons.Default.PictureAsPdf, "PDF로 저장"); if (!compact) { Spacer(Modifier.width(4.dp)); Text("PDF로 저장") }
                    }
                    Button(
                        onClick = { viewModel.onIntent(ImageScoreIntent.Create) },
                        enabled = !state.busy && state.items.isNotEmpty(),
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(if (compact) "만들기" else "악보 만들기") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val titleField: @Composable (Modifier) -> Unit = { m ->
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { viewModel.onIntent(ImageScoreIntent.TitleChanged(it)) },
                    label = { Text("악보 제목") },
                    singleLine = true,
                    modifier = m,
                )
            }
            val pickButtons: @Composable () -> Unit = {
                Button(onClick = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("사진 선택")
                }
                OutlinedButton(onClick = { pickFiles.launch(arrayOf("image/*")) }) {
                    Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("파일에서")
                }
            }
            if (compact) {
                // 좁은 화면: 제목 칸을 한 줄 전체로, 버튼은 그 아래에
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    titleField(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { pickButtons() }
                }
            } else {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    titleField(Modifier.weight(1f))
                    pickButtons()
                }
            }
            if (state.items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "종이 악보를 찍은 사진을 여러 장 고르세요.\n선택한 순서대로 페이지가 되고, 길게 눌러 끌면 순서를 바꿀 수 있어요.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "${state.items.size}장 · 길게 눌러 끌어서 순서 변경 · 회전 버튼으로 방향 맞추기",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(state.items, key = { _, it -> it.key }) { index, item ->
                        ReorderableItem(reorder, key = item.key) { dragging ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                shadowElevation = if (dragging) 12.dp else 2.dp,
                                modifier = Modifier.longPressDraggableHandle(),
                            ) {
                                Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(Color.White)) {
                                    AsyncImage(
                                        model = item.uri,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize().padding(4.dp).rotate(item.rotation.toFloat()),
                                    )
                                    Text(
                                        "${index + 1}",
                                        color = Color.White,
                                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                                            .clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                    Row(Modifier.align(Alignment.TopEnd)) {
                                        SmallButton({ viewModel.onIntent(ImageScoreIntent.Rotate(item.key)) }) {
                                            Icon(Icons.AutoMirrored.Filled.RotateRight, "회전", Modifier.size(18.dp))
                                        }
                                        SmallButton({ viewModel.onIntent(ImageScoreIntent.Remove(item.key)) }) {
                                            Icon(Icons.Default.Close, "빼기", Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    // IconButton 은 최소 터치 영역(48dp)을 강제해 작은 버튼끼리 겹치므로 직접 구성
    Box(
        Modifier.padding(3.dp).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Color.White,
        ) { content() }
    }
}

package com.scorebook.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.scorebook.core.model.Score
import com.scorebook.core.model.Setlist

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoreInfoDialog(
    score: Score,
    knownTags: List<String>,
    onSave: (title: String, composer: String, genre: String, key: String, tags: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(score.title) }
    var composer by remember { mutableStateOf(score.composer) }
    var genre by remember { mutableStateOf(score.genre) }
    var key by remember { mutableStateOf(score.musicalKey) }
    val tags = remember { mutableStateListOf<String>().apply { addAll(score.tags) } }
    var tagInput by remember { mutableStateOf("") }

    fun addTag(raw: String) {
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && it !in tags }.forEach { tags += it }
        tagInput = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("악보 정보") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("제목") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(composer, { composer = it }, label = { Text("작곡가") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(genre, { genre = it }, label = { Text("장르") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(key, { key = it }, label = { Text("조성") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { if (it.endsWith(",")) addTag(it) else tagInput = it },
                    label = { Text("태그 추가 (쉼표로 구분)") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { addTag(tagInput) }, enabled = tagInput.isNotBlank()) { Text("추가") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        InputChip(selected = true, onClick = { tags.remove(tag) }, label = { Text("$tag  ✕") })
                    }
                }
                val suggestions = knownTags.filter { it !in tags }.take(12)
                if (suggestions.isNotEmpty()) {
                    Text("기존 태그", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { tag -> AssistChip(onClick = { tags += tag }, label = { Text(tag) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (tagInput.isNotBlank()) addTag(tagInput)
                onSave(title.trim(), composer.trim(), genre.trim(), key.trim(), tags.toList())
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
fun SetlistPickerDialog(
    setlists: List<Setlist>,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("셋리스트에 추가") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(setlists, key = { it.id }) { s ->
                        ListItem(
                            headlineContent = { Text(s.name) },
                            supportingContent = { Text("${s.itemCount}곡") },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                            modifier = Modifier.clickable { onPick(s.id) },
                        )
                    }
                }
                if (setlists.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("새 셋리스트 이름") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = { onCreate(newName.trim()) }, enabled = newName.isNotBlank()) {
                            Icon(Icons.Default.Add, null); Text("만들기")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

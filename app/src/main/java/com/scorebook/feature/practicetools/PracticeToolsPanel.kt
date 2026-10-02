package com.scorebook.feature.practicetools

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scorebook.core.audio.Metronome
import com.scorebook.core.model.AudioKind
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun PracticeToolsPanel(
    onClose: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PracticeToolsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingMicAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingMicAction?.invoke() else onMessage("마이크 권한이 필요합니다")
        pendingMicAction = null
    }
    fun withMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingMicAction = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { if (it is PracticeToolsEffect.Message) onMessage(it.text) }
    }
    DisposableEffect(Unit) { onDispose { viewModel.onIntent(PracticeToolsIntent.PanelClosed) } }

    Surface(
        modifier = modifier.fillMaxHeight().width(360.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp)) {
                Text("연습 도구", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "닫기") }
            }
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                Tab(state.tab == ToolsTab.METRONOME, { viewModel.onIntent(PracticeToolsIntent.SelectTab(ToolsTab.METRONOME)) }, text = { Text("메트로놈") })
                Tab(state.tab == ToolsTab.PIANO, { viewModel.onIntent(PracticeToolsIntent.SelectTab(ToolsTab.PIANO)) }, text = { Text("피아노") })
                Tab(state.tab == ToolsTab.AUDIO, { viewModel.onIntent(PracticeToolsIntent.SelectTab(ToolsTab.AUDIO)) }, text = { Text("녹음·음원") })
            }
            when (state.tab) {
                ToolsTab.METRONOME -> MetronomeTab(state.metronome, viewModel::onIntent)
                ToolsTab.PIANO -> PianoTab(state, viewModel::onIntent)
                ToolsTab.AUDIO -> AudioTab(state, viewModel::onIntent, withMic = ::withMic)
            }
        }
    }
}

@Composable
private fun MetronomeTab(m: Metronome.State, onIntent: (PracticeToolsIntent) -> Unit) {
    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(m.beatsPerBar) { i ->
                val active = m.running && m.currentBeat == i
                Box(
                    Modifier.size(if (m.beatsPerBar > 8) 16.dp else 22.dp).clip(CircleShape).background(
                        when {
                            active && i == 0 -> MaterialTheme.colorScheme.secondary
                            active -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onIntent(PracticeToolsIntent.SetBpm(m.bpm - 1)) }) { Icon(Icons.Default.Remove, "느리게") }
            Text("${m.bpm}", fontSize = 64.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(horizontal = 12.dp))
            IconButton(onClick = { onIntent(PracticeToolsIntent.SetBpm(m.bpm + 1)) }) { Icon(Icons.Default.Add, "빠르게") }
        }
        Text("BPM · ${tempoName(m.bpm)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = m.bpm.toFloat(),
            onValueChange = { onIntent(PracticeToolsIntent.SetBpm(it.roundToInt())) },
            valueRange = Metronome.MIN_BPM.toFloat()..Metronome.MAX_BPM.toFloat(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("박자", modifier = Modifier.padding(end = 8.dp))
            IconButton(onClick = { onIntent(PracticeToolsIntent.SetBeats(m.beatsPerBar - 1)) }) { Icon(Icons.Default.Remove, null) }
            Text("${m.beatsPerBar}/4", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onIntent(PracticeToolsIntent.SetBeats(m.beatsPerBar + 1)) }) { Icon(Icons.Default.Add, null) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onIntent(PracticeToolsIntent.TapTempo) }) { Text("탭 템포") }
            Button(onClick = { onIntent(PracticeToolsIntent.ToggleMetronome) }) {
                Icon(if (m.running) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(if (m.running) "정지" else "시작")
            }
        }
        Text(
            "패널을 닫아도 메트로놈은 계속 울립니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

private fun tempoName(bpm: Int) = when {
    bpm < 60 -> "Largo"
    bpm < 76 -> "Adagio"
    bpm < 108 -> "Andante"
    bpm < 120 -> "Moderato"
    bpm < 168 -> "Allegro"
    else -> "Presto"
}

@Composable
private fun AudioTab(state: PracticeToolsState, onIntent: (PracticeToolsIntent) -> Unit, withMic: (() -> Unit) -> Unit) {
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onIntent(PracticeToolsIntent.AttachAudio(uri))
    }
    Column(Modifier.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val rec = state.recording.recording
            Button(
                onClick = { if (rec) onIntent(PracticeToolsIntent.ToggleRecording) else withMic { onIntent(PracticeToolsIntent.ToggleRecording) } },
                colors = ButtonDefaults.buttonColors(containerColor = if (rec) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary),
            ) {
                Icon(if (rec) Icons.Default.Stop else Icons.Default.FiberManualRecord, null)
                Spacer(Modifier.width(6.dp))
                if (rec) RecordingTimer(state.recording.startedAt) else Text("녹음")
            }
            OutlinedButton(onClick = { pickAudio.launch(arrayOf("audio/*")) }) {
                Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(6.dp)); Text("음원 첨부")
            }
        }
        val p = state.player
        if (p.clipId != null) {
            Spacer(Modifier.height(12.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onIntent(PracticeToolsIntent.TogglePlay) }) {
                            Icon(if (p.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "재생")
                        }
                        Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f))
                        Text("${formatMs(p.positionMs)} / ${formatMs(p.durationMs)}", style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = p.positionMs.toFloat(),
                        onValueChange = { onIntent(PracticeToolsIntent.Seek(it.toLong())) },
                        valueRange = 0f..p.durationMs.coerceAtLeast(1).toFloat(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.5f, 0.75f, 1f, 1.25f).forEach { sp ->
                            FilterChip(selected = p.speed == sp, onClick = { onIntent(PracticeToolsIntent.SetSpeed(sp)) }, label = { Text("${sp}x") })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = p.loopStartMs != null, onClick = { onIntent(PracticeToolsIntent.MarkA) }, label = { Text("A " + (p.loopStartMs?.let(::formatMs) ?: "")) })
                        FilterChip(selected = p.loopEndMs != null, onClick = { onIntent(PracticeToolsIntent.MarkB) }, label = { Text("B " + (p.loopEndMs?.let(::formatMs) ?: "")) })
                        if (p.loopStartMs != null || p.loopEndMs != null) {
                            FilterChip(selected = false, onClick = { onIntent(PracticeToolsIntent.ClearLoop) }, label = { Text("구간 해제") })
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        if (state.clips.isEmpty()) {
            Text("녹음이나 첨부한 음원이 여기에 표시됩니다. 음원은 속도 조절과 A-B 구간 반복으로 연습할 수 있어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn {
            items(state.clips, key = { it.id }) { clip ->
                ListItem(
                    headlineContent = { Text(clip.title, maxLines = 1) },
                    supportingContent = { Text((if (clip.kind == AudioKind.RECORDING) "녹음" else "음원") + " · " + formatMs(clip.durationMs)) },
                    leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                    trailingContent = {
                        IconButton(onClick = { onIntent(PracticeToolsIntent.DeleteClip(clip)) }) { Icon(Icons.Default.Delete, "삭제") }
                    },
                    modifier = Modifier.clickable { onIntent(PracticeToolsIntent.PlayClip(clip)) },
                )
            }
        }
    }
}

@Composable
private fun RecordingTimer(startedAt: Long) {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    Text("정지 " + formatMs(now - startedAt))
}

private fun formatMs(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

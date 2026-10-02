package com.scorebook.feature.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.scorebook.core.data.PracticeRepository
import com.scorebook.core.model.PracticeDay
import com.scorebook.core.model.PracticeTotal
import com.scorebook.core.mvi.MviViewModel
import com.scorebook.core.mvi.UiEffect
import com.scorebook.core.mvi.UiIntent
import com.scorebook.core.mvi.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class PracticeState(
    val days: List<PracticeDay> = emptyList(),
    val totals: List<PracticeTotal> = emptyList(),
) : UiState {
    val weekSeconds: Long get() = days.takeLast(7).sumOf { it.seconds }
    val todaySeconds: Long get() = days.lastOrNull()?.seconds ?: 0
    val streak: Int get() = days.reversed().takeWhile { it.seconds > 0 }.size
}

sealed interface PracticeIntent : UiIntent
sealed interface PracticeEffect : UiEffect

@HiltViewModel
class PracticeViewModel @Inject constructor(repo: PracticeRepository) :
    MviViewModel<PracticeState, PracticeIntent, PracticeEffect>(PracticeState()) {

    init {
        viewModelScope.launch {
            combine(repo.observeDaily(14), repo.observeTotals()) { d, t -> d to t }.collect { (d, t) ->
                setState { copy(days = d, totals = t) }
            }
        }
    }

    override fun handleIntent(intent: PracticeIntent) = Unit
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}시간 ${m}분"
        m > 0 -> "${m}분"
        else -> "${seconds}초"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(onOpenScore: (Long) -> Unit, viewModel: PracticeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("연습 기록", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(padding)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("오늘", formatDuration(state.todaySeconds), Modifier.weight(1f))
                    StatCard("최근 7일", formatDuration(state.weekSeconds), Modifier.weight(1f))
                    StatCard("연속 연습", "${state.streak}일", Modifier.weight(1f))
                }
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("최근 2주", style = MaterialTheme.typography.titleMedium)
                        DailyChart(state.days, Modifier.fillMaxWidth().height(180.dp).padding(top = 12.dp))
                    }
                }
            }
            item {
                Text("곡별 누적", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (state.totals.isEmpty()) {
                    Text(
                        "악보를 열어 두면 연습 시간이 자동으로 기록됩니다 (20초 이상).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.totals, key = { it.scoreId }) { t ->
                ListItem(
                    headlineContent = { Text(t.title) },
                    supportingContent = { Text("${t.sessions}회") },
                    trailingContent = { Text(formatDuration(t.seconds), style = MaterialTheme.typography.titleSmall) },
                    modifier = Modifier.clickable { onOpenScore(t.scoreId) },
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DailyChart(days: List<PracticeDay>, modifier: Modifier) {
    val bar = MaterialTheme.colorScheme.secondary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    Canvas(modifier) {
        if (days.isEmpty()) return@Canvas
        val max = (days.maxOf { it.seconds }).coerceAtLeast(60)
        val labelSpace = 18.dp.toPx()
        val chartH = size.height - labelSpace
        val slot = size.width / days.size
        val w = slot * 0.6f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = label; textSize = 11.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
        }
        val cal = Calendar.getInstance()
        days.forEachIndexed { i, d ->
            val h = (d.seconds.toFloat() / max) * chartH
            val x = i * slot + (slot - w) / 2
            drawRoundRect(
                if (d.seconds > 0) bar else empty,
                topLeft = Offset(x, chartH - h.coerceAtLeast(3f)),
                size = Size(w, h.coerceAtLeast(3f)),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            cal.timeInMillis = d.dayStartMillis
            drawIntoCanvas {
                it.nativeCanvas.drawText("${cal.get(Calendar.DAY_OF_MONTH)}", x + w / 2, size.height - 4.dp.toPx(), paint)
            }
        }
    }
}


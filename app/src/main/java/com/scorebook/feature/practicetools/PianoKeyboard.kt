package com.scorebook.feature.practicetools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scorebook.core.audio.PianoSynth

private val WHITE_OFFSETS = intArrayOf(0, 2, 4, 5, 7, 9, 11)
/** 흰건반 인덱스(옥타브 내) 뒤에 검은건반이 있는지: C D (E) F G A (B) */
private val HAS_BLACK = booleanArrayOf(true, true, false, true, true, true, false)
private const val WHITE_KEYS = 15 // 2옥타브 + 마지막 C

@Composable
fun PianoTab(state: PracticeToolsState, onIntent: (PracticeToolsIntent) -> Unit) {
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val note = state.lastNote
        Text(
            note?.let { PianoSynth.noteName(it) } ?: "–",
            fontSize = 52.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            note?.let { "${PianoSynth.solfege(it)} · %.1f Hz".format(PianoSynth.frequency(it)) } ?: "건반을 눌러 음을 확인하세요",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (!state.pianoReady) {
            Text("피아노 소리를 준비하는 중…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(
                onClick = { onIntent(PracticeToolsIntent.ShiftOctave(-1)) },
                enabled = state.pianoOctave > PracticeToolsViewModel.MIN_OCTAVE,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "낮은 옥타브") }
            Text(
                "C${state.pianoOctave} ~ C${state.pianoOctave + 2}",
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(
                onClick = { onIntent(PracticeToolsIntent.ShiftOctave(1)) },
                enabled = state.pianoOctave < PracticeToolsViewModel.MAX_OCTAVE,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "높은 옥타브") }
        }
        PianoKeyboard(
            startOctave = state.pianoOctave,
            onNote = { onIntent(PracticeToolsIntent.PlayNote(it)) },
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
        Text(
            "C4 = 가운데 도 · 여러 건반을 동시에 누르거나 미끄러지듯 쳐도 됩니다",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun PianoKeyboard(startOctave: Int, onNote: (Int) -> Unit, modifier: Modifier = Modifier) {
    val pressed = remember { mutableStateMapOf<PointerId, Int>() }
    val currentOnNote = rememberUpdatedState(onNote)
    val base = 12 * (startOctave + 1)
    val highlight = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    fun whiteMidi(i: Int) = base + 12 * (i / 7) + WHITE_OFFSETS[i % 7]

    Canvas(
        modifier.pointerInput(base) {
            fun keyAt(p: Offset): Int? {
                val ww = size.width.toFloat() / WHITE_KEYS
                val h = size.height.toFloat()
                if (p.x < 0 || p.x >= size.width || p.y < 0 || p.y > h) return null
                val i = (p.x / ww).toInt().coerceIn(0, WHITE_KEYS - 1)
                if (p.y < h * 0.62f) {
                    // 검은건반이 위에 있으므로 먼저 판정
                    val bw = ww * 0.6f
                    for (j in listOf(i - 1, i)) {
                        if (j < 0 || j >= WHITE_KEYS - 1 || !HAS_BLACK[j % 7]) continue
                        val cx = (j + 1) * ww
                        if (p.x in (cx - bw / 2)..(cx + bw / 2)) return whiteMidi(j) + 1
                    }
                }
                return whiteMidi(i)
            }
            fun handle(c: androidx.compose.ui.input.pointer.PointerInputChange) {
                if (c.pressed) {
                    val key = keyAt(c.position)
                    if (key != null && pressed[c.id] != key) {
                        pressed[c.id] = key
                        currentOnNote.value(key)
                    } else if (key == null) {
                        pressed.remove(c.id)
                    }
                } else {
                    pressed.remove(c.id)
                }
                c.consume()
            }
            awaitEachGesture {
                // 첫 손가락은 누르는 순간 바로 소리 (짧게 톡 쳐도 울리도록)
                handle(awaitFirstDown(requireUnconsumed = false))
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach(::handle)
                } while (event.changes.any { it.pressed })
                pressed.clear()
            }
        },
    ) {
        val ww = size.width / WHITE_KEYS
        val h = size.height
        val down = pressed.values.toSet()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = 11.dp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        for (i in 0 until WHITE_KEYS) {
            val midi = whiteMidi(i)
            val x = i * ww
            drawRoundRect(
                if (midi in down) highlight else Color.White,
                topLeft = Offset(x + 1, 0f), size = Size(ww - 2, h),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            drawRoundRect(
                outline, topLeft = Offset(x + 1, 0f), size = Size(ww - 2, h),
                cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()),
            )
            val label = if (midi % 12 == 0) PianoSynth.noteName(midi) else PianoSynth.noteName(midi).dropLast(1)
            drawIntoCanvas { it.nativeCanvas.drawText(label, x + ww / 2, h - 8.dp.toPx(), paint) }
        }
        val bw = ww * 0.6f
        for (i in 0 until WHITE_KEYS - 1) {
            if (!HAS_BLACK[i % 7]) continue
            val midi = whiteMidi(i) + 1
            val cx = (i + 1) * ww
            drawRoundRect(
                if (midi in down) highlight else Color(0xFF1E1E22),
                topLeft = Offset(cx - bw / 2, 0f), size = Size(bw, h * 0.62f),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}

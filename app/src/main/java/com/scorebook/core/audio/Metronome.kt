package com.scorebook.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 샘플 단위로 정확한 메트로놈. 박마다 "클릭 + 무음" PCM 을 AudioTrack 에 연속으로 써서
 * 타이머 지터 없이 박자가 유지된다. 화면을 나가도 계속 울리도록 싱글톤.
 */
@Singleton
class Metronome @Inject constructor() {

    data class State(
        val running: Boolean = false,
        val bpm: Int = 100,
        val beatsPerBar: Int = 4,
        val currentBeat: Int = -1,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var thread: Thread? = null
    private val tapTimes = ArrayDeque<Long>()

    private val accentClick by lazy { click(1760.0) }
    private val normalClick by lazy { click(1175.0) }

    fun configure(bpm: Int, beats: Int) {
        _state.update { it.copy(bpm = bpm.coerceIn(MIN_BPM, MAX_BPM), beatsPerBar = beats.coerceIn(1, 12)) }
    }

    fun setBpm(bpm: Int) = _state.update { it.copy(bpm = bpm.coerceIn(MIN_BPM, MAX_BPM)) }

    fun setBeats(beats: Int) = _state.update { it.copy(beatsPerBar = beats.coerceIn(1, 12)) }

    fun toggle() = if (_state.value.running) stop() else start()

    /** 탭 템포: 최근 탭 간격의 평균으로 BPM 계산 */
    fun tap() {
        val now = SystemClock.elapsedRealtime()
        if (tapTimes.isNotEmpty() && now - tapTimes.last() > 2000) tapTimes.clear()
        tapTimes.addLast(now)
        while (tapTimes.size > 6) tapTimes.removeFirst()
        if (tapTimes.size >= 2) {
            val avg = (tapTimes.last() - tapTimes.first()).toDouble() / (tapTimes.size - 1)
            setBpm((60000.0 / avg).roundToInt())
        }
    }

    fun start() {
        if (thread != null) return
        _state.update { it.copy(running = true, currentBeat = -1) }
        thread = Thread({ loop() }, "metronome").apply { start() }
    }

    fun stop() {
        thread?.interrupt()
        thread = null
        _state.update { it.copy(running = false, currentBeat = -1) }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
        try {
            track.play()
            var beat = 0
            var carry = 0.0
            while (!Thread.currentThread().isInterrupted) {
                val s = _state.value
                if (!s.running) break
                val exact = SAMPLE_RATE * 60.0 / s.bpm + carry
                val length = exact.toInt()
                carry = exact - length
                val idx = beat % s.beatsPerBar
                val buffer = ShortArray(length)
                val clickData = if (idx == 0 && s.beatsPerBar > 1) accentClick else normalClick
                System.arraycopy(clickData, 0, buffer, 0, minOf(clickData.size, length))
                _state.update { it.copy(currentBeat = idx) }
                var written = 0
                while (written < length && !Thread.currentThread().isInterrupted) {
                    val n = track.write(buffer, written, length - written)
                    if (n <= 0) break
                    written += n
                }
                beat++
            }
        } catch (_: Exception) {
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }

    private fun click(freq: Double): ShortArray {
        val n = (SAMPLE_RATE * 0.035).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val env = exp(-t * 90)
            (sin(2 * PI * freq * t) * env * Short.MAX_VALUE * 0.8).toInt().toShort()
        }
    }

    companion object {
        const val SAMPLE_RATE = 48000
        const val MIN_BPM = 30
        const val MAX_BPM = 300
    }
}

package com.scorebook.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * 음정 확인용 간단한 피아노 음원.
 *
 * 음원 파일을 앱에 넣지 않고, 처음 쓸 때 배음 + 감쇠로 피아노 비슷한 소리를 합성해 캐시에 WAV 로 저장한 뒤
 * SoundPool 로 재생한다(지연이 짧고 여러 음을 동시에 칠 수 있음).
 */
@Singleton
class PianoSynth @Inject constructor(@ApplicationContext private val context: Context) {

    private var pool: SoundPool? = null
    private val soundIds = ConcurrentHashMap<Int, Int>()
    private val loaded = ConcurrentHashMap.newKeySet<Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun prepare() {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(12)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .build()
        p.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded += sampleId
            if (loaded.size >= (MAX_MIDI - MIN_MIDI + 1) / 2) _ready.value = true
        }
        pool = p
        scope.launch {
            val dir = File(context.cacheDir, "piano").apply { mkdirs() }
            // 자주 쓰는 가운데 음역부터 준비
            (MIN_MIDI..MAX_MIDI).sortedBy { abs(it - 62) }.forEach { midi ->
                val file = File(dir, "v1_$midi.wav")
                if (!file.exists() || file.length() < 1000) writeWav(file, midi)
                soundIds[midi] = p.load(file.absolutePath, 1)
            }
        }
    }

    fun play(midi: Int) {
        val id = soundIds[midi] ?: return
        if (id !in loaded) return
        // 높은 음일수록 조금 작게 → 고르게 들림
        val volume = (1f - (midi - 60) * 0.008f).coerceIn(0.6f, 1f)
        pool?.play(id, volume, volume, 1, 0, 1f)
    }

    private fun writeWav(file: File, midi: Int) {
        val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val n = (SAMPLE_RATE * DURATION).toInt()
        val data = DoubleArray(n)
        val decay = 1.1 + (midi - MIN_MIDI) * 0.045
        val nyquist = SAMPLE_RATE / 2.0 * 0.9
        var peak = 0.0
        for (h in HARMONICS.indices) {
            val hf = freq * (h + 1) * (1 + 0.0004 * h * h) // 약간의 비조화성 → 현 느낌
            if (hf >= nyquist) break
            val amp = HARMONICS[h]
            val hDecay = decay * (1 + h * 0.55)
            val w = 2 * PI * hf / SAMPLE_RATE
            val step = exp(-hDecay / SAMPLE_RATE)
            var env = amp
            for (i in 0 until n) {
                data[i] += sin(w * i) * env
                env *= step
            }
        }
        val attack = (SAMPLE_RATE * 0.004).toInt()
        val release = (SAMPLE_RATE * 0.15).toInt()
        for (i in 0 until n) {
            if (i < attack) data[i] *= i.toDouble() / attack
            if (i > n - release) data[i] *= (n - i).toDouble() / release
            peak = maxOf(peak, abs(data[i]))
        }
        val gain = if (peak > 0) 0.85 * Short.MAX_VALUE / peak else 0.0

        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.writeBytes("RIFF"); le32(36 + n * 2); out.writeBytes("WAVE")
            out.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(SAMPLE_RATE); le32(SAMPLE_RATE * 2); le16(2); le16(16)
            out.writeBytes("data"); le32(n * 2)
            for (v in data) le16((v * gain).toInt())
        }
        tmp.renameTo(file)
    }

    companion object {
        const val MIN_MIDI = 36 // C2
        const val MAX_MIDI = 96 // C7
        private const val SAMPLE_RATE = 22050
        private const val DURATION = 1.8
        private val HARMONICS = doubleArrayOf(1.0, 0.55, 0.32, 0.2, 0.12, 0.08, 0.05)

        private val NAMES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        private val SOLFEGE = listOf("도", "도♯", "레", "미♭", "미", "파", "파♯", "솔", "솔♯", "라", "시♭", "시")

        fun noteName(midi: Int) = NAMES[midi % 12] + (midi / 12 - 1)
        fun solfege(midi: Int) = SOLFEGE[midi % 12]
        fun frequency(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    }
}

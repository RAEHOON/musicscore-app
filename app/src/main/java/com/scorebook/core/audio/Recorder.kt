package com.scorebook.core.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.scorebook.core.storage.LibraryFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 연습 녹음 (AAC .m4a). 마이크 권한은 호출 측에서 확인한다. */
@Singleton
class Recorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: LibraryFiles,
) {
    data class State(val recording: Boolean = false, val startedAt: Long = 0L)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var currentPath: String? = null

    fun start(): Boolean {
        if (recorder != null) return false
        val (rel, file) = files.newAudio("m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            currentPath = rel
            _state.value = State(true, SystemClock.elapsedRealtime())
            true
        }.getOrElse {
            r.release()
            file.delete()
            false
        }
    }

    /** 녹음 종료. 저장된 상대 경로(실패 시 null) */
    fun stop(): String? {
        val r = recorder ?: return null
        val path = currentPath
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        recorder = null
        currentPath = null
        _state.value = State()
        if (!ok) {
            files.delete(path)
            return null
        }
        return path
    }
}

package com.scorebook.core.audio

import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 첨부 음원/녹음 재생기: 속도 조절 + A-B 구간 반복 */
@Singleton
class AudioPlayer @Inject constructor() {

    data class State(
        val clipId: Long? = null,
        val title: String = "",
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val speed: Float = 1f,
        val loopStartMs: Long? = null,
        val loopEndMs: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    fun load(clipId: Long, title: String, file: File) {
        release()
        val mp = MediaPlayer()
        runCatching {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
        }.onFailure {
            mp.release()
            return
        }
        mp.setOnCompletionListener {
            _state.update { s -> s.copy(playing = false, positionMs = 0) }
            ticker?.cancel()
        }
        player = mp
        _state.value = State(clipId = clipId, title = title, durationMs = mp.duration.toLong())
    }

    fun togglePlay() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            _state.update { it.copy(playing = false) }
            ticker?.cancel()
        } else {
            applySpeed(mp, _state.value.speed)
            mp.start()
            _state.update { it.copy(playing = true) }
            startTicker()
        }
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.toInt())
        _state.update { it.copy(positionMs = ms) }
    }

    fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.5f, 1.5f)
        _state.update { it.copy(speed = s) }
        player?.let { if (it.isPlaying) applySpeed(it, s) }
    }

    fun markA() = _state.update { it.copy(loopStartMs = it.positionMs, loopEndMs = it.loopEndMs?.takeIf { e -> e > it.positionMs }) }

    fun markB() = _state.update { s ->
        val a = s.loopStartMs ?: 0L
        if (s.positionMs > a) s.copy(loopStartMs = a, loopEndMs = s.positionMs) else s
    }

    fun clearLoop() = _state.update { it.copy(loopStartMs = null, loopEndMs = null) }

    fun release() {
        ticker?.cancel()
        player?.release()
        player = null
        _state.value = State()
    }

    private fun applySpeed(mp: MediaPlayer, speed: Float) {
        runCatching { mp.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val mp = player ?: break
                val pos = mp.currentPosition.toLong()
                val s = _state.value
                val a = s.loopStartMs
                val b = s.loopEndMs
                if (a != null && b != null && pos >= b) {
                    mp.seekTo(a.toInt())
                    _state.update { it.copy(positionMs = a) }
                } else {
                    _state.update { it.copy(positionMs = pos) }
                }
                delay(30)
            }
        }
    }
}

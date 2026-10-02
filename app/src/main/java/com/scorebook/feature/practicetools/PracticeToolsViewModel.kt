package com.scorebook.feature.practicetools

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.scorebook.core.audio.AudioPlayer
import com.scorebook.core.audio.Metronome
import com.scorebook.core.audio.PianoSynth
import com.scorebook.core.audio.Recorder
import com.scorebook.core.data.AudioRepository
import com.scorebook.core.data.SettingsRepository
import com.scorebook.core.di.ApplicationScope
import com.scorebook.core.model.AudioClip
import com.scorebook.core.mvi.MviViewModel
import com.scorebook.core.mvi.UiEffect
import com.scorebook.core.mvi.UiIntent
import com.scorebook.core.mvi.UiState
import com.scorebook.core.storage.LibraryFiles
import com.scorebook.navigation.ViewerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class ToolsTab { METRONOME, PIANO, AUDIO }

data class PracticeToolsState(
    val tab: ToolsTab = ToolsTab.METRONOME,
    val metronome: Metronome.State = Metronome.State(),
    /** 피아노 건반 왼쪽 끝 C 의 옥타브 (C4 = 가운데 도) */
    val pianoOctave: Int = 3,
    val lastNote: Int? = null,
    val pianoReady: Boolean = false,
    val clips: List<AudioClip> = emptyList(),
    val player: AudioPlayer.State = AudioPlayer.State(),
    val recording: Recorder.State = Recorder.State(),
) : UiState

sealed interface PracticeToolsIntent : UiIntent {
    data class SelectTab(val tab: ToolsTab) : PracticeToolsIntent
    data object ToggleMetronome : PracticeToolsIntent
    data class SetBpm(val bpm: Int) : PracticeToolsIntent
    data class SetBeats(val beats: Int) : PracticeToolsIntent
    data object TapTempo : PracticeToolsIntent
    data class PlayNote(val midi: Int) : PracticeToolsIntent
    data class ShiftOctave(val delta: Int) : PracticeToolsIntent
    data class AttachAudio(val uri: Uri) : PracticeToolsIntent
    data object ToggleRecording : PracticeToolsIntent
    data class PlayClip(val clip: AudioClip) : PracticeToolsIntent
    data object TogglePlay : PracticeToolsIntent
    data class Seek(val ms: Long) : PracticeToolsIntent
    data class SetSpeed(val speed: Float) : PracticeToolsIntent
    data object MarkA : PracticeToolsIntent
    data object MarkB : PracticeToolsIntent
    data object ClearLoop : PracticeToolsIntent
    data class DeleteClip(val clip: AudioClip) : PracticeToolsIntent
    data object PanelClosed : PracticeToolsIntent
}

sealed interface PracticeToolsEffect : UiEffect {
    data class Message(val text: String) : PracticeToolsEffect
}

@HiltViewModel
class PracticeToolsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val metronome: Metronome,
    private val piano: PianoSynth,
    private val player: AudioPlayer,
    private val recorder: Recorder,
    private val audio: AudioRepository,
    private val settings: SettingsRepository,
    private val files: LibraryFiles,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : MviViewModel<PracticeToolsState, PracticeToolsIntent, PracticeToolsEffect>(PracticeToolsState()) {

    private val scoreId = savedStateHandle.toRoute<ViewerRoute>().scoreId

    init {
        viewModelScope.launch {
            val s = settings.current()
            if (!metronome.state.value.running) metronome.configure(s.metronomeBpm, s.metronomeBeats)
        }
        viewModelScope.launch { metronome.state.collect { setState { copy(metronome = it) } } }
        viewModelScope.launch { piano.ready.collect { setState { copy(pianoReady = it) } } }
        viewModelScope.launch { player.state.collect { setState { copy(player = it) } } }
        viewModelScope.launch { recorder.state.collect { setState { copy(recording = it) } } }
        viewModelScope.launch { audio.observeForScore(scoreId).collect { setState { copy(clips = it) } } }
    }

    override fun handleIntent(intent: PracticeToolsIntent) {
        when (intent) {
            is PracticeToolsIntent.SelectTab -> {
                if (intent.tab == ToolsTab.PIANO) piano.prepare()
                setState { copy(tab = intent.tab) }
            }
            PracticeToolsIntent.ToggleMetronome -> metronome.toggle()
            is PracticeToolsIntent.SetBpm -> {
                metronome.setBpm(intent.bpm)
                persistMetronome()
            }
            is PracticeToolsIntent.SetBeats -> {
                metronome.setBeats(intent.beats)
                persistMetronome()
            }
            PracticeToolsIntent.TapTempo -> {
                metronome.tap()
                persistMetronome()
            }
            is PracticeToolsIntent.PlayNote -> {
                piano.play(intent.midi)
                setState { copy(lastNote = intent.midi) }
            }
            is PracticeToolsIntent.ShiftOctave -> setState {
                copy(pianoOctave = (pianoOctave + intent.delta).coerceIn(MIN_OCTAVE, MAX_OCTAVE))
            }
            is PracticeToolsIntent.AttachAudio -> viewModelScope.launch {
                audio.importAudio(scoreId, intent.uri)
                    .onSuccess { sendEffect(PracticeToolsEffect.Message("음원을 첨부했습니다")) }
                    .onFailure { sendEffect(PracticeToolsEffect.Message("음원을 가져오지 못했습니다")) }
            }
            PracticeToolsIntent.ToggleRecording -> toggleRecording()
            is PracticeToolsIntent.PlayClip -> player.load(intent.clip.id, intent.clip.title, files.resolve(intent.clip.path)).also { player.togglePlay() }
            PracticeToolsIntent.TogglePlay -> player.togglePlay()
            is PracticeToolsIntent.Seek -> player.seekTo(intent.ms)
            is PracticeToolsIntent.SetSpeed -> player.setSpeed(intent.speed)
            PracticeToolsIntent.MarkA -> player.markA()
            PracticeToolsIntent.MarkB -> player.markB()
            PracticeToolsIntent.ClearLoop -> player.clearLoop()
            is PracticeToolsIntent.DeleteClip -> viewModelScope.launch {
                if (player.state.value.clipId == intent.clip.id) player.release()
                audio.delete(intent.clip.id)
            }
            PracticeToolsIntent.PanelClosed -> Unit
        }
    }

    private fun toggleRecording() {
        if (recorder.state.value.recording) {
            val path = recorder.stop() ?: run {
                sendEffect(PracticeToolsEffect.Message("녹음을 저장하지 못했습니다"))
                return
            }
            val title = "녹음 " + SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date())
            // 화면이 닫히는 중이어도 저장이 끝나도록 앱 스코프 사용
            appScope.launch { audio.addRecording(scoreId, path, title) }
            sendEffect(PracticeToolsEffect.Message("녹음을 저장했습니다"))
        } else {
            player.release()
            if (!recorder.start()) sendEffect(PracticeToolsEffect.Message("녹음을 시작할 수 없습니다"))
        }
    }

    private fun persistMetronome() {
        val s = metronome.state.value
        viewModelScope.launch { settings.update { it.copy(metronomeBpm = s.bpm, metronomeBeats = s.beatsPerBar) } }
    }

    override fun onCleared() {
        if (recorder.state.value.recording) toggleRecording()
        super.onCleared()
    }

    companion object {
        /** 건반은 2옥타브 + C. 표시 범위가 PianoSynth 음역(C2~C7) 안에 들도록 */
        const val MIN_OCTAVE = 2
        const val MAX_OCTAVE = 5
    }
}

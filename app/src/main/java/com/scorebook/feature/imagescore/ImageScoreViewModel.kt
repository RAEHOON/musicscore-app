package com.scorebook.feature.imagescore

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.scorebook.core.data.ScoreRepository
import com.scorebook.core.importer.ImagePageInput
import com.scorebook.core.importer.ImportResult
import com.scorebook.core.importer.ScoreImporter
import com.scorebook.core.input.ExternalIntentBus
import com.scorebook.core.mvi.MviViewModel
import com.scorebook.core.mvi.UiEffect
import com.scorebook.core.mvi.UiIntent
import com.scorebook.core.mvi.UiState
import com.scorebook.core.pdf.PdfExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

data class ImageItem(val key: String, val uri: Uri, val rotation: Int = 0)

data class ImageScoreState(
    val items: List<ImageItem> = emptyList(),
    val title: String = "사진 악보 " + SimpleDateFormat("M월 d일", Locale.KOREA).format(Date()),
    val busy: Boolean = false,
) : UiState

sealed interface ImageScoreIntent : UiIntent {
    data class AddImages(val uris: List<Uri>) : ImageScoreIntent
    data class Remove(val key: String) : ImageScoreIntent
    data class Rotate(val key: String) : ImageScoreIntent
    data class Move(val from: Int, val to: Int) : ImageScoreIntent
    data class TitleChanged(val title: String) : ImageScoreIntent
    data object Create : ImageScoreIntent
    data object RequestPdf : ImageScoreIntent
    data class SavePdf(val uri: Uri) : ImageScoreIntent
}

sealed interface ImageScoreEffect : UiEffect {
    data class Created(val scoreId: Long) : ImageScoreEffect
    data class CreateDocument(val name: String) : ImageScoreEffect
    data class Message(val text: String) : ImageScoreEffect
}

@HiltViewModel
class ImageScoreViewModel @Inject constructor(
    private val importer: ScoreImporter,
    private val scores: ScoreRepository,
    private val exporter: PdfExporter,
    externalIntents: ExternalIntentBus,
    @param:ApplicationContext private val context: Context,
) : MviViewModel<ImageScoreState, ImageScoreIntent, ImageScoreEffect>(ImageScoreState()) {

    /** PDF 저장을 먼저 누른 경우에도 악보는 한 번만 만든다 */
    private var createdId: Long? = null

    init {
        // 다른 앱에서 "공유"로 들어온 사진 (화면이 열려 있는 동안 추가로 공유해도 이어 붙임)
        viewModelScope.launch {
            externalIntents.images.filter { it.isNotEmpty() }.collect {
                handleIntent(ImageScoreIntent.AddImages(externalIntents.takeImages()))
            }
        }
    }

    override fun handleIntent(intent: ImageScoreIntent) {
        when (intent) {
            is ImageScoreIntent.AddImages -> setState {
                copy(items = items + intent.uris.map { ImageItem(UUID.randomUUID().toString(), it) })
            }
            is ImageScoreIntent.Remove -> setState { copy(items = items.filterNot { it.key == intent.key }) }
            is ImageScoreIntent.Rotate -> setState {
                copy(items = items.map { if (it.key == intent.key) it.copy(rotation = (it.rotation + 90) % 360) else it })
            }
            is ImageScoreIntent.Move -> setState {
                val list = items.toMutableList()
                if (intent.from in list.indices && intent.to in list.indices) list.add(intent.to, list.removeAt(intent.from))
                copy(items = list)
            }
            is ImageScoreIntent.TitleChanged -> setState { copy(title = intent.title) }
            ImageScoreIntent.Create -> viewModelScope.launch {
                val id = ensureCreated() ?: return@launch
                sendEffect(ImageScoreEffect.Created(id))
            }
            ImageScoreIntent.RequestPdf -> {
                if (currentState.items.isEmpty()) {
                    sendEffect(ImageScoreEffect.Message("사진을 먼저 추가하세요"))
                } else {
                    sendEffect(ImageScoreEffect.CreateDocument("${currentState.title.ifBlank { "사진 악보" }}.pdf"))
                }
            }
            is ImageScoreIntent.SavePdf -> viewModelScope.launch {
                val id = ensureCreated() ?: return@launch
                setState { copy(busy = true) }
                runCatching {
                    val pages = scores.getPages(id)
                    context.contentResolver.openOutputStream(intent.uri, "wt")?.use { out ->
                        exporter.export(pages, emptyList(), emptyList(), out)
                    } ?: error("파일을 열 수 없습니다")
                }.onSuccess {
                    sendEffect(ImageScoreEffect.Message("PDF로 저장했습니다. 악보 목록에도 추가되었어요"))
                }.onFailure {
                    sendEffect(ImageScoreEffect.Message("PDF 저장 실패: ${it.message}"))
                }
                setState { copy(busy = false) }
            }
        }
    }

    private suspend fun ensureCreated(): Long? {
        createdId?.let { return it }
        val s = currentState
        if (s.items.isEmpty()) {
            sendEffect(ImageScoreEffect.Message("사진을 먼저 추가하세요"))
            return null
        }
        setState { copy(busy = true) }
        val result = importer.importImages(s.items.map { ImagePageInput(it.uri, it.rotation) }, s.title)
        setState { copy(busy = false) }
        return when (result) {
            is ImportResult.Success -> result.scoreId.also { createdId = it }
            is ImportResult.Failure -> {
                sendEffect(ImageScoreEffect.Message(result.message))
                null
            }
            is ImportResult.Duplicate -> result.scoreId
        }
    }
}

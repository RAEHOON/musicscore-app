package com.scorebook.core.input

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** 다른 앱에서 "열기/공유"로 들어온 파일을 화면(ViewModel)에 전달 */
@Singleton
class ExternalIntentBus @Inject constructor() {
    private val _pdfs = MutableStateFlow<List<Uri>>(emptyList())
    val pdfs: StateFlow<List<Uri>> = _pdfs.asStateFlow()

    private val _images = MutableStateFlow<List<Uri>>(emptyList())
    val images: StateFlow<List<Uri>> = _images.asStateFlow()

    /** 하나의 PDF 를 "다음으로 열기" 했다면 가져온 직후 바로 연다 */
    var openAfterImport: Boolean = false

    fun offerPdfs(uris: List<Uri>, open: Boolean) {
        if (uris.isEmpty()) return
        openAfterImport = open
        _pdfs.update { it + uris }
    }

    fun offerImages(uris: List<Uri>) {
        if (uris.isNotEmpty()) _images.update { it + uris }
    }

    fun takePdfs(): List<Uri> = _pdfs.getAndUpdate { emptyList() }

    fun takeImages(): List<Uri> = _images.getAndUpdate { emptyList() }
}

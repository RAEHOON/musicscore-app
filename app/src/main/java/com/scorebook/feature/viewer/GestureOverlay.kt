package com.scorebook.feature.viewer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * 진행 중인 도구 제스처의 미리보기(올가미 선, 도형, 링크 영역, 선택 이동, 지우개 위치).
 * 매 입력마다 MVI 상태를 갱신하면 화면 전체가 재구성되므로, 일시적인 미리보기는 UI 로컬 상태로 두고
 * 제스처가 끝났을 때만 ViewModel 에 Intent 로 확정한다.
 */
@Stable
class GestureOverlay {
    var pageId by mutableLongStateOf(-1L)
    val lasso = mutableStateListOf<Offset>()
    var start by mutableStateOf<Offset?>(null)
    var end by mutableStateOf<Offset?>(null)
    var moveDelta by mutableStateOf(Offset.Zero)
    var eraser by mutableStateOf<Offset?>(null)
    /** 지우개 네모의 한 변(pt) — 미리보기용 */
    var eraserSize by mutableFloatStateOf(10f)
    var kind by mutableStateOf(Kind.NONE)

    enum class Kind { NONE, LASSO, SHAPE, LINK, MOVE, ERASER }

    fun clear() {
        pageId = -1
        lasso.clear()
        start = null
        end = null
        moveDelta = Offset.Zero
        eraser = null
        kind = Kind.NONE
    }
}

package com.scorebook.core.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 화면 상태. 불변 data class 로 구현한다. */
interface UiState

/** 사용자 의도(이벤트). View → ViewModel 단방향. */
interface UiIntent

/** 1회성 사이드 이펙트(네비게이션, 스낵바, 파일 선택기 실행 등). */
interface UiEffect

/**
 * MVI 기본 ViewModel.
 *
 * - View 는 [state] 를 구독해 그리기만 하고, 모든 입력은 [onIntent] 로 전달한다.
 * - 상태 변경은 오직 [setState] 의 reducer 를 통해서만 일어난다.
 * - 1회성 이벤트는 [effects] 채널로 내보낸다(구성 변경 시 재전달되지 않음).
 */
abstract class MviViewModel<S : UiState, I : UiIntent, E : UiEffect>(initialState: S) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = Channel<E>(Channel.BUFFERED)
    val effects: Flow<E> = _effects.receiveAsFlow()

    protected val currentState: S get() = _state.value

    fun onIntent(intent: I) = handleIntent(intent)

    protected abstract fun handleIntent(intent: I)

    protected fun setState(reducer: S.() -> S) = _state.update(reducer)

    protected fun sendEffect(effect: E) {
        viewModelScope.launch { _effects.send(effect) }
    }
}

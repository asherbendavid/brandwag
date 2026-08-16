package cvc.dashingdog.brandwag.alarm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for "is the alarm currently sounding," shared
 * between AlarmForegroundService (writer) and AlarmActivity (reader).
 *
 * Exists because the activity previously had no way to learn the service
 * had stopped (10min timeout, dismiss, future snooze) except by being the
 * one that caused it - e.g. the screen-stays-lit-after-timeout follow-up
 * from 4a. A StateFlow is preferred here over LocalBroadcastManager
 * (long-deprecated) - simple, testable under plain JUnit, no extra
 * permission surface.
 */
object AlarmStateHolder {

    sealed class State {
        data object Idle : State()
        data class Sounding(val maxGustKmh: Double) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun setSounding(maxGustKmh: Double) {
        _state.value = State.Sounding(maxGustKmh)
    }

    fun setIdle() {
        _state.value = State.Idle
    }
}
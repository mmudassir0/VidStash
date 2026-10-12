package com.mudassir.vidstash

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stops playback after a set time or at the end of the current file.
 * Set from the player screen; carried out by [PlaybackService], so it also works
 * with the screen off.
 */
object SleepTimer {

    sealed class State {
        object Off : State()
        data class At(val endAtMs: Long) : State()
        object EndOfItem : State()
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    fun set(minutes: Int) {
        _state.value = State.At(System.currentTimeMillis() + minutes * 60_000L)
    }

    fun endOfItem() {
        _state.value = State.EndOfItem
    }

    fun cancel() {
        _state.value = State.Off
    }

    /** Minutes left (rounded up), or null when not counting down. */
    fun minutesLeft(): Int? {
        val s = _state.value as? State.At ?: return null
        val ms = s.endAtMs - System.currentTimeMillis()
        return if (ms <= 0) 0 else ((ms + 59_999) / 60_000).toInt()
    }
}

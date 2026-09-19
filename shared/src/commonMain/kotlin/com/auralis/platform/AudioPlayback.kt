package com.auralis.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val sessionId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val label: String? = null,
)

/**
 * STT-4: click a caption and jump to the matching local audio.
 * Platform players (AudioTrack / AVPlayer) implement this; tests use [InMemoryPlayback].
 */
interface AudioPlayback {
    val state: StateFlow<PlaybackState>
    fun seek(sessionId: String, positionMs: Long, durationMs: Long = 0, label: String? = null)
    fun pause()
    fun stop()
}

class InMemoryPlayback : AudioPlayback {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override fun seek(sessionId: String, positionMs: Long, durationMs: Long, label: String?) {
        _state.value = PlaybackState(
            sessionId = sessionId,
            positionMs = positionMs.coerceAtLeast(0),
            durationMs = durationMs.coerceAtLeast(positionMs),
            playing = true,
            label = label,
        )
    }

    override fun pause() {
        _state.value = _state.value.copy(playing = false)
    }

    override fun stop() {
        _state.value = PlaybackState()
    }
}

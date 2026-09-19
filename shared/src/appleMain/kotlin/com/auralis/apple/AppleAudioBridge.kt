package com.auralis.apple

import com.auralis.audio.AudioChunk
import com.auralis.core.Clock
import com.auralis.platform.AudioCapture
import com.auralis.platform.BackgroundKeepAlive
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * Kotlin [AudioCapture] that Swift [AppleAudioCapture] starts/stops and feeds PCM into.
 * Implementing [kotlinx.coroutines.flow.Flow] from Swift is not practical.
 */
class AppleAudioBridge : AudioCapture {
    private val _chunks = MutableSharedFlow<AudioChunk>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val chunks = _chunks.asSharedFlow()

    /** Swift starts AVAudioEngine and returns the optional local file path. */
    var onStart: ((sessionId: String, keepFile: Boolean) -> String?)? = null
    var onStop: (() -> Unit)? = null
    private var path: String? = null

    override suspend fun start(sessionId: String, keepFile: Boolean) {
        path = onStart?.invoke(sessionId, keepFile)
    }

    override suspend fun stop() {
        onStop?.invoke()
    }

    override fun lastFilePath(): String? = path

    @OptIn(ExperimentalForeignApi::class)
    fun pushPcm(data: NSData, streamOffsetMs: Long) {
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, data.length)
            }
        }
        _chunks.tryEmit(
            AudioChunk(
                pcm16le = bytes,
                capturedAtMs = Clock.System.nowMs(),
                streamOffsetMs = streamOffsetMs,
            ),
        )
    }
}

/** Background audio session is owned by Swift AVAudioSession; nothing extra to start. */
class AppleKeepAlive : BackgroundKeepAlive {
    override fun start(sessionTitle: String) = Unit
    override fun stop() = Unit
}

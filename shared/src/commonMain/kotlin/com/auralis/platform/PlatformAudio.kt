package com.auralis.platform

import com.auralis.audio.AudioChunk
import kotlinx.coroutines.flow.Flow

/**
 * Implemented natively:
 *  - iOS/macOS: AVAudioEngine (+ ScreenCaptureKit on macOS for system audio, P1)
 *  - Android: AudioRecord + microphone foreground service
 *
 * Shared kernel only consumes [AudioChunk] PCM16le @ 16 kHz mono.
 */
interface AudioCapture {
    val chunks: Flow<AudioChunk>
    suspend fun start(sessionId: String, keepFile: Boolean)
    suspend fun stop()
    fun lastFilePath(): String?
}

interface BackgroundKeepAlive {
    fun start(sessionTitle: String)
    fun stop()
}

interface SystemShare {
    fun share(fileName: String, mime: String, bytes: ByteArray)
}

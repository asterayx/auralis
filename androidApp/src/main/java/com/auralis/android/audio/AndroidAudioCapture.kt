package com.auralis.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.auralis.audio.AudioChunk
import com.auralis.platform.AudioCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class AndroidAudioCapture(
    private val filesDir: File,
) : AudioCapture {
    private val _chunks = MutableSharedFlow<AudioChunk>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val chunks = _chunks.asSharedFlow()
    private var job: Job? = null
    private var path: String? = null

    @SuppressLint("MissingPermission")
    override suspend fun start(sessionId: String, keepFile: Boolean) {
        val sampleRate = 16_000
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            min * 2,
        )
        val out = if (keepFile) File(filesDir, "$sessionId.pcm") else null
        path = out?.absolutePath
        recorder.startRecording()
        job = CoroutineScope(Dispatchers.IO).launch {
            val buf = ByteArray(3200)
            var offset = 0L
            val fos = out?.let { FileOutputStream(it) }
            try {
                while (isActive) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val slice = buf.copyOf(n)
                    fos?.write(slice)
                    _chunks.emit(
                        AudioChunk(
                            pcm16le = slice,
                            capturedAtMs = System.currentTimeMillis(),
                            streamOffsetMs = offset,
                        ),
                    )
                    offset += (n * 1000L) / (sampleRate * 2)
                }
            } finally {
                fos?.close()
                recorder.stop()
                recorder.release()
            }
        }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
    }

    override fun lastFilePath(): String? = path
}

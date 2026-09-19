package com.auralis.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.auralis.audio.AudioArchive
import com.auralis.audio.Pcm
import com.auralis.platform.AudioPlayback
import com.auralis.platform.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AndroidPcmPlayer(
    private val archive: AudioArchive,
) : AudioPlayback {
    private val _state = MutableStateFlow(PlaybackState())
    override val state = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var track: AudioTrack? = null

    override fun seek(sessionId: String, positionMs: Long, durationMs: Long, label: String?) {
        val pcm = archive.read(sessionId) ?: ByteArray(0)
        val duration = Pcm.durationMs(pcm).coerceAtLeast(durationMs)
        stopTrack()
        _state.value = PlaybackState(sessionId, positionMs, duration, playing = pcm.isNotEmpty(), label = label)
        if (pcm.isEmpty()) return
        val offset = Pcm.byteOffset(positionMs).coerceAtMost(pcm.size)
        val remaining = if (offset >= pcm.size) ByteArray(0) else pcm.copyOfRange(offset, pcm.size)
        if (remaining.isEmpty()) return
        val min = AudioTrack.getMinBufferSize(
            16_000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val audio = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(16_000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(min.coerceAtLeast(remaining.size.coerceAtMost(min * 4)))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audio
        audio.play()
        job = scope.launch {
            audio.write(remaining, 0, remaining.size)
            _state.value = _state.value.copy(playing = false, positionMs = duration)
            audio.stop()
            audio.release()
            if (track === audio) track = null
        }
    }

    override fun pause() {
        track?.pause()
        _state.value = _state.value.copy(playing = false)
    }

    override fun stop() {
        stopTrack()
        _state.value = PlaybackState()
    }

    private fun stopTrack() {
        job?.cancel()
        job = null
        runCatching {
            track?.stop()
            track?.release()
        }
        track = null
    }
}

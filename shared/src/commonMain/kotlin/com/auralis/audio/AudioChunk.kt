package com.auralis.audio

data class AudioChunk(
    val pcm16le: ByteArray,
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val capturedAtMs: Long,
    val streamOffsetMs: Long,
) {
    val durationMs: Long
        get() = if (sampleRate <= 0 || channels <= 0) 0
        else (pcm16le.size.toLong() * 1000L) / (sampleRate * channels * 2L)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return pcm16le.contentEquals(other.pcm16le) &&
            sampleRate == other.sampleRate &&
            channels == other.channels &&
            capturedAtMs == other.capturedAtMs &&
            streamOffsetMs == other.streamOffsetMs
    }

    override fun hashCode(): Int = pcm16le.contentHashCode()
}

const val TARGET_SAMPLE_RATE = 16_000
const val RING_BUFFER_MS = 30_000

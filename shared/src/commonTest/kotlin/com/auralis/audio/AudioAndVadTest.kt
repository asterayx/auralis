package com.auralis.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioAndVadTest {
    @Test
    fun ringBufferReturnsLastThirtySeconds() {
        val ring = AudioRingBuffer(capacityMs = 1_000, sampleRate = 1000, channels = 1)
        // 1000 Hz * 2 bytes = 2000 bytes/s → 2 bytes/ms
        val first = ByteArray(1000) { 1 }
        val second = ByteArray(2000) { 2 }
        ring.write(AudioChunk(first, sampleRate = 1000, capturedAtMs = 0, streamOffsetMs = 0))
        ring.write(AudioChunk(second, sampleRate = 1000, capturedAtMs = 500, streamOffsetMs = 500))
        val snap = ring.snapshot()
        assertEquals(2000, snap.size)
        assertTrue(snap.all { it == 2.toByte() })
        val gap = ring.sliceFrom(1000)
        assertEquals(1000, gap.size)
    }

    @Test
    fun wavRoundTripKeepsDuration() {
        val pcm = ByteArray(32_000) { 1 }
        val wav = Pcm.toWav(pcm, 16_000)
        assertTrue(Pcm.isWav(wav))
        assertEquals(1_000, Pcm.durationMs(Pcm.pcmFromContainer(wav)))
        assertEquals(32_000, Pcm.byteOffset(1_000))
    }

    @Test
    fun energyVadDetectsSpeechAndHangover() {
        val vad = EnergyVad(rmsThreshold = 100.0, hangoverChunks = 2)
        val loud = AudioChunk(tone(2000, 4000), capturedAtMs = 0, streamOffsetMs = 0)
        val quiet = AudioChunk(ByteArray(200), capturedAtMs = 20, streamOffsetMs = 20)
        assertTrue(vad.isSpeech(loud))
        assertTrue(vad.isSpeech(quiet))
        assertTrue(vad.isSpeech(quiet))
        assertTrue(!vad.isSpeech(quiet))
    }

    private fun tone(samples: Int, amplitude: Int): ByteArray {
        val out = ByteArray(samples * 2)
        var i = 0
        while (i < samples) {
            val v = amplitude
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = (v shr 8 and 0xff).toByte()
            i += 1
        }
        return out
    }
}

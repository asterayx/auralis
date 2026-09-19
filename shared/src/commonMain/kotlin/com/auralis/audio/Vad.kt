package com.auralis.audio

interface Vad {
    fun isSpeech(chunk: AudioChunk): Boolean
    fun reset()
}

/**
 * Lightweight energy VAD for P0. Silero (ONNX) can replace this without
 * changing the pipeline — it only depends on [Vad].
 */
class EnergyVad(
    private val rmsThreshold: Double = 350.0,
    private val hangoverChunks: Int = 6,
) : Vad {
    private var silentRun = 0
    private var speaking = false

    override fun isSpeech(chunk: AudioChunk): Boolean {
        val rms = rms(chunk.pcm16le)
        return if (rms >= rmsThreshold) {
            silentRun = 0
            speaking = true
            true
        } else {
            silentRun += 1
            if (silentRun > hangoverChunks) speaking = false
            speaking
        }
    }

    override fun reset() {
        silentRun = 0
        speaking = false
    }

    companion object {
        fun rms(pcm16le: ByteArray): Double {
            if (pcm16le.size < 2) return 0.0
            var sum = 0.0
            var n = 0
            var i = 0
            while (i + 1 < pcm16le.size) {
                val lo = pcm16le[i].toInt() and 0xff
                val hi = pcm16le[i + 1].toInt()
                val sample = (hi shl 8) or lo
                val signed = if (sample >= 0x8000) sample - 0x10000 else sample
                sum += signed.toDouble() * signed.toDouble()
                n += 1
                i += 2
            }
            return if (n == 0) 0.0 else kotlin.math.sqrt(sum / n)
        }
    }
}

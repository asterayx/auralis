package com.auralis.audio

object Pcm {
    fun durationMs(pcm16le: ByteArray, sampleRate: Int = TARGET_SAMPLE_RATE, channels: Int = 1): Long {
        if (pcm16le.isEmpty() || sampleRate <= 0 || channels <= 0) return 0
        return (pcm16le.size.toLong() * 1000L) / (sampleRate * channels * 2L)
    }

    fun byteOffset(positionMs: Long, sampleRate: Int = TARGET_SAMPLE_RATE, channels: Int = 1): Int {
        val bytes = (positionMs * sampleRate * channels * 2L) / 1000L
        return bytes.toInt().coerceAtLeast(0) and 1.inv()
    }

    fun isWav(bytes: ByteArray): Boolean =
        bytes.size > 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    fun pcmFromContainer(bytes: ByteArray): ByteArray =
        if (isWav(bytes)) bytes.copyOfRange(44, bytes.size) else bytes

    fun toWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val header = ByteArray(44)
        val byteRate = sampleRate * channels * 2
        fun putStr(offset: Int, s: String) = s.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() }
        fun put32(offset: Int, v: Int) {
            header[offset] = (v and 0xff).toByte()
            header[offset + 1] = (v shr 8 and 0xff).toByte()
            header[offset + 2] = (v shr 16 and 0xff).toByte()
            header[offset + 3] = (v shr 24 and 0xff).toByte()
        }
        fun put16(offset: Int, v: Int) {
            header[offset] = (v and 0xff).toByte()
            header[offset + 1] = (v shr 8 and 0xff).toByte()
        }
        putStr(0, "RIFF")
        put32(4, 36 + pcm.size)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        put32(16, 16)
        put16(20, 1)
        put16(22, channels)
        put32(24, sampleRate)
        put32(28, byteRate)
        put16(32, channels * 2)
        put16(34, 16)
        putStr(36, "data")
        put32(40, pcm.size)
        return header + pcm
    }
}

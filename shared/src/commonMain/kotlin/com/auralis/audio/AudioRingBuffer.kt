package com.auralis.audio

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * 30-second PCM ring buffer used to backfill STT after a reconnect.
 */
class AudioRingBuffer(
    private val capacityMs: Int = RING_BUFFER_MS,
    private val sampleRate: Int = TARGET_SAMPLE_RATE,
    private val channels: Int = 1,
) {
    private val lock = reentrantLock()
    private val bytesPerMs = sampleRate * channels * 2 / 1000
    private val capacityBytes = (capacityMs * bytesPerMs).coerceAtLeast(bytesPerMs)
    private val buf = ByteArray(capacityBytes)
    private var writePos = 0
    private var size = 0
    private var startOffsetMs: Long = 0
    private var endOffsetMs: Long = 0

    fun write(chunk: AudioChunk) = lock.withLock {
        val data = chunk.pcm16le
        if (data.isEmpty()) return
        var remaining = data.size
        var src = 0
        while (remaining > 0) {
            val space = capacityBytes - writePos
            val n = minOf(remaining, space)
            data.copyInto(buf, writePos, src, src + n)
            writePos = (writePos + n) % capacityBytes
            size = minOf(capacityBytes, size + n)
            remaining -= n
            src += n
        }
        endOffsetMs = chunk.streamOffsetMs + chunk.durationMs
        val coveredMs = if (bytesPerMs == 0) 0 else size / bytesPerMs
        startOffsetMs = (endOffsetMs - coveredMs).coerceAtLeast(0)
    }

    fun snapshot(): ByteArray = lock.withLock {
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        val start = (writePos - size + capacityBytes) % capacityBytes
        val first = minOf(size, capacityBytes - start)
        buf.copyInto(out, 0, start, start + first)
        if (first < size) buf.copyInto(out, first, 0, size - first)
        out
    }

    fun sliceFrom(offsetMs: Long): ByteArray = lock.withLock {
        if (size == 0 || offsetMs >= endOffsetMs) return ByteArray(0)
        val from = offsetMs.coerceAtLeast(startOffsetMs)
        val skipMs = (from - startOffsetMs).toInt()
        val skipBytes = (skipMs * bytesPerMs).coerceAtMost(size)
        val full = snapshot()
        full.copyOfRange(skipBytes, full.size)
    }

    fun coveredRange(): LongRange = lock.withLock { startOffsetMs until endOffsetMs }

    fun clear() = lock.withLock {
        writePos = 0
        size = 0
        startOffsetMs = 0
        endOffsetMs = 0
    }
}

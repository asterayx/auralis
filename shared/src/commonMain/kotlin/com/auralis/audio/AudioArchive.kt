package com.auralis.audio

/**
 * Crash-safe PCM journal (REC-3 / REC-4). Audio is appended as it is captured
 * so a killed session can still catch up after restart.
 */
interface AudioArchive {
    fun append(sessionId: String, pcm16le: ByteArray)
    fun read(sessionId: String): ByteArray?
    fun uri(sessionId: String): String?
    fun delete(sessionId: String)
}

class MemoryAudioArchive : AudioArchive {
    private val chunks = LinkedHashMap<String, MutableList<ByteArray>>()

    override fun append(sessionId: String, pcm16le: ByteArray) {
        if (pcm16le.isEmpty()) return
        chunks.getOrPut(sessionId) { mutableListOf() }.add(pcm16le.copyOf())
    }

    override fun read(sessionId: String): ByteArray? {
        val parts = chunks[sessionId] ?: return null
        val size = parts.sumOf { it.size }
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    override fun uri(sessionId: String): String? =
        if (chunks.containsKey(sessionId)) "memory://$sessionId.pcm" else null

    override fun delete(sessionId: String) {
        chunks.remove(sessionId)
    }
}

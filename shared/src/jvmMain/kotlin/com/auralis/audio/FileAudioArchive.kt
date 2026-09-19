package com.auralis.audio

import java.io.File
import java.io.FileOutputStream

class FileAudioArchive(private val root: File) : AudioArchive {
    init {
        root.mkdirs()
    }

    override fun append(sessionId: String, pcm16le: ByteArray) {
        if (pcm16le.isEmpty()) return
        val file = file(sessionId)
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { it.write(pcm16le) }
    }

    override fun read(sessionId: String): ByteArray? {
        val file = file(sessionId)
        return if (file.isFile) file.readBytes() else null
    }

    override fun uri(sessionId: String): String? {
        val file = file(sessionId)
        return if (file.isFile) file.absolutePath else null
    }

    override fun delete(sessionId: String) {
        file(sessionId).delete()
    }

    private fun file(sessionId: String): File {
        val safe = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(root, "$safe.pcm")
    }
}

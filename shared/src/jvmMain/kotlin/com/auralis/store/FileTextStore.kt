package com.auralis.store

import java.io.File

class FileTextStore(private val root: File) : TextStore {
    init {
        root.mkdirs()
    }

    override fun read(key: String): String? {
        val file = file(key)
        return if (file.isFile) file.readText() else null
    }

    override fun write(key: String, value: String) {
        val file = file(key)
        file.parentFile?.mkdirs()
        file.writeText(value)
    }

    override fun delete(key: String) {
        file(key).delete()
    }

    override fun keys(): List<String> =
        root.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()

    private fun file(key: String): File {
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(root, safe)
    }
}

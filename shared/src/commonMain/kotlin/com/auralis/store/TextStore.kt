package com.auralis.store

/**
 * Tiny key/value text store so session + settings persistence can live in
 * common code. JVM/Android use [FileTextStore]; tests use [MemoryTextStore].
 */
interface TextStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun delete(key: String)
    fun keys(): List<String>
}

class MemoryTextStore : TextStore {
    private val data = LinkedHashMap<String, String>()
    override fun read(key: String): String? = data[key]
    override fun write(key: String, value: String) {
        data[key] = value
    }
    override fun delete(key: String) {
        data.remove(key)
    }
    override fun keys(): List<String> = data.keys.toList()
}

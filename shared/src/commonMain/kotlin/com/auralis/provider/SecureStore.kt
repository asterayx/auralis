package com.auralis.provider

/**
 * Platform secure storage. Keys must never enter the database, logs,
 * crash reports, or export files (KEY-1).
 */
interface SecureStore {
    suspend fun put(alias: String, secret: String)
    suspend fun get(alias: String): String?
    suspend fun delete(alias: String)
    suspend fun contains(alias: String): Boolean = get(alias) != null
}

class InMemorySecureStore : SecureStore {
    private val map = mutableMapOf<String, String>()
    override suspend fun put(alias: String, secret: String) {
        map[alias] = secret
    }
    override suspend fun get(alias: String): String? = map[alias]
    override suspend fun delete(alias: String) {
        map.remove(alias)
    }
}

fun secretAlias(endpointId: String): String = "auralis.provider.$endpointId"

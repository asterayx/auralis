package com.auralis.store

import com.auralis.core.Clock
import com.auralis.model.EditOverlay
import com.auralis.model.Session
import com.auralis.model.SessionBundle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * File-backed session library (LIB-1). SQLDelight schema remains the mobile
 * target; this JSON store ships the P0 "sessions survive restart" loop on
 * JVM/Android without extra native drivers.
 */
class JsonSessionRepository(
    private val store: TextStore,
    private val clock: Clock = Clock.System,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : SessionRepository {
    private fun key(id: String) = "session-$id.json"

    override suspend fun upsert(bundle: SessionBundle) {
        val next = bundle.copy(session = bundle.session.copy(updatedAtMs = clock.nowMs()))
        store.write(key(next.session.id), json.encodeToString(SessionBundle.serializer(), next))
    }

    override suspend fun get(id: String): SessionBundle? =
        store.read(key(id))?.let { json.decodeFromString(SessionBundle.serializer(), it) }

    override suspend fun list(): List<Session> =
        all().map { it.session }.sortedByDescending { it.updatedAtMs }

    override suspend fun delete(id: String) {
        store.delete(key(id))
    }

    override suspend fun search(query: String): List<Session> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list()
        return all().filter { bundle ->
            bundle.session.title.lowercase().contains(q) ||
                bundle.session.tags.any { it.lowercase().contains(q) } ||
                bundle.fullTranscript().lowercase().contains(q) ||
                bundle.translations.any { it.translatedText.lowercase().contains(q) }
        }.map { it.session }.sortedByDescending { it.updatedAtMs }
    }

    override suspend fun editSegment(sessionId: String, overlay: EditOverlay) {
        val current = get(sessionId) ?: return
        val rest = current.edits.filterNot { it.segmentId == overlay.segmentId }
        upsert(current.copy(edits = rest + overlay))
    }

    override suspend fun rename(sessionId: String, title: String) {
        val current = get(sessionId) ?: return
        upsert(current.copy(session = current.session.copy(title = title)))
    }

    override suspend fun setFolder(sessionId: String, folder: String?) {
        val current = get(sessionId) ?: return
        upsert(current.copy(session = current.session.copy(folder = folder)))
    }

    override suspend fun renameSpeaker(sessionId: String, speakerId: String, name: String) {
        val current = get(sessionId) ?: return
        upsert(current.copy(speakers = com.auralis.transcript.SpeakerRoster.rename(current.speakers, speakerId, name)))
    }

    private fun all(): List<SessionBundle> = store.keys()
        .filter { it.startsWith("session-") && it.endsWith(".json") }
        .mapNotNull { key ->
            store.read(key)?.let { runCatching { json.decodeFromString(SessionBundle.serializer(), it) }.getOrNull() }
        }
}

class JsonSettingsStore(
    private val store: TextStore,
    private val fileKey: String = "settings.json",
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : SettingsStore {
    override suspend fun load(): AppSettings {
        val raw = store.read(fileKey) ?: return AppSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), raw) }.getOrElse { AppSettings() }
    }

    override suspend fun save(settings: AppSettings) {
        store.write(fileKey, json.encodeToString(AppSettings.serializer(), settings))
    }
}

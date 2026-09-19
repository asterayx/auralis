package com.auralis.store

import com.auralis.core.Clock
import com.auralis.model.EditOverlay
import com.auralis.model.Session
import com.auralis.model.SessionBundle
import com.auralis.model.SessionStatus

interface SessionRepository {
    suspend fun upsert(bundle: SessionBundle)
    suspend fun get(id: String): SessionBundle?
    suspend fun list(): List<Session>
    suspend fun delete(id: String)
    suspend fun search(query: String): List<Session>
    suspend fun editSegment(sessionId: String, overlay: EditOverlay)
    suspend fun rename(sessionId: String, title: String)
    suspend fun setFolder(sessionId: String, folder: String?)
}

class InMemorySessionRepository(
    private val clock: Clock = Clock.System,
) : SessionRepository {
    private val data = LinkedHashMap<String, SessionBundle>()

    override suspend fun upsert(bundle: SessionBundle) {
        data[bundle.session.id] = bundle.copy(
            session = bundle.session.copy(updatedAtMs = clock.nowMs()),
        )
    }

    override suspend fun get(id: String): SessionBundle? = data[id]

    override suspend fun list(): List<Session> =
        data.values.map { it.session }.sortedByDescending { it.updatedAtMs }

    override suspend fun delete(id: String) {
        data.remove(id)
    }

    override suspend fun search(query: String): List<Session> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list()
        return data.values.filter { bundle ->
            bundle.session.title.lowercase().contains(q) ||
                bundle.session.tags.any { it.lowercase().contains(q) } ||
                bundle.fullTranscript().lowercase().contains(q) ||
                bundle.translations.any { it.translatedText.lowercase().contains(q) }
        }.map { it.session }.sortedByDescending { it.updatedAtMs }
    }

    override suspend fun editSegment(sessionId: String, overlay: EditOverlay) {
        val current = data[sessionId] ?: return
        val rest = current.edits.filterNot { it.segmentId == overlay.segmentId }
        data[sessionId] = current.copy(
            edits = rest + overlay,
            session = current.session.copy(updatedAtMs = clock.nowMs()),
        )
    }

    override suspend fun rename(sessionId: String, title: String) {
        val current = data[sessionId] ?: return
        data[sessionId] = current.copy(session = current.session.copy(title = title, updatedAtMs = clock.nowMs()))
    }

    override suspend fun setFolder(sessionId: String, folder: String?) {
        val current = data[sessionId] ?: return
        data[sessionId] = current.copy(session = current.session.copy(folder = folder, updatedAtMs = clock.nowMs()))
    }
}

fun SessionBundle.withStatus(status: SessionStatus): SessionBundle =
    copy(session = session.copy(status = status))

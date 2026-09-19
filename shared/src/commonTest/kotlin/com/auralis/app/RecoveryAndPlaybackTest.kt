package com.auralis.app

import com.auralis.audio.AudioChunk
import com.auralis.audio.MemoryAudioArchive
import com.auralis.core.Clock
import com.auralis.export.formatTimestamp
import com.auralis.model.SessionMode
import com.auralis.model.SessionStatus
import com.auralis.platform.InMemoryPlayback
import com.auralis.provider.Presets
import com.auralis.store.JsonSessionRepository
import com.auralis.store.JsonSettingsStore
import com.auralis.store.MemoryTextStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecoveryAndPlaybackTest {
    @Test
    fun demoScriptDoesNotReplayAfterExpectedClose() = runBlocking {
        val app = AuralisApp(clock = Clock { 1L })
        app.load()
        app.persist { it.copy(activeProfileId = Presets.demo.id) }
        val live = app.startLive(SessionMode.SCRIBE)
        repeat(24) { i ->
            app.feed(live, AudioChunk(ByteArray(3200), capturedAtMs = i * 100L, streamOffsetMs = i * 100L))
            delay(350)
        }
        delay(800)
        val bundle = app.finishLive(live)
        val ids = bundle.segments.map { it.id }
        assertEquals(ids.distinct(), ids, "demo STT replayed segments: $ids")
        assertTrue(bundle.segments.size in 1..5, "unexpected segment count ${bundle.segments.size}")
    }

    @Test
    fun killedSessionCatchUpFromLocalAudio() = runBlocking {
        val disk = MemoryTextStore()
        val archive = MemoryAudioArchive()
        val first = AuralisApp(
            sessions = JsonSessionRepository(disk, Clock { 10L }),
            settingsStore = JsonSettingsStore(disk),
            clock = Clock { 10L },
            audioArchive = archive,
        )
        first.load()
        first.persist { it.copy(activeProfileId = Presets.demo.id) }
        val live = first.startLive(SessionMode.TRANSLATOR)
        repeat(20) { i ->
            first.feed(
                live,
                AudioChunk(
                    pcm16le = ByteArray(32_000),
                    capturedAtMs = i * 1000L,
                    streamOffsetMs = i * 1000L,
                ),
            )
        }
        val abandoned = first.abandonLive(live)
        assertEquals(SessionStatus.OFFLINE_PENDING, abandoned.session.status)
        assertNotNull(archive.uri(abandoned.session.id))

        val second = AuralisApp(
            sessions = JsonSessionRepository(disk, Clock { 20L }),
            settingsStore = JsonSettingsStore(disk),
            clock = Clock { 20L },
            audioArchive = archive,
        )
        second.load()
        val recovered = second.sessions.get(abandoned.session.id)
        assertNotNull(recovered)
        assertEquals(SessionStatus.READY, recovered.session.status)
        assertTrue(recovered.fullTranscript().contains("供应商") || recovered.segments.isNotEmpty())
        assertEquals(recovered.segments.map { it.id }.distinct(), recovered.segments.map { it.id })
        assertTrue(recovered.session.title.isNotBlank())
    }

    @Test
    fun seekJumpsToCaptionTimestamp() = runBlocking {
        val playback = InMemoryPlayback()
        val archive = MemoryAudioArchive()
        val app = AuralisApp(
            clock = Clock { 3L },
            audioArchive = archive,
            playback = playback,
        )
        app.load()
        val live = app.startLive(SessionMode.SCRIBE)
        repeat(8) { i ->
            app.feed(live, AudioChunk(ByteArray(32_000), capturedAtMs = i * 1000L, streamOffsetMs = i * 1000L))
            delay(350)
        }
        val bundle = app.finishLive(live)
        val seg = bundle.segments.first()
        val state = app.seekToSegment(bundle.session.id, seg.id)
        assertEquals(seg.startMs, state.positionMs)
        assertTrue(state.playing)
        assertTrue(state.label.orEmpty().contains(formatTimestamp(seg.startMs)))
        assertTrue(state.label.orEmpty().contains("正在播放"))
        assertTrue(app.audioWav(bundle.session.id) != null)
    }
}

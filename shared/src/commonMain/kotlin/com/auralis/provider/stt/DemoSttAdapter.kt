package com.auralis.provider.stt

import com.auralis.audio.AudioChunk
import com.auralis.provider.SttCapabilities
import com.auralis.transcript.newToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Scripted captions for first-run and App Store review. No network, no key.
 */
class DemoSttAdapter(
    override val id: String = "stt-demo",
) : SttProvider {
    override val capabilities = SttCapabilities(
        streaming = true,
        interimResults = true,
        wordTimestamps = true,
        diarizationStreaming = true,
        languageAuto = true,
        codeSwitching = true,
        customVocabulary = true,
        nativeTranslation = false,
        languages = listOf("zh", "en"),
        notes = "Offline scripted demo",
    )

    override suspend fun connect(config: SttSessionConfig): SttSession = DemoSession()

    private class DemoSession : SttSession {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val _events = MutableSharedFlow<SttEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        override val events = _events.asSharedFlow()
        private var job: Job? = null

        init {
            job = scope.launch { play() }
        }

        override suspend fun send(chunk: AudioChunk) {
            // Demo ignores PCM; the script is time-based.
        }

        private suspend fun play() {
            _events.emit(SttEvent.Ready("demo"))
            script.forEachIndexed { index, line ->
                val start = line.startMs
                _events.emit(
                    SttEvent.Tokens(
                        listOf(
                            newToken(
                                text = line.text,
                                startMs = start,
                                endMs = line.endMs,
                                isFinal = false,
                                speakerId = line.speaker,
                                language = line.lang,
                            ),
                        ),
                    ),
                )
                delay(280)
                _events.emit(
                    SttEvent.Tokens(
                        listOf(
                            newToken(
                                text = line.text,
                                startMs = start,
                                endMs = line.endMs,
                                isFinal = true,
                                speakerId = line.speaker,
                                language = line.lang,
                                id = "tok-demo-$index",
                            ),
                        ),
                    ),
                )
                delay(420)
            }
            _events.emit(SttEvent.Closed("demo-finished"))
        }

        override suspend fun finalizeUtterance() = Unit

        override suspend fun close() {
            job?.cancel()
            _events.emit(SttEvent.Closed("closed"))
        }
    }

    private data class Line(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val speaker: String,
        val lang: String,
    )

    companion object {
        private val script = listOf(
            Line("大家好，我们开始今天的供应商对齐会。", 400, 3200, "1", "zh"),
            Line("Hello everyone, thanks for joining.", 3400, 5600, "2", "en"),
            Line("本周交期能否从十月十二日提前到十月八日？", 5800, 9800, "1", "zh"),
            Line("We can pull in two days if the firmware freeze happens tonight.", 10000, 14800, "2", "en"),
            Line("好，那行动项是今晚冻结固件，并更新给采购。", 15000, 19800, "1", "zh"),
        )
    }
}

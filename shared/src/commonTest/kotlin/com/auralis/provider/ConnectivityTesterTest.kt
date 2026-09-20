package com.auralis.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectivityTesterTest {
    @Test
    fun parseOpenAiModelsList() {
        val ids = parseModelIds(
            """{"object":"list","data":[{"id":"gpt-4.1-mini"},{"id":"whisper-1","object":"model"}]}""",
        )
        assertEquals(listOf("gpt-4.1-mini", "whisper-1"), ids)
    }

    @Test
    fun parseSonioxModelsList() {
        val ids = parseModelIds(
            """{"models":[{"id":"stt-rt-v5","name":"Soniox RT"},{"id":"stt-rt-preview"}]}""",
        )
        assertEquals(listOf("stt-rt-v5", "stt-rt-preview"), ids)
    }

    @Test
    fun parseStringArrayModels() {
        val ids = parseModelIds("""{"models":["grok-4-1-fast-non-reasoning","grok-2"]}""")
        assertEquals(listOf("grok-4-1-fast-non-reasoning", "grok-2"), ids)
    }

    @Test
    fun parseInvalidBodyIsEmpty() {
        assertTrue(parseModelIds("").isEmpty())
        assertTrue(parseModelIds("not-json").isEmpty())
        assertTrue(parseModelIds("""{"user":{"name":"x"}}""").isEmpty())
    }

    @Test
    fun endpointSlotsSplitSttAndLlm() {
        assertTrue(Presets.soniox.fitsStt())
        assertTrue(!Presets.soniox.fitsLlm())
        assertTrue(Presets.grokLlm.fitsLlm())
        assertTrue(!Presets.grokLlm.fitsStt())
        assertTrue(Presets.demoStt.fitsStt())
        assertTrue(Presets.demoLlm.fitsLlm())
    }
}

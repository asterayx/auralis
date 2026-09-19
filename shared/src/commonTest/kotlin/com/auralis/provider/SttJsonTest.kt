package com.auralis.provider

import com.auralis.error.ProviderError
import com.auralis.provider.stt.GrokSttAdapter
import com.auralis.provider.stt.OpenAiCompatibleBatchSttAdapter
import com.auralis.provider.stt.SttJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SttJsonTest {
    @Test
    fun sonioxInterimAndFinalAndNativeTranslation() {
        val raw = """
            {"tokens":[
              {"text":"Hello","start_ms":600,"end_ms":760,"is_final":true,"speaker":"1","confidence":0.97},
              {"text":"Hola","translation_status":"translation","is_final":true}
            ],"total_audio_proc_ms":880}
        """.trimIndent()
        val parsed = SttJson.parseSonioxTokens(raw)
        assertEquals(1, parsed.tokens.size)
        assertEquals("Hello", parsed.tokens[0].text)
        assertEquals("1", parsed.tokens[0].speakerId)
        assertEquals(1, parsed.native.size)
        assertEquals("Hola", parsed.native[0].text)
        assertTrue(parsed.native[0].isNativeTranslation)
    }

    @Test
    fun sonioxErrorMapsToUnauthorized() {
        val parsed = SttJson.parseSonioxTokens(
            """{"tokens":[],"error_code":401,"error_type":"unauthenticated","error_message":"Incorrect API key"}""",
        )
        val err = ProviderError.fromHttp(ProviderError.Slot.STT, "stt-soniox", parsed.errorCode!!, parsed.errorMessage!!)
        assertTrue(err is ProviderError.Unauthorized)
        assertTrue(err.userMessage().contains("Authentication"))
    }

    @Test
    fun grokPartialWords() {
        val (tokens, speechFinal) = SttJson.parseGrokPartial(
            """{"type":"transcript.partial","is_final":true,"speech_final":true,"words":[
              {"text":"Hi","start":0.1,"end":0.3,"speaker":1}
            ]}""",
        )
        assertEquals("Hi", tokens.single().text)
        assertEquals(100, tokens.single().startMs)
        assertTrue(speechFinal)
    }

    @Test
    fun openaiVerboseJsonWords() {
        val body = """{"text":"The balance is 10.","language":"en","words":[
          {"word":"The","start":0.24,"end":0.48},
          {"word":"balance","start":0.48,"end":0.96}
        ]}"""
        val tokens = OpenAiCompatibleBatchSttAdapter.parseVerboseJson(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
            body,
            offsetMs = 1000,
        )
        assertEquals(2, tokens.size)
        assertEquals(1240, tokens[0].startMs)
        assertTrue(tokens.all { it.isFinal })
    }

    @Test
    fun grokDoesNotListChinese() {
        assertTrue("pt" in GrokSttAdapter.GROK_LANGUAGES)
        assertTrue("zh" !in GrokSttAdapter.GROK_LANGUAGES)
        assertTrue("cmn" !in GrokSttAdapter.GROK_LANGUAGES)
    }

    @Test
    fun wavHeaderIs44BytesPlusPcm() {
        val wav = OpenAiCompatibleBatchSttAdapter.pcm16ToWav(ByteArray(8), 16_000)
        assertEquals(52, wav.size)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
    }
}

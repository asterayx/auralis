package com.auralis.provider.stt

import com.auralis.core.newId
import com.auralis.transcript.newToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object SttJson {
    val json = Json { ignoreUnknownKeys = true }

    fun parseSonioxTokens(raw: String) = parseSoniox(json.parseToJsonElement(raw).jsonObject)

    data class SonioxParse(
        val finished: Boolean,
        val errorCode: Int?,
        val errorMessage: String?,
        val tokens: List<com.auralis.model.TranscriptToken>,
        val native: List<com.auralis.model.TranscriptToken>,
    )

    fun parseSoniox(obj: JsonObject): SonioxParse {
        val errorCode = obj["error_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val errorMessage = obj["error_message"]?.jsonPrimitive?.contentOrNull
        val tokens = (obj["tokens"] as? JsonArray)?.mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            val text = t["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val translationStatus = t["translation_status"]?.jsonPrimitive?.contentOrNull
            newToken(
                text = text,
                startMs = t["start_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                endMs = t["end_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                isFinal = t["is_final"]?.jsonPrimitive?.booleanOrNull ?: false,
                speakerId = t["speaker"]?.jsonPrimitive?.contentOrNull,
                language = t["language"]?.jsonPrimitive?.contentOrNull,
                confidence = t["confidence"]?.jsonPrimitive?.doubleOrNull,
                id = newId("sx"),
                isNativeTranslation = translationStatus == "translation",
                sourceLanguage = t["source_language"]?.jsonPrimitive?.contentOrNull,
            )
        }.orEmpty()
        val (native, source) = tokens.partition { it.isNativeTranslation }
        return SonioxParse(
            finished = obj["finished"]?.jsonPrimitive?.booleanOrNull == true,
            errorCode = errorCode,
            errorMessage = errorMessage,
            tokens = source,
            native = native,
        )
    }

    fun parseGrokPartial(raw: String): Pair<List<com.auralis.model.TranscriptToken>, Boolean> {
        val obj = json.parseToJsonElement(raw).jsonObject
        val isFinal = obj["is_final"]?.jsonPrimitive?.booleanOrNull
            ?: (obj["type"]?.jsonPrimitive?.contentOrNull == "transcript.done")
        val speechFinal = obj["speech_final"]?.jsonPrimitive?.booleanOrNull == true
        val words = obj["words"]?.jsonArray
        val tokens = if (words != null && words.isNotEmpty()) {
            words.mapNotNull { el ->
                val w = el.jsonObject
                val text = w["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val start = ((w["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
                val end = ((w["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
                newToken(
                    text = text,
                    startMs = start,
                    endMs = end,
                    isFinal = isFinal,
                    speakerId = w["speaker"]?.jsonPrimitive?.contentOrNull,
                    language = obj["language"]?.jsonPrimitive?.contentOrNull,
                )
            }
        } else emptyList()
        return tokens to speechFinal
    }
}

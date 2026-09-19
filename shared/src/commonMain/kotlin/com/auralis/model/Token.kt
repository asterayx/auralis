package com.auralis.model

import kotlinx.serialization.Serializable

/**
 * Token-level transcript unit. Sentences are aggregated from tokens.
 * User edits live in [EditOverlay] and never mutate the original token.
 */
@Serializable
data class TranscriptToken(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val isFinal: Boolean,
    val speakerId: String? = null,
    val language: String? = null,
    val confidence: Double? = null,
    val sourceLanguage: String? = null,
    val isNativeTranslation: Boolean = false,
)

@Serializable
data class TranscriptSegment(
    val id: String,
    val tokenIds: List<String>,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val speakerId: String? = null,
    val language: String? = null,
    val isFinal: Boolean = true,
)

@Serializable
data class EditOverlay(
    val segmentId: String,
    val editedText: String,
    val editedAtMs: Long,
)

@Serializable
data class Speaker(
    val id: String,
    val displayName: String,
)

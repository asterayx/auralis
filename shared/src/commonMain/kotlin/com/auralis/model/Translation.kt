package com.auralis.model

import kotlinx.serialization.Serializable

@Serializable
data class GlossaryEntry(
    val source: String,
    val target: String,
)

@Serializable
data class TranslatedSegment(
    val segmentId: String,
    val sourceText: String,
    val translatedText: String,
    val isPreview: Boolean = false,
    val providerId: String,
    val model: String,
    val updatedAtMs: Long,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val side: ConversationSide? = null,
) {
    val directionLabel: String
        get() {
            val from = sourceLanguage ?: "?"
            val to = targetLanguage ?: "?"
            return "$from → $to"
        }
}

enum class TranslationLayout {
    STACKED,
    SIDE_BY_SIDE,
}

enum class ConversationSide {
    LOCAL,
    REMOTE,
}

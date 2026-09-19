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
)

enum class TranslationLayout {
    STACKED,
    SIDE_BY_SIDE,
}

enum class ConversationSide {
    LOCAL,
    REMOTE,
}

package com.auralis.model

import kotlinx.serialization.Serializable

@Serializable
data class UsageRecord(
    val sessionId: String,
    val audioMs: Long = 0,
    val sttProviderId: String? = null,
    val sttModel: String? = null,
    val llmInputTokens: Int = 0,
    val llmOutputTokens: Int = 0,
    val translationInputTokens: Int = 0,
    val translationOutputTokens: Int = 0,
    val estimatedUsd: Double? = null,
)

@Serializable
data class PriceRow(
    val providerId: String,
    val model: String,
    val usdPerAudioHour: Double? = null,
    val usdPerMillionInputTokens: Double? = null,
    val usdPerMillionOutputTokens: Double? = null,
)

package com.auralis.provider

import kotlinx.serialization.Serializable

enum class ProviderKind {
    SONIOX,
    ELEVENLABS,
    GROK_STT,
    OPENAI_COMPAT_STREAMING,
    OPENAI_COMPAT_BATCH,
    OPENAI_COMPAT_LLM,
    DEMO,
}

enum class SlotKind { STT, TRANSLATION, POST_PROCESS }

@Serializable
data class ProviderEndpoint(
    val id: String,
    val kind: ProviderKind,
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val extraHeaders: Map<String, String> = emptyMap(),
    val tokenEndpoint: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class ProviderProfile(
    val id: String,
    val name: String,
    val description: String,
    val sttId: String,
    val translationId: String,
    val postProcessId: String,
    val preferNativeTranslation: Boolean = true,
    val previewInterimTranslation: Boolean = false,
    val fallbackSttId: String? = null,
    val fallbackTranslationId: String? = null,
    val fallbackPostProcessId: String? = null,
)

@Serializable
data class SecretRef(
    val endpointId: String,
)

data class ResolvedEndpoint(
    val endpoint: ProviderEndpoint,
    val apiKey: String,
)

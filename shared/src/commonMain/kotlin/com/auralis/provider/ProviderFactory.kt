package com.auralis.provider

import com.auralis.provider.llm.DemoLlmAdapter
import com.auralis.provider.llm.LlmProvider
import com.auralis.provider.llm.OpenAiCompatibleLlmAdapter
import com.auralis.provider.stt.DemoSttAdapter
import com.auralis.provider.stt.ElevenLabsSttAdapter
import com.auralis.provider.stt.GrokSttAdapter
import com.auralis.provider.stt.OpenAiCompatibleBatchSttAdapter
import com.auralis.provider.stt.OpenAiCompatibleStreamingSttAdapter
import com.auralis.provider.stt.SonioxSttAdapter
import com.auralis.provider.stt.SttProvider
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

class ProviderFactory(
    private val client: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun stt(resolved: ResolvedEndpoint): SttProvider = when (resolved.endpoint.kind) {
        ProviderKind.SONIOX -> SonioxSttAdapter(client, resolved, json)
        ProviderKind.ELEVENLABS -> ElevenLabsSttAdapter(client, resolved, json)
        ProviderKind.GROK_STT -> GrokSttAdapter(client, resolved, json)
        ProviderKind.OPENAI_COMPAT_STREAMING -> OpenAiCompatibleStreamingSttAdapter(client, resolved, json)
        ProviderKind.OPENAI_COMPAT_BATCH -> OpenAiCompatibleBatchSttAdapter(client, resolved, json)
        ProviderKind.DEMO -> DemoSttAdapter(resolved.endpoint.id)
        ProviderKind.OPENAI_COMPAT_LLM ->
            error("${resolved.endpoint.id} is an LLM endpoint, not STT")
    }

    fun llm(resolved: ResolvedEndpoint): LlmProvider = when (resolved.endpoint.kind) {
        ProviderKind.OPENAI_COMPAT_LLM -> OpenAiCompatibleLlmAdapter(client, resolved, json)
        ProviderKind.DEMO -> DemoLlmAdapter(resolved.endpoint.id)
        else -> error("${resolved.endpoint.id} is not an LLM endpoint")
    }

    fun capabilitiesFor(kind: ProviderKind): SttCapabilities? = when (kind) {
        ProviderKind.SONIOX -> SonioxSttAdapter(
            client,
            ResolvedEndpoint(Presets.soniox, ""),
            json,
        ).capabilities
        ProviderKind.ELEVENLABS -> ElevenLabsSttAdapter(
            client,
            ResolvedEndpoint(Presets.elevenLabs, ""),
            json,
        ).capabilities
        ProviderKind.GROK_STT -> GrokSttAdapter(
            client,
            ResolvedEndpoint(Presets.grokStt, ""),
            json,
        ).capabilities
        ProviderKind.OPENAI_COMPAT_STREAMING -> OpenAiCompatibleStreamingSttAdapter(
            client,
            ResolvedEndpoint(Presets.openaiCompatStreaming, ""),
            json,
        ).capabilities
        ProviderKind.OPENAI_COMPAT_BATCH -> OpenAiCompatibleBatchSttAdapter(
            client,
            ResolvedEndpoint(Presets.openaiCompatBatch, ""),
            json,
        ).capabilities
        ProviderKind.DEMO -> DemoSttAdapter().capabilities
        ProviderKind.OPENAI_COMPAT_LLM -> null
    }
}

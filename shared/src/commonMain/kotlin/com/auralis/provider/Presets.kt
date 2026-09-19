package com.auralis.provider

object Presets {
    val soniox = ProviderEndpoint(
        id = "stt-soniox",
        kind = ProviderKind.SONIOX,
        displayName = "Soniox Realtime",
        baseUrl = "wss://stt-rt.soniox.com/transcribe-websocket",
        model = "stt-rt-v5",
    )

    val elevenLabs = ProviderEndpoint(
        id = "stt-elevenlabs",
        kind = ProviderKind.ELEVENLABS,
        displayName = "ElevenLabs Scribe v2 Realtime",
        baseUrl = "wss://api.elevenlabs.io/v1/speech-to-text/realtime",
        model = "scribe_v2_realtime",
        tokenEndpoint = "https://api.elevenlabs.io/v1/single-use-token/realtime_scribe",
    )

    val grokStt = ProviderEndpoint(
        id = "stt-grok",
        kind = ProviderKind.GROK_STT,
        displayName = "Grok STT",
        baseUrl = "wss://api.x.ai/v1/stt",
        model = "grok-voice-transcribe-2.0",
        tokenEndpoint = "https://api.x.ai/v1/realtime/client-secrets",
    )

    val openaiCompatBatch = ProviderEndpoint(
        id = "stt-openai-batch",
        kind = ProviderKind.OPENAI_COMPAT_BATCH,
        displayName = "OpenAI-compatible (batch / VAD)",
        baseUrl = "https://api.openai.com/v1",
        model = "whisper-1",
    )

    val openaiCompatStreaming = ProviderEndpoint(
        id = "stt-openai-stream",
        kind = ProviderKind.OPENAI_COMPAT_STREAMING,
        displayName = "OpenAI-compatible (streaming)",
        baseUrl = "wss://api.openai.com/v1/realtime",
        model = "gpt-4o-transcribe",
    )

    val grokLlm = ProviderEndpoint(
        id = "llm-grok",
        kind = ProviderKind.OPENAI_COMPAT_LLM,
        displayName = "Grok (OpenAI-compatible)",
        baseUrl = "https://api.x.ai/v1",
        model = "grok-4-1-fast-non-reasoning",
    )

    val geminiLlm = ProviderEndpoint(
        id = "llm-gemini",
        kind = ProviderKind.OPENAI_COMPAT_LLM,
        displayName = "Gemini (OpenAI-compatible)",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        model = "gemini-2.5-flash",
    )

    val openaiLlm = ProviderEndpoint(
        id = "llm-openai",
        kind = ProviderKind.OPENAI_COMPAT_LLM,
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4.1-mini",
    )

    val deepseekLlm = ProviderEndpoint(
        id = "llm-deepseek",
        kind = ProviderKind.OPENAI_COMPAT_LLM,
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-chat",
    )

    val siliconFlowLlm = ProviderEndpoint(
        id = "llm-siliconflow",
        kind = ProviderKind.OPENAI_COMPAT_LLM,
        displayName = "SiliconFlow",
        baseUrl = "https://api.siliconflow.cn/v1",
        model = "Qwen/Qwen2.5-7B-Instruct",
    )

    val demoStt = ProviderEndpoint(
        id = "stt-demo",
        kind = ProviderKind.DEMO,
        displayName = "Built-in demo (no key)",
        baseUrl = "local://demo",
        model = "demo",
    )

    val demoLlm = ProviderEndpoint(
        id = "llm-demo",
        kind = ProviderKind.DEMO,
        displayName = "Built-in demo (no key)",
        baseUrl = "local://demo",
        model = "demo",
    )

    val allEndpoints: List<ProviderEndpoint> = listOf(
        soniox, elevenLabs, grokStt, openaiCompatBatch, openaiCompatStreaming,
        grokLlm, geminiLlm, openaiLlm, deepseekLlm, siliconFlowLlm,
        demoStt, demoLlm,
    )

    val qualityMeeting = ProviderProfile(
        id = "profile-quality",
        name = "Client meeting — quality",
        description = "Soniox streaming STT + native translation fallback to a strong LLM",
        sttId = soniox.id,
        translationId = grokLlm.id,
        postProcessId = grokLlm.id,
        preferNativeTranslation = true,
        previewInterimTranslation = false,
    )

    val cheapDaily = ProviderProfile(
        id = "profile-cheap",
        name = "Daily — save money",
        description = "Grok STT (no Chinese) + small LLM. Switch STT for Chinese meetings.",
        sttId = grokStt.id,
        translationId = siliconFlowLlm.id,
        postProcessId = grokLlm.id,
        preferNativeTranslation = false,
    )

    val privateEndpoint = ProviderProfile(
        id = "profile-private",
        name = "Intranet — private endpoint",
        description = "OpenAI-compatible batch STT + custom Base URL LLM",
        sttId = openaiCompatBatch.id,
        translationId = openaiLlm.id,
        postProcessId = openaiLlm.id,
        preferNativeTranslation = false,
    )

    val demo = ProviderProfile(
        id = "profile-demo",
        name = "Demo (no key)",
        description = "Scripted captions for first-run and App Store review",
        sttId = demoStt.id,
        translationId = demoLlm.id,
        postProcessId = demoLlm.id,
        preferNativeTranslation = false,
    )

    val defaultProfiles: List<ProviderProfile> = listOf(qualityMeeting, cheapDaily, privateEndpoint, demo)

    val applyKeyLinks: Map<String, String> = mapOf(
        soniox.id to "https://console.soniox.com",
        elevenLabs.id to "https://elevenlabs.io/app/settings/api-keys",
        grokStt.id to "https://console.x.ai",
        grokLlm.id to "https://console.x.ai",
        geminiLlm.id to "https://aistudio.google.com/apikey",
        openaiLlm.id to "https://platform.openai.com/api-keys",
        openaiCompatBatch.id to "https://platform.openai.com/api-keys",
        openaiCompatStreaming.id to "https://platform.openai.com/api-keys",
        deepseekLlm.id to "https://platform.deepseek.com",
        siliconFlowLlm.id to "https://cloud.siliconflow.cn",
    )

    val grokSttLanguageNote =
        "Grok STT (2026-09) lists 25 languages including Portuguese, but not Chinese. " +
            "For zh+en meetings use Soniox or an OpenAI-compatible Chinese endpoint."
}

package com.auralis.provider

import com.auralis.error.ProviderError
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess

data class ConnectivityResult(
    val ok: Boolean,
    val message: String,
)

class ConnectivityTester(
    private val client: HttpClient,
) {
    suspend fun test(resolved: ResolvedEndpoint): ConnectivityResult {
        if (resolved.endpoint.kind == ProviderKind.DEMO) {
            return ConnectivityResult(true, "Demo provider is always ready.")
        }
        if (resolved.apiKey.isBlank()) {
            return ConnectivityResult(false, "API key is empty.")
        }
        val probe = httpProbeUrl(resolved.endpoint)
        return try {
            val response = client.get(probe) {
                header(HttpHeaders.Authorization, "Bearer ${resolved.apiKey}")
                if (resolved.endpoint.kind == ProviderKind.ELEVENLABS) {
                    header("xi-api-key", resolved.apiKey)
                }
                resolved.endpoint.extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            if (response.status.isSuccess() || response.status.value == 404) {
                ConnectivityResult(true, "Reachable (${response.status.value}).")
            } else {
                val err = ProviderError.fromHttp(
                    ProviderError.Slot.STT,
                    resolved.endpoint.id,
                    response.status.value,
                    response.bodyAsText().take(200),
                )
                ConnectivityResult(false, err.userMessage())
            }
        } catch (t: Throwable) {
            ConnectivityResult(
                false,
                "Cannot reach ${resolved.endpoint.baseUrl}. ${t.message ?: ""} " +
                    "If you are in mainland China, set a reachable Base URL or a proxy.",
            )
        }
    }

    private fun httpProbeUrl(endpoint: ProviderEndpoint): String {
        val base = endpoint.baseUrl
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .trimEnd('/')
        return when (endpoint.kind) {
            ProviderKind.SONIOX -> "https://api.soniox.com/v1/models"
            ProviderKind.ELEVENLABS -> "https://api.elevenlabs.io/v1/user"
            ProviderKind.GROK_STT, ProviderKind.OPENAI_COMPAT_LLM,
            ProviderKind.OPENAI_COMPAT_BATCH, ProviderKind.OPENAI_COMPAT_STREAMING,
            -> if (base.contains("/v1")) "$base/models" else "$base/v1/models"
            ProviderKind.DEMO -> base
        }
    }
}

package com.auralis.provider

import com.auralis.error.ProviderError
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ConnectivityResult(
    val ok: Boolean,
    val message: String,
    val models: List<String> = emptyList(),
)

class ConnectivityTester(
    private val client: HttpClient,
) {
    suspend fun test(resolved: ResolvedEndpoint): ConnectivityResult {
        if (resolved.endpoint.kind == ProviderKind.DEMO) {
            return ConnectivityResult(
                true,
                "Demo provider is always ready.",
                listOfNotNull(resolved.endpoint.model.takeIf { it.isNotBlank() }),
            )
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
            val body = runCatching { response.bodyAsText() }.getOrElse { "" }
            if (response.status.isSuccess() || response.status.value == 404) {
                ConnectivityResult(
                    true,
                    "Reachable (${response.status.value}).",
                    parseModelIds(body),
                )
            } else {
                val err = ProviderError.fromHttp(
                    ProviderError.Slot.STT,
                    resolved.endpoint.id,
                    response.status.value,
                    body.take(200),
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

private val modelListJson = Json { ignoreUnknownKeys = true }

fun parseModelIds(body: String): List<String> {
    if (body.isBlank()) return emptyList()
    return runCatching {
        val found = linkedSetOf<String>()
        collectModelIds(modelListJson.parseToJsonElement(body), found)
        found.toList()
    }.getOrElse { emptyList() }
}

private fun collectModelIds(element: JsonElement, out: MutableSet<String>) {
    when (element) {
        is JsonObject -> {
            element["data"]?.let { collectModelIdsFromArray(it, out) }
            element["models"]?.let { collectModelIdsFromArray(it, out) }
        }
        is JsonArray -> collectModelIdsFromArray(element, out)
        else -> Unit
    }
}

private fun collectModelIdsFromArray(element: JsonElement, out: MutableSet<String>) {
    val arr = element as? JsonArray ?: return
    for (item in arr) {
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.takeIf { it.isNotBlank() }?.let(out::add)
            is JsonObject -> {
                val id = item["id"]?.jsonPrimitive?.contentOrNull
                    ?: item["model"]?.jsonPrimitive?.contentOrNull
                    ?: item["name"]?.jsonPrimitive?.contentOrNull
                if (!id.isNullOrBlank()) out.add(id)
            }
            else -> Unit
        }
    }
}

package com.auralis.provider.llm

import com.auralis.error.ProviderError
import com.auralis.provider.LlmCapabilities
import com.auralis.provider.ResolvedEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class OpenAiCompatibleLlmAdapter(
    private val client: HttpClient,
    private val resolved: ResolvedEndpoint,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : LlmProvider {
    override val id: String = resolved.endpoint.id
    override val model: String = resolved.endpoint.model
    override val capabilities = LlmCapabilities()

    override fun stream(request: ChatRequest): Flow<ChatChunk> = flow {
        val response = client.post(url()) {
            header(HttpHeaders.Authorization, "Bearer ${resolved.apiKey}")
            resolved.endpoint.extraHeaders.forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(encode(request, stream = true))
        }
        if (!response.status.isSuccess()) {
            throw ProviderError.fromHttp(
                ProviderError.Slot.POST_PROCESS,
                id,
                response.status.value,
                response.bodyAsText().take(400),
            )
        }
        val channel = response.bodyAsChannel()
        val acc = StringBuilder()
        var inTok = 0
        var outTok = 0
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
            val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("delta")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull
            if (!delta.isNullOrEmpty()) {
                acc.append(delta)
                emit(ChatChunk(delta))
            }
            obj["usage"]?.jsonObject?.let { usage ->
                inTok = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: inTok
                outTok = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: outTok
            }
        }
        emit(ChatChunk(acc.toString(), inTok, outTok, done = true))
    }

    override suspend fun complete(request: ChatRequest): ChatChunk {
        val response = client.post(url()) {
            header(HttpHeaders.Authorization, "Bearer ${resolved.apiKey}")
            resolved.endpoint.extraHeaders.forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(encode(request, stream = false))
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw ProviderError.fromHttp(
                ProviderError.Slot.POST_PROCESS,
                id,
                response.status.value,
                body.take(400),
            )
        }
        val obj = json.parseToJsonElement(body).jsonObject
        val text = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        val usage = obj["usage"]?.jsonObject
        return ChatChunk(
            text = text,
            inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            done = true,
        )
    }

    private fun url(): String {
        val base = resolved.endpoint.baseUrl.trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun encode(request: ChatRequest, stream: Boolean): String = buildJsonObject {
        put("model", model)
        put("stream", stream)
        put("temperature", request.temperature)
        request.maxTokens?.let { put("max_tokens", it) }
        if (request.jsonMode) {
            put("response_format", buildJsonObject { put("type", "json_object") })
        }
        put(
            "messages",
            buildJsonArray {
                request.messages.forEach { msg ->
                    add(
                        buildJsonObject {
                            put("role", msg.role)
                            put("content", msg.content)
                        },
                    )
                }
            },
        )
    }.toString()
}

class DemoLlmAdapter(
    override val id: String = "llm-demo",
    override val model: String = "demo",
) : LlmProvider {
    override val capabilities = LlmCapabilities(streaming = false)

    override fun stream(request: ChatRequest): Flow<ChatChunk> = flow {
        emit(complete(request))
    }

    override suspend fun complete(request: ChatRequest): ChatChunk {
        val last = request.messages.lastOrNull()?.content.orEmpty()
        val system = request.messages.firstOrNull { it.role == "system" }?.content.orEmpty()
        val utterance = last.substringAfter("Utterance:", "").trim()
        val text = when {
            system.contains("Title this meeting") || last.contains("Title this meeting") ->
                when {
                    last.contains("供应商") -> "供应商对齐会"
                    last.contains("firmware", ignoreCase = true) -> "Firmware review"
                    else -> last.lineSequence().firstOrNull { it.isNotBlank() }?.take(24) ?: "Untitled session"
                }
            last.contains("Translate the LAST utterance") && utterance.isNotBlank() ->
                DEMO_TRANSLATIONS[utterance] ?: "[en] $utterance"
            system.contains("Extract action items") ->
                "- [ ] Firmware owner: freeze firmware tonight\n- [ ] Procurement: update the delivery date to 8 Oct"
            system.contains("Clean the transcript") ->
                last.substringAfter("transcript", last).trim()
            else ->
                """
                ## Summary
                The two sides aligned on pulling the delivery date from 12 Oct to 8 Oct, contingent on a firmware freeze tonight.

                ## Key points
                - Delivery can move earlier by two days
                - Firmware freeze is the gate

                ## Action items
                - [ ] Firmware owner: freeze tonight
                - [ ] Buyer: notify procurement

                ## Open questions
                - Who confirms the freeze to the supplier?
                """.trimIndent()
        }
        return ChatChunk(text, inputTokens = last.length / 4, outputTokens = text.length / 4, done = true)
    }

    companion object {
        private val DEMO_TRANSLATIONS = mapOf(
            "大家好，我们开始今天的供应商对齐会。" to "Hello everyone, let's start today's supplier alignment.",
            "Hello everyone, thanks for joining." to "大家好，谢谢参加。",
            "本周交期能否从十月十二日提前到十月八日？" to "Can we pull this week's delivery from 12 Oct to 8 Oct?",
            "We can pull in two days if the firmware freeze happens tonight." to "如果今晚冻结固件，可以提前两天。",
            "好，那行动项是今晚冻结固件，并更新给采购。" to "Action item: freeze firmware tonight and update procurement.",
        )
    }
}

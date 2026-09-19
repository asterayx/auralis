package com.auralis.pipeline

import com.auralis.provider.llm.ChatMessage
import com.auralis.provider.llm.ChatRequest
import com.auralis.provider.llm.LlmProvider

/** LIB-1: LLM session title with a first-line fallback. */
object SessionTitle {
    const val SYSTEM_PROMPT =
        "Title this meeting in at most 8 words. Use the transcript language. Return only the title, no quotes."

    fun fallback(transcript: String): String =
        transcript.lineSequence().firstOrNull { it.isNotBlank() }?.take(40)?.trim()
            ?: "Untitled session"

    suspend fun generate(llm: LlmProvider, transcript: String): String {
        if (transcript.isBlank()) return "Untitled session"
        return runCatching {
            val result = llm.complete(
                ChatRequest(
                    messages = listOf(
                        ChatMessage("system", SYSTEM_PROMPT),
                        ChatMessage("user", transcript.take(1_200)),
                    ),
                    temperature = 0.2,
                    maxTokens = 40,
                ),
            )
            result.text.lineSequence().firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.trim('"', '“', '”', '\'')
                ?.take(60)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: fallback(transcript)
    }
}

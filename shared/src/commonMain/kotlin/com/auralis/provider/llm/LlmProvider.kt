package com.auralis.provider.llm

import com.auralis.provider.LlmCapabilities
import kotlinx.coroutines.flow.Flow

data class ChatMessage(
    val role: String,
    val content: String,
)

data class ChatRequest(
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    val maxTokens: Int? = 2048,
    val jsonMode: Boolean = false,
)

data class ChatChunk(
    val text: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val done: Boolean = false,
)

interface LlmProvider {
    val id: String
    val model: String
    val capabilities: LlmCapabilities
    fun stream(request: ChatRequest): Flow<ChatChunk>
    suspend fun complete(request: ChatRequest): ChatChunk
}

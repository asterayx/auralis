package com.auralis.error

sealed class ProviderError(
    open val slot: Slot,
    open val providerId: String,
    override val message: String,
    open val retryable: Boolean,
) : Exception(message) {
    enum class Slot { STT, TRANSLATION, POST_PROCESS }

    data class Unauthorized(
        override val slot: Slot,
        override val providerId: String,
        override val message: String = "API key is invalid or expired.",
    ) : ProviderError(slot, providerId, message, retryable = false)

    data class Forbidden(
        override val slot: Slot,
        override val providerId: String,
        override val message: String = "This key does not have permission for this product.",
    ) : ProviderError(slot, providerId, message, retryable = false)

    data class InsufficientBalance(
        override val slot: Slot,
        override val providerId: String,
        override val message: String = "Provider balance or quota is exhausted.",
    ) : ProviderError(slot, providerId, message, retryable = false)

    data class RateLimited(
        override val slot: Slot,
        override val providerId: String,
        val retryAfterMs: Long? = null,
        override val message: String = "Rate limited. Retry shortly.",
    ) : ProviderError(slot, providerId, message, retryable = true)

    data class Network(
        override val slot: Slot,
        override val providerId: String,
        override val message: String = "Network interrupted. Audio is still being saved locally.",
    ) : ProviderError(slot, providerId, message, retryable = true)

    data class InvalidRequest(
        override val slot: Slot,
        override val providerId: String,
        override val message: String,
    ) : ProviderError(slot, providerId, message, retryable = false)

    data class Unavailable(
        override val slot: Slot,
        override val providerId: String,
        override val message: String = "Provider is temporarily unavailable.",
    ) : ProviderError(slot, providerId, message, retryable = true)

    data class Unknown(
        override val slot: Slot,
        override val providerId: String,
        override val message: String,
    ) : ProviderError(slot, providerId, message, retryable = true)

    fun userMessage(): String = when (this) {
        is Unauthorized -> "Authentication failed ($providerId). Check the API key."
        is Forbidden -> "Permission denied ($providerId). $message"
        is InsufficientBalance -> "Insufficient balance ($providerId). Top up at the provider."
        is RateLimited -> "Rate limited ($providerId). ${retryAfterMs?.let { "Retry in ${it / 1000}s." } ?: "Retry shortly."}"
        is Network -> "Network interrupted. Recording continues locally and will catch up."
        is InvalidRequest -> "Invalid request ($providerId): $message"
        is Unavailable -> "Provider $providerId is unavailable. Will retry / fail over."
        is Unknown -> "Provider error ($providerId): $message"
    }

    companion object {
        fun fromHttp(
            slot: Slot,
            providerId: String,
            status: Int,
            body: String,
        ): ProviderError = when (status) {
            401 -> Unauthorized(slot, providerId, body.ifBlank { "Unauthorized" })
            402 -> InsufficientBalance(slot, providerId, body.ifBlank { "Payment required" })
            403 -> Forbidden(slot, providerId, body.ifBlank { "Forbidden" })
            429 -> RateLimited(slot, providerId, message = body.ifBlank { "Too many requests" })
            in 400..499 -> InvalidRequest(slot, providerId, body.ifBlank { "HTTP $status" })
            in 500..599 -> Unavailable(slot, providerId, body.ifBlank { "HTTP $status" })
            else -> Unknown(slot, providerId, body.ifBlank { "HTTP $status" })
        }
    }
}

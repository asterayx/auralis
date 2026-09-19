package com.auralis.cost

import com.auralis.error.ProviderError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CostEstimatorTest {
    @Test
    fun grokHourCostsTwentyCents() {
        val usd = CostEstimator.estimateSimple(
            audioMs = 3_600_000,
            sttProviderId = "stt-grok",
            llmInput = 0,
            llmOutput = 0,
        )
        assertEquals(0.2, usd, 0.0001)
    }

    @Test
    fun oneHourMeetingUnderOneDollarTarget() {
        val usd = CostEstimator.estimateSimple(
            audioMs = 3_600_000,
            sttProviderId = "stt-grok",
            llmInput = 20_000,
            llmOutput = 2_000,
            llmProviderId = "llm-grok",
        )
        assertTrue(usd < 1.0, "expected <$1, got $usd")
    }

    @Test
    fun errorsAreSpecific() {
        val e = ProviderError.fromHttp(ProviderError.Slot.STT, "stt-soniox", 429, "slow down")
        assertTrue(e is ProviderError.RateLimited)
        assertTrue(e.userMessage().contains("Rate limited"))
        val net = ProviderError.Network(ProviderError.Slot.STT, "stt-soniox")
        assertTrue(net.userMessage().contains("Recording continues"))
    }
}

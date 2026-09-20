package com.auralis.app

import com.auralis.provider.InMemorySecureStore
import com.auralis.provider.Presets
import com.auralis.provider.SecureStore
import com.auralis.provider.secretAlias
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract both Android and iOS clients follow: keys go through
 * [AuralisApp.saveKey] → [com.auralis.provider.SecureStore] +
 * [com.auralis.provider.ConnectivityTester], never a platform-only store call.
 */
class KernelClientContractTest {
    @Test
    fun saveKeyUsesSecureStoreAndConnectivityTester() = runBlocking {
        val secrets = InMemorySecureStore()
        val app = AuralisApp(secrets = secrets)
        app.load()
        val demo = app.saveKey(Presets.demoStt.id, "unused")
        assertTrue(demo.ok)
        assertTrue(demo.message.contains("Demo", ignoreCase = true))
        assertEquals("unused", secrets.get(secretAlias(Presets.demoStt.id)))
    }

    @Test
    fun emptyLiveKeyFailsConnectivity() = runBlocking {
        val app = AuralisApp()
        app.load()
        val result = app.saveKey(Presets.soniox.id, "")
        assertTrue(!result.ok)
        assertTrue(result.message.contains("empty", ignoreCase = true))
    }

    @Test
    fun blankSaveRetestsStoredKeyWithoutOverwriting() = runBlocking {
        val secrets = InMemorySecureStore()
        secrets.put(secretAlias(Presets.demoStt.id), "stored-key")
        val app = AuralisApp(secrets = secrets)
        app.load()
        val result = app.saveKey(Presets.demoStt.id, "  ")
        assertTrue(result.ok)
        assertEquals("stored-key", secrets.get(secretAlias(Presets.demoStt.id)))
        assertTrue(result.models.contains("demo"))
    }

    @Test
    fun setProfileSlotsUpdatesCombination() = runBlocking {
        val app = AuralisApp()
        app.load()
        app.setProfileSlots(
            Presets.qualityMeeting.id,
            Presets.grokStt.id,
            Presets.deepseekLlm.id,
            Presets.openaiLlm.id,
        )
        val profile = app.settings.value.profiles.first { it.id == Presets.qualityMeeting.id }
        assertEquals(Presets.grokStt.id, profile.sttId)
        assertEquals(Presets.deepseekLlm.id, profile.translationId)
        assertEquals(Presets.openaiLlm.id, profile.postProcessId)
    }

    @Test
    fun probeModelsDemoReturnsCandidate() = runBlocking {
        val app = AuralisApp()
        app.load()
        val result = app.probeModels(Presets.demoStt.id, "")
        assertTrue(result.ok)
        assertTrue(result.models.contains("demo"))
    }

    @Test
    fun hasKeyIsFalseUntilSecretExists() = runBlocking {
        val secrets = InMemorySecureStore()
        val app = AuralisApp(secrets = secrets)
        app.load()
        assertTrue(!app.hasKey(Presets.soniox.id))
        assertTrue(app.hasKey(Presets.demoStt.id))
        secrets.put(secretAlias(Presets.soniox.id), "sk-test")
        assertTrue(app.hasKey(Presets.soniox.id))
    }

    @Test
    fun saveKeyPutFailureIsConnectivityError() = runBlocking {
        val secrets = object : SecureStore {
            override suspend fun put(alias: String, secret: String) {
                error("Keychain put failed: errSecParam (-50). Invalid item attributes.")
            }
            override suspend fun get(alias: String): String? = null
            override suspend fun delete(alias: String) = Unit
        }
        val app = AuralisApp(secrets = secrets)
        app.load()
        val result = app.saveKey(Presets.soniox.id, "sk-test")
        assertTrue(!result.ok)
        assertTrue(result.message.contains("-50") || result.message.contains("Keychain"))
    }
}

package com.auralis.app

import com.auralis.provider.InMemorySecureStore
import com.auralis.provider.Presets
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
}

package com.auralis.android.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.auralis.provider.SecureStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * KEY-1: API keys live in Android Keystore + encrypted prefs, never in SQLite.
 */
class KeystoreSecureStore(context: Context) : SecureStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auralis.secrets", Context.MODE_PRIVATE)

    override suspend fun put(alias: String, secret: String) {
        val key = secretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(secret.toByteArray())
        prefs.edit()
            .putString("$alias.iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(alias, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    override suspend fun get(alias: String): String? {
        val payload = prefs.getString(alias, null) ?: return null
        val iv = prefs.getString("$alias.iv", null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)))
    }

    override suspend fun delete(alias: String) {
        prefs.edit().remove(alias).remove("$alias.iv").apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(MASTER, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                MASTER,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val MASTER = "auralis.master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

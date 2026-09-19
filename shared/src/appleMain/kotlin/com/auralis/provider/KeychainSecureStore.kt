package com.auralis.provider

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.posix.memcpy
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * KEY-1: API keys live in the Apple Keychain, never in the JSON library.
 * Account names are the aliases [secretAlias] already prefixes (`auralis.provider.<id>`).
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainSecureStore(
    private val service: String = "com.auralis.app",
) : SecureStore {
    override suspend fun put(alias: String, secret: String) {
        delete(alias)
        val data = (secret as NSString).dataUsingEncoding(NSUTF8StringEncoding)
            ?: error("Keychain put failed: could not encode secret")
        val status = withCfDictionary(
            baseQuery(alias) + mapOf(
                kSecValueData to data,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ),
        ) { SecItemAdd(it, null) }
        require(status == errSecSuccess) { "Keychain put failed: $status" }
    }

    override suspend fun get(alias: String): String? {
        val query = baseQuery(alias) + mapOf(
            kSecReturnData to true,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        return memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = withCfDictionary(query) { SecItemCopyMatching(it, out.ptr) }
            if (status == errSecItemNotFound || status != errSecSuccess) return@memScoped null
            val data = out.value as? NSData ?: return@memScoped null
            data.utf8String()
        }
    }

    override suspend fun delete(alias: String) {
        withCfDictionary(baseQuery(alias)) { SecItemDelete(it) }
    }

    private fun baseQuery(alias: String): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrAccount to alias,
        kSecAttrService to service,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.utf8String(): String {
    val size = length.toInt()
    if (size == 0) return ""
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return bytes.decodeToString()
}

@OptIn(ExperimentalForeignApi::class)
private inline fun withCfDictionary(query: Map<Any?, Any?>, block: (CFDictionaryRef?) -> Int32): Int32 {
    val retained = CFBridgingRetain(query)
    return try {
        block(retained as CFDictionaryRef?)
    } finally {
        if (retained != null) CFRelease(retained)
    }
}

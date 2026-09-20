package com.auralis.provider

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.posix.memcpy
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
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
 *
 * Queries must be real [NSMutableDictionary]s. A Kotlin [Map] retained via
 * CFBridgingRetain is not a valid SecItem dictionary and returns errSecParam (-50).
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainSecureStore(
    private val service: String = "com.auralis.app",
) : SecureStore {
    override suspend fun put(alias: String, secret: String) {
        val data = (secret as NSString).dataUsingEncoding(NSUTF8StringEncoding)
            ?: error("Keychain put failed: could not encode secret")
        delete(alias)
        val status = add(alias, data)
        if (status == errSecSuccess || status == errSecDuplicateItem) return
        error(secMessage("put", status))
    }

    override suspend fun get(alias: String): String? = runCatching {
        memScopedGet(alias)
    }.getOrNull()

    override suspend fun delete(alias: String) {
        val status = SecItemDelete(baseQuery(alias).asCf())
        if (status != errSecSuccess && status != errSecItemNotFound) {
            println("Keychain delete: ${secMessage("delete", status)}")
        }
    }

    private fun add(alias: String, data: NSData): Int {
        val query = baseQuery(alias)
        query.setObject(data, forKey = nsKey(kSecValueData))
        query.setObject(kSecAttrAccessibleAfterFirstUnlock as Any, forKey = nsKey(kSecAttrAccessible))
        return SecItemAdd(query.asCf(), null)
    }

    private fun memScopedGet(alias: String): String? {
        val query = baseQuery(alias)
        query.setObject(kCFBooleanTrue as Any, forKey = nsKey(kSecReturnData))
        query.setObject(kSecMatchLimitOne as Any, forKey = nsKey(kSecMatchLimit))
        return memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query.asCf(), out.ptr)
            if (status == errSecItemNotFound || status != errSecSuccess) return@memScoped null
            val data = out.value as? NSData ?: return@memScoped null
            data.utf8String()
        }
    }

    private fun baseQuery(alias: String): NSMutableDictionary {
        val query = NSMutableDictionary()
        query.setObject(kSecClassGenericPassword as Any, forKey = nsKey(kSecClass))
        query.setObject(service, forKey = nsKey(kSecAttrService))
        query.setObject(alias, forKey = nsKey(kSecAttrAccount))
        return query
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun nsKey(ref: CFStringRef?): NSString =
    ref as? NSString ?: error("missing Security attribute key")

@OptIn(ExperimentalForeignApi::class)
private fun NSMutableDictionary.asCf(): CFDictionaryRef? = this as CFDictionaryRef?

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

private fun secMessage(op: String, status: Int): String = when (status) {
    -50 -> "Keychain $op failed: errSecParam (-50). Invalid item attributes."
    -34018 -> "Keychain $op failed: missing entitlement (-34018)."
    else -> "Keychain $op failed: $status"
}

package com.auralis.provider

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.posix.memcpy
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess

/**
 * KEY-1: API keys live in the Apple Keychain, never in the JSON library.
 *
 * Kotlin/Native cannot `as? NSString` the Security `CFStringRef` constants
 * (`kSecClass`, …) — that was "missing security attribute key". Use the
 * documented SecItem string keys instead; they are what those CFSTR()s are.
 *
 * @see https://developer.apple.com/documentation/security/ksecclass
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainSecureStore(
    private val service: String = "com.auralis.app",
) : SecureStore {
    override suspend fun put(alias: String, secret: String) {
        val data = (secret as NSString).dataUsingEncoding(NSUTF8StringEncoding)
            ?: error("Keychain put failed: could not encode secret")
        when (val added = SecItemAdd(addQuery(alias, data).asCf(), null)) {
            errSecSuccess -> return
            errSecDuplicateItem -> {
                val updated = SecItemUpdate(baseQuery(alias).asCf(), updateAttrs(data).asCf())
                if (updated == errSecSuccess) return
                error(secMessage("update", updated))
            }
            else -> {
                delete(alias)
                val retry = SecItemAdd(addQuery(alias, data).asCf(), null)
                if (retry == errSecSuccess || retry == errSecDuplicateItem) return
                error(secMessage("put", retry))
            }
        }
    }

    override suspend fun get(alias: String): String? = runCatching {
        val query = baseQuery(alias)
        query.setObject(NSNumber.numberWithBool(true), forKey = KEY_RETURN_DATA)
        query.setObject(VAL_MATCH_ONE, forKey = KEY_MATCH_LIMIT)
        memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query.asCf(), out.ptr)
            if (status == errSecItemNotFound || status != errSecSuccess) return@memScoped null
            val data = out.value as? NSData ?: return@memScoped null
            data.utf8String()
        }
    }.getOrNull()

    override suspend fun delete(alias: String) {
        val status = SecItemDelete(baseQuery(alias).asCf())
        if (status != errSecSuccess && status != errSecItemNotFound) {
            println("Keychain delete: ${secMessage("delete", status)}")
        }
    }

    private fun baseQuery(alias: String): NSMutableDictionary {
        val query = NSMutableDictionary()
        query.setObject(VAL_GENERIC_PASSWORD, forKey = KEY_CLASS)
        query.setObject(service, forKey = KEY_SERVICE)
        query.setObject(alias, forKey = KEY_ACCOUNT)
        return query
    }

    private fun addQuery(alias: String, data: NSData): NSMutableDictionary {
        val query = baseQuery(alias)
        query.setObject(data, forKey = KEY_VALUE_DATA)
        query.setObject(VAL_AFTER_FIRST_UNLOCK, forKey = KEY_ACCESSIBLE)
        return query
    }

    private fun updateAttrs(data: NSData): NSMutableDictionary {
        val attrs = NSMutableDictionary()
        attrs.setObject(data, forKey = KEY_VALUE_DATA)
        attrs.setObject(VAL_AFTER_FIRST_UNLOCK, forKey = KEY_ACCESSIBLE)
        return attrs
    }
}

// SecItem.h CFSTR values — NSString keys work with NSMutableDictionary on KN.
private const val KEY_CLASS = "class"
private const val KEY_SERVICE = "svce"
private const val KEY_ACCOUNT = "acct"
private const val KEY_VALUE_DATA = "v_Data"
private const val KEY_ACCESSIBLE = "pdmn"
private const val KEY_RETURN_DATA = "r_Data"
private const val KEY_MATCH_LIMIT = "m_Limit"
private const val VAL_GENERIC_PASSWORD = "genp"
private const val VAL_AFTER_FIRST_UNLOCK = "ck"
private const val VAL_MATCH_ONE = "m_LimitOne"

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

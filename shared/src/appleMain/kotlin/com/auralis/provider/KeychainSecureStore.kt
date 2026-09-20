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
import platform.Foundation.NSCopyingProtocol
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
 * Security `kSec*` symbols are CFStringRef C pointers. Kotlin/Native cannot
 * `as? NSString` them ("missing security attribute key"). Use the documented
 * SecItem string names as NSString keys on NSMutableDictionary.
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
        when (val added = addQuery(alias, data).useCf { SecItemAdd(it, null) }) {
            errSecSuccess -> return
            errSecDuplicateItem -> {
                val updated = baseQuery(alias).useCf { query ->
                    updateAttrs(data).useCf { attrs -> SecItemUpdate(query, attrs) }
                }
                if (updated == errSecSuccess) return
                error(secMessage("update", updated))
            }
            else -> {
                delete(alias)
                val retry = addQuery(alias, data).useCf { SecItemAdd(it, null) }
                if (retry == errSecSuccess || retry == errSecDuplicateItem) return
                error(secMessage("put", retry))
            }
        }
    }

    override suspend fun get(alias: String): String? = runCatching {
        val query = baseQuery(alias)
        query.setObject(NSNumber(bool = true), forKey = ns(KEY_RETURN_DATA))
        query.setObject(ns(VAL_MATCH_ONE), forKey = ns(KEY_MATCH_LIMIT))
        memScoped {
            val out = alloc<CFTypeRefVar>()
            val status = query.useCf { SecItemCopyMatching(it, out.ptr) }
            if (status == errSecItemNotFound || status != errSecSuccess) return@memScoped null
            val data = out.value as? NSData ?: return@memScoped null
            data.utf8String()
        }
    }.getOrNull()

    override suspend fun delete(alias: String) {
        val status = baseQuery(alias).useCf { SecItemDelete(it) }
        if (status != errSecSuccess && status != errSecItemNotFound) {
            println("Keychain delete: ${secMessage("delete", status)}")
        }
    }

    private fun baseQuery(alias: String): NSMutableDictionary {
        val query = NSMutableDictionary()
        query.setObject(ns(VAL_GENERIC_PASSWORD), forKey = ns(KEY_CLASS))
        query.setObject(ns(service), forKey = ns(KEY_SERVICE))
        query.setObject(ns(alias), forKey = ns(KEY_ACCOUNT))
        return query
    }

    private fun addQuery(alias: String, data: NSData): NSMutableDictionary {
        val query = baseQuery(alias)
        query.setObject(data, forKey = ns(KEY_VALUE_DATA))
        query.setObject(ns(VAL_AFTER_FIRST_UNLOCK), forKey = ns(KEY_ACCESSIBLE))
        return query
    }

    private fun updateAttrs(data: NSData): NSMutableDictionary {
        val attrs = NSMutableDictionary()
        attrs.setObject(data, forKey = ns(KEY_VALUE_DATA))
        attrs.setObject(ns(VAL_AFTER_FIRST_UNLOCK), forKey = ns(KEY_ACCESSIBLE))
        return attrs
    }
}

// SecItem.h CFSTR values. NSMutableDictionary.setObject(forKey:) needs
// NSCopyingProtocol keys — Kotlin String is not that type. NSNumber.numberWithBool
// is not in the KN Foundation bindings; use the bool constructor instead.
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

private fun ns(value: String): NSCopyingProtocol = value as NSString

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> NSMutableDictionary.useCf(block: (CFDictionaryRef?) -> T): T {
    val cf = CFBridgingRetain(this) as CFDictionaryRef?
    return try {
        block(cf)
    } finally {
        if (cf != null) CFRelease(cf)
    }
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

private fun secMessage(op: String, status: Int): String = when (status) {
    -50 -> "Keychain $op failed: errSecParam (-50). Invalid item attributes."
    -34018 -> "Keychain $op failed: missing entitlement (-34018)."
    else -> "Keychain $op failed: $status"
}

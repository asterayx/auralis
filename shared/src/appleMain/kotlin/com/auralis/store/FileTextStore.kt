package com.auralis.store

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Documents-directory JSON store for Apple. Same contract as the JVM [FileTextStore].
 */
@OptIn(ExperimentalForeignApi::class)
class FileTextStore(rootPath: String) : TextStore {
    private val root = rootPath.trimEnd('/')
    private val fm = NSFileManager.defaultManager

    init {
        fm.createDirectoryAtPath(root, withIntermediateDirectories = true, attributes = null, error = null)
    }

    override fun read(key: String): String? {
        val path = file(key)
        if (!fm.fileExistsAtPath(path)) return null
        return NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
    }

    override fun write(key: String, value: String) {
        (value as NSString).writeToFile(file(key), atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    override fun delete(key: String) {
        runCatching { fm.removeItemAtPath(file(key), error = null) }
    }

    override fun keys(): List<String> {
        val contents = fm.contentsOfDirectoryAtPath(root, error = null) ?: return emptyList()
        return contents.filterIsInstance<String>()
    }

    private fun file(key: String): String {
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$root/$safe"
    }
}

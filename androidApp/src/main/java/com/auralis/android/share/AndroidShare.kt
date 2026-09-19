package com.auralis.android.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.auralis.platform.SystemShare
import java.io.File

class AndroidShare(private val context: Context) : SystemShare {
    override fun share(fileName: String, mime: String, bytes: ByteArray) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, file.readText())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, fileName))
    }
}

package com.auralis.android.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import com.auralis.platform.BackgroundKeepAlive

class AndroidKeepAlive(private val context: Context) : BackgroundKeepAlive {
    override fun start(sessionTitle: String) {
        val intent = Intent(context, RecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    override fun stop() {
        context.stopService(Intent(context, RecordingService::class.java))
    }
}

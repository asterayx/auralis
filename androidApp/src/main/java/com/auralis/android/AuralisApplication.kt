package com.auralis.android

import android.app.Application
import com.auralis.app.AuralisApp
import com.auralis.android.audio.AndroidAudioCapture
import com.auralis.android.audio.AndroidKeepAlive
import com.auralis.android.audio.AndroidPcmPlayer
import com.auralis.android.security.KeystoreSecureStore
import com.auralis.android.share.AndroidShare
import com.auralis.audio.FileAudioArchive
import com.auralis.store.FileTextStore
import com.auralis.store.JsonSessionRepository
import com.auralis.store.JsonSettingsStore
import java.io.File

class AuralisApplication : Application() {
    lateinit var app: AuralisApp
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val root = File(filesDir, "auralis").apply { mkdirs() }
        val disk = FileTextStore(root)
        val archive = FileAudioArchive(File(root, "audio").apply { mkdirs() })
        app = AuralisApp(
            sessions = JsonSessionRepository(disk),
            settingsStore = JsonSettingsStore(disk),
            secrets = KeystoreSecureStore(this),
            audio = AndroidAudioCapture(File(root, "capture").apply { mkdirs() }),
            keepAlive = AndroidKeepAlive(this),
            share = AndroidShare(this),
            audioArchive = archive,
            playback = AndroidPcmPlayer(archive),
        )
    }

    companion object {
        lateinit var instance: AuralisApplication
            private set
    }
}

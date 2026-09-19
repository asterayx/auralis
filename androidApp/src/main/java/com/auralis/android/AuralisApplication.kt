package com.auralis.android

import android.app.Application
import com.auralis.app.AuralisApp
import com.auralis.android.security.KeystoreSecureStore

class AuralisApplication : Application() {
    lateinit var app: AuralisApp
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        app = AuralisApp(secrets = KeystoreSecureStore(this))
    }

    companion object {
        lateinit var instance: AuralisApplication
            private set
    }
}

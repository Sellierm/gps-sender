package com.mathieu.gpssender

import android.app.Application

/** Point d'entrée du process : le journal est prêt avant le service, le BootReceiver et le watchdog. */
class GpsSenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
    }
}

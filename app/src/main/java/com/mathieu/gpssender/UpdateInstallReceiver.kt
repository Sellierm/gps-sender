package com.mathieu.gpssender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** Statut d'une installation lancée par [UpdateManager] (PendingIntent de la session). */
class UpdateInstallReceiver : BroadcastReceiver() {

    private val TAG = "GPS_SENDER_LOG"

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val version = intent.getStringExtra(EXTRA_VERSION_NAME) ?: "?"
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    AppLogger.e(TAG, "Mise à jour $version : confirmation demandée mais écran introuvable")
                    return
                }
                AppLogger.i(TAG, "Mise à jour $version : confirmation demandée à l'utilisateur")
                UpdateManager.requestConfirmation(context, confirm, version)
            }
            // Rarement reçu : Android arrête le process pendant l'installation (voir BootReceiver).
            PackageInstaller.STATUS_SUCCESS -> AppLogger.i(TAG, "Mise à jour $version installée")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppLogger.e(TAG, "Mise à jour $version échouée (statut $status) : $message")
            }
        }
    }

    companion object {
        const val EXTRA_VERSION_NAME = "com.mathieu.gpssender.VERSION_NAME"
    }
}

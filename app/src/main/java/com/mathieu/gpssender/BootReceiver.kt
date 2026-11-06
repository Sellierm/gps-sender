package com.mathieu.gpssender

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    private val TAG = "GPS_SENDER_LOG"

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> AppLogger.d(TAG, "BootReceiver: ACTION_BOOT_COMPLETED reçu.")
            // Après une mise à jour, Android a arrêté le process : on relance le service comme au boot.
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AppLogger.i(TAG, "BootReceiver: appli mise à jour en ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                UpdateManager.onPackageReplaced(context)
            }
            else -> return
        }

        if (!AppPreferences.isServiceEnabled(context)) {
            AppLogger.d(TAG, "BootReceiver: service arrêté par l'utilisateur, pas de démarrage.")
            return
        }

        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        if (hasFineLocation && hasBackgroundLocation) {
            AppLogger.d(TAG, "BootReceiver: Permissions OK, démarrage du service.")
            PositionService.start(context)
            GpsWatchdogWorker.schedule(context)
        } else {
            AppLogger.e(TAG, "BootReceiver: Permissions manquantes (Fine: $hasFineLocation, Background: $hasBackgroundLocation)")
        }
    }
}

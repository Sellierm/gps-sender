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
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AppLogger.d(TAG, "BootReceiver: ACTION_BOOT_COMPLETED reçu.")

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

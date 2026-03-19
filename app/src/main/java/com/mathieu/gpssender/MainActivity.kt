package com.mathieu.gpssender

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val TAG = "GPS_SENDER_LOG"

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                Log.d(TAG, "Permission ACCESS_FINE_LOCATION accordée")
                checkPermissionsAndStart()
            } else {
                Log.e(TAG, "Permission ACCESS_FINE_LOCATION refusée")
                statusText.text = "Permission de localisation refusée"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logDeviceInfo()

        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        startButton.setOnClickListener { checkPermissionsAndStart() }
        stopButton.setOnClickListener { stopGPSService() }

        Log.d(TAG, "MainActivity créée et UI initialisée")
        updateStatus()
        GpsWatchdogWorker.schedule(this)
    }

    private fun checkPermissionsAndStart() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineLocationGranted) {
            Log.d(TAG, "Demande permission ACCESS_FINE_LOCATION")
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundLocationGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!backgroundLocationGranted) {
                Log.w(TAG, "Permission ACCESS_BACKGROUND_LOCATION manquante")
                AlertDialog.Builder(this)
                    .setTitle("Permission en arrière-plan nécessaire")
                    .setMessage("Veuillez autoriser le suivi permanent dans les paramètres.")
                    .setPositiveButton("Aller aux paramètres") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", packageName, null)
                        startActivity(intent)
                    }
                    .setNegativeButton("Plus tard", null)
                    .show()
                return
            }
        }

        // Démarrage direct sans vérifier le GPS interne
        // La tablette CHCNAV utilise le port série /dev/ttyS4
        Log.d(TAG, "Démarrage direct du service (port série RTK)")
        startGPSService()
    }

    private fun startGPSService() {
        val intent = Intent(this, PositionService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Log.d(TAG, "Service PositionService démarré")
        updateStatus()
    }

    private fun stopGPSService() {
        val intent = Intent(this, PositionService::class.java)
        stopService(intent)
        Log.d(TAG, "Service PositionService arrêté")
        updateStatus()
        GpsWatchdogWorker.cancel(this)
    }

    private fun updateStatus() {
        val isRunning = isServiceRunning(PositionService::class.java)
        statusText.text = if (isRunning) "GPS Sender en cours" else "GPS Sender arrêté"
        Log.d(TAG, "Status mis à jour: ${statusText.text}")
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) return true
        }
        return false
    }
}

fun logDeviceInfo() {
    Log.d("DEVICE_INFO", "Manufacturer: ${Build.MANUFACTURER}")
    Log.d("DEVICE_INFO", "Model: ${Build.MODEL}")
    Log.d("DEVICE_INFO", "Device: ${Build.DEVICE}")
    Log.d("DEVICE_INFO", "Android SDK: ${Build.VERSION.SDK_INT}")
    Log.d("DEVICE_INFO", "Android version: ${Build.VERSION.RELEASE}")
}
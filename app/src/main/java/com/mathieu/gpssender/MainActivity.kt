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
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*


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

        // On démarre directement sans vérifier le GPS interne
        // car la tablette CHCNAV utilise le port série
        Log.d(TAG, "Démarrage direct du service (port série RTK)")
        startGPSService()
    }
    private fun checkLocationEnabledAndStart() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L
        ).build()

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        val client: SettingsClient = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            Log.d(TAG, "GPS activé, démarrage du service")
            startGPSService()
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    exception.startResolutionForResult(this, 1001)
                } catch (sendEx: Exception) {
                    Log.e(TAG, "Impossible de lancer la popup GPS", sendEx)
                    redirectToLocationSettings()
                }
            } else {
                Log.w(TAG, "GPS désactivé, pas de popup disponible")
                redirectToLocationSettings()
            }
        }
    }

    private fun redirectToLocationSettings() {
        Log.d(TAG, "Redirection vers les paramètres de localisation")
        statusText.text = "Veuillez activer les services de localisation."
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        startActivity(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            Log.d(TAG, "Retour popup GPS, vérification")
            checkLocationEnabledAndStart()
        }
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
    val manufacturer = Build.MANUFACTURER
    val model = Build.MODEL
    val device = Build.DEVICE
    val sdkInt = Build.VERSION.SDK_INT
    val release = Build.VERSION.RELEASE

    Log.d("DEVICE_INFO", "Manufacturer: $manufacturer")
    Log.d("DEVICE_INFO", "Model: $model")
    Log.d("DEVICE_INFO", "Device: $device")
    Log.d("DEVICE_INFO", "Android SDK: $sdkInt")
    Log.d("DEVICE_INFO", "Android version: $release")
}
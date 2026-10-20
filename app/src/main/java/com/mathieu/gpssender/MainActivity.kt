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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var viewLogsButton: Button

    private lateinit var deviceIdEditText: TextInputEditText
    private lateinit var portSpinner: Spinner
    private lateinit var baudRateSpinner: Spinner
    private lateinit var refreshRateSpinner: Spinner
    private lateinit var saveButton: Button

    private val TAG = "GPS_SENDER_LOG"

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                AppLogger.d(TAG, "Permission ACCESS_FINE_LOCATION accordée")
                checkPermissionsAndStart()
            } else {
                AppLogger.e(TAG, "Permission ACCESS_FINE_LOCATION refusée")
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
        viewLogsButton = findViewById(R.id.viewLogsButton)

        deviceIdEditText = findViewById(R.id.deviceIdEditText)
        portSpinner = findViewById(R.id.portSpinner)
        baudRateSpinner = findViewById(R.id.baudRateSpinner)
        refreshRateSpinner = findViewById(R.id.refreshRateSpinner)
        saveButton = findViewById(R.id.saveButton)

        setupSpinners()
        loadPreferences()

        startButton.setOnClickListener { checkPermissionsAndStart() }
        stopButton.setOnClickListener { stopGPSService() }
        viewLogsButton.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        saveButton.setOnClickListener { savePreferences() }

        AppLogger.d(TAG, "MainActivity créée et UI initialisée")
        updateStatus()
        GpsWatchdogWorker.schedule(this)
    }

    private fun setupSpinners() {
        val portLabels = AppPreferences.AVAILABLE_PORTS.map { it.first }
        val portAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, portLabels)
        portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        portSpinner.adapter = portAdapter

        val baudLabels = AppPreferences.AVAILABLE_BAUD_RATES.map { "$it baud" }
        val baudAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, baudLabels)
        baudAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        baudRateSpinner.adapter = baudAdapter

        val refreshLabels = AppPreferences.AVAILABLE_INTERVALS.map { it.first }
        val refreshAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, refreshLabels)
        refreshAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        refreshRateSpinner.adapter = refreshAdapter
    }

    private fun loadPreferences() {
        val currentDeviceId = AppPreferences.getDeviceId(this)
        deviceIdEditText.setText(currentDeviceId)

        val currentPort = AppPreferences.getSerialPort(this)
        val portIndex = AppPreferences.AVAILABLE_PORTS.indexOfFirst { it.second == currentPort }
        if (portIndex >= 0) {
            portSpinner.setSelection(portIndex)
        } else {
            portSpinner.setSelection(0)
        }

        val currentBaud = AppPreferences.getBaudRate(this)
        val baudIndex = AppPreferences.AVAILABLE_BAUD_RATES.indexOf(currentBaud)
        if (baudIndex >= 0) {
            baudRateSpinner.setSelection(baudIndex)
        } else {
            val defaultIndex = AppPreferences.AVAILABLE_BAUD_RATES.indexOf(AppPreferences.DEFAULT_BAUD_RATE)
            baudRateSpinner.setSelection(if (defaultIndex >= 0) defaultIndex else 0)
        }

        val currentInterval = AppPreferences.getSendIntervalMs(this)
        val intervalIndex = AppPreferences.AVAILABLE_INTERVALS.indexOfFirst { it.second == currentInterval }
        if (intervalIndex >= 0) {
            refreshRateSpinner.setSelection(intervalIndex)
        } else {
            val defaultIndex = AppPreferences.AVAILABLE_INTERVALS.indexOfFirst { it.second == AppPreferences.DEFAULT_SEND_INTERVAL_MS }
            refreshRateSpinner.setSelection(if (defaultIndex >= 0) defaultIndex else 0)
        }
    }

    private fun savePreferences() {
        val newDeviceId = deviceIdEditText.text.toString().trim()
        if (newDeviceId.isEmpty()) {
            Toast.makeText(this, "Veuillez saisir un ID d'appareil valide", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedPortIndex = portSpinner.selectedItemPosition
        val selectedPort = if (selectedPortIndex in AppPreferences.AVAILABLE_PORTS.indices) {
            AppPreferences.AVAILABLE_PORTS[selectedPortIndex].second
        } else {
            AppPreferences.DEFAULT_PORT_AUTO
        }

        val selectedBaudIndex = baudRateSpinner.selectedItemPosition
        val selectedBaudRate = if (selectedBaudIndex in AppPreferences.AVAILABLE_BAUD_RATES.indices) {
            AppPreferences.AVAILABLE_BAUD_RATES[selectedBaudIndex]
        } else {
            AppPreferences.DEFAULT_BAUD_RATE
        }

        val selectedIntervalIndex = refreshRateSpinner.selectedItemPosition
        val selectedIntervalMs = if (selectedIntervalIndex in AppPreferences.AVAILABLE_INTERVALS.indices) {
            AppPreferences.AVAILABLE_INTERVALS[selectedIntervalIndex].second
        } else {
            AppPreferences.DEFAULT_SEND_INTERVAL_MS
        }

        AppPreferences.setDeviceId(this, newDeviceId)
        AppPreferences.setSerialPort(this, selectedPort)
        AppPreferences.setBaudRate(this, selectedBaudRate)
        AppPreferences.setSendIntervalMs(this, selectedIntervalMs)

        Toast.makeText(this, "Paramètres enregistrés", Toast.LENGTH_SHORT).show()
        AppLogger.d(TAG, "Paramètres sauvegardés: ID=$newDeviceId, Port=$selectedPort, Baud=$selectedBaudRate, Interval=${selectedIntervalMs}ms")

        if (isServiceRunning(PositionService::class.java)) {
            val reloadIntent = Intent(this, PositionService::class.java).apply {
                action = PositionService.ACTION_RELOAD_SETTINGS
            }
            startService(reloadIntent)
            Toast.makeText(this, "Paramètres appliqués au service en cours", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndStart() {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineLocationGranted) {
            AppLogger.d(TAG, "Demande permission ACCESS_FINE_LOCATION")
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundLocationGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!backgroundLocationGranted) {
                AppLogger.w(TAG, "Permission ACCESS_BACKGROUND_LOCATION manquante")
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

        AppLogger.d(TAG, "Démarrage direct du service GPS Sender")
        startGPSService()
    }

    private fun startGPSService() {
        val intent = Intent(this, PositionService::class.java)
        ContextCompat.startForegroundService(this, intent)
        AppLogger.d(TAG, "Service PositionService démarré")
        updateStatus()
    }

    private fun stopGPSService() {
        val intent = Intent(this, PositionService::class.java)
        stopService(intent)
        AppLogger.d(TAG, "Service PositionService arrêté")
        updateStatus()
        GpsWatchdogWorker.cancel(this)
    }

    private fun updateStatus() {
        val isRunning = isServiceRunning(PositionService::class.java)
        statusText.text = if (isRunning) "GPS Sender en cours" else "GPS Sender arrêté"
        AppLogger.d(TAG, "Status mis à jour: ${statusText.text}")
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
    AppLogger.d("DEVICE_INFO", "Manufacturer: ${Build.MANUFACTURER}")
    AppLogger.d("DEVICE_INFO", "Model: ${Build.MODEL}")
    AppLogger.d("DEVICE_INFO", "Device: ${Build.DEVICE}")
    AppLogger.d("DEVICE_INFO", "Android SDK: ${Build.VERSION.SDK_INT}")
    AppLogger.d("DEVICE_INFO", "Android version: ${Build.VERSION.RELEASE}")
}

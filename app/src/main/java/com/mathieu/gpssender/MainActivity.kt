package com.mathieu.gpssender

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.material.textfield.TextInputEditText
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var viewLogsButton: Button

    private lateinit var deviceIdEditText: TextInputEditText
    private lateinit var apiUrlEditText: TextInputEditText
    private lateinit var apiKeyEditText: TextInputEditText
    private lateinit var portSpinner: Spinner
    private lateinit var baudRateSpinner: Spinner
    private lateinit var refreshRateSpinner: Spinner
    private lateinit var saveButton: Button

    private lateinit var updateStatusText: TextView
    private lateinit var allowInstallButton: Button
    private lateinit var checkUpdateButton: Button

    private val TAG = "GPS_SENDER_LOG"

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.POST_NOTIFICATIONS] == false) {
                AppLogger.w(TAG, "Permission POST_NOTIFICATIONS refusée — notification d'état masquée")
            }
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
        findViewById<View>(R.id.mainRoot).padForSystemBars(top = true, bottom = true, horizontal = true)
        logDeviceInfo()

        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        viewLogsButton = findViewById(R.id.viewLogsButton)

        deviceIdEditText = findViewById(R.id.deviceIdEditText)
        apiUrlEditText = findViewById(R.id.apiUrlEditText)
        apiKeyEditText = findViewById(R.id.apiKeyEditText)
        portSpinner = findViewById(R.id.portSpinner)
        baudRateSpinner = findViewById(R.id.baudRateSpinner)
        refreshRateSpinner = findViewById(R.id.refreshRateSpinner)
        saveButton = findViewById(R.id.saveButton)

        updateStatusText = findViewById(R.id.updateStatusText)
        allowInstallButton = findViewById(R.id.allowInstallButton)
        checkUpdateButton = findViewById(R.id.checkUpdateButton)

        setupSpinners()
        loadPreferences()

        startButton.setOnClickListener { checkPermissionsAndStart() }
        stopButton.setOnClickListener { stopGPSService() }
        viewLogsButton.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        saveButton.setOnClickListener { savePreferences() }
        checkUpdateButton.setOnClickListener { checkForUpdate() }
        allowInstallButton.setOnClickListener { openInstallPermissionSettings() }

        AppLogger.d(TAG, "MainActivity créée et UI initialisée")
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        updateStatus()
        updateUpdateSection()
    }

    override fun onPause() {
        isInForeground = false
        super.onPause()
    }

    private fun updateUpdateSection() {
        val allowed = UpdateManager.canInstallPackages(this)
        updateStatusText.text = buildString {
            append("Version installée : ${BuildConfig.VERSION_NAME}\n")
            append(
                if (allowed) "Mises à jour automatiques depuis le serveur, toutes les 6 h"
                else "Installation des mises à jour non autorisée : elles attendront une confirmation"
            )
        }
        allowInstallButton.visibility = if (allowed) View.GONE else View.VISIBLE
    }

    /** « Installer des applis inconnues » pour GPS Sender : nécessaire une fois par tablette. */
    private fun openInstallPermissionSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())
            )
        } catch (e: ActivityNotFoundException) {
            AppLogger.e(TAG, "Réglage « applis inconnues » introuvable sur cet appareil")
            Toast.makeText(this, "Réglage introuvable : Paramètres > Applis > GPS Sender", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkForUpdate() {
        checkUpdateButton.isEnabled = false
        checkUpdateButton.text = "Vérification…"
        thread(name = "VerifMiseAJour") {
            val outcome = UpdateManager.checkAndInstall(applicationContext)
            runOnUiThread {
                checkUpdateButton.isEnabled = true
                checkUpdateButton.text = "Vérifier les mises à jour"
                Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupSpinners() {
        val portLabels = AppPreferences.AVAILABLE_PORTS.map { it.first }
        val portAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, portLabels)
        portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        portSpinner.adapter = portAdapter

        val baudLabels = AppPreferences.AVAILABLE_BAUD_RATES.map { AppPreferences.baudRateLabel(it) }
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
        apiUrlEditText.setText(AppPreferences.getApiUrl(this))
        apiKeyEditText.setText(AppPreferences.getApiKey(this))

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

        val newApiUrl = apiUrlEditText.text.toString().trim()
        if (!newApiUrl.startsWith("http://") && !newApiUrl.startsWith("https://")) {
            apiUrlEditText.error = "L'URL doit commencer par http:// ou https://"
            return
        }

        val newApiKey = apiKeyEditText.text.toString().trim()
        if (newApiKey.isEmpty()) {
            apiKeyEditText.error = "Veuillez saisir la clé API"
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
        AppPreferences.setApiUrl(this, newApiUrl)
        AppPreferences.setApiKey(this, newApiKey)
        AppPreferences.setSerialPort(this, selectedPort)
        AppPreferences.setBaudRate(this, selectedBaudRate)
        AppPreferences.setSendIntervalMs(this, selectedIntervalMs)

        Toast.makeText(this, "Paramètres enregistrés", Toast.LENGTH_SHORT).show()
        AppLogger.d(TAG, "Paramètres sauvegardés: ID=$newDeviceId, URL=$newApiUrl, Port=$selectedPort, Baud=${AppPreferences.baudRateLabel(selectedBaudRate)}, Interval=${selectedIntervalMs}ms")

        if (PositionService.isActive) {
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
            val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissionLauncher.launch(permissions.toTypedArray())
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
                    .setMessage("Veuillez autoriser le suivi permanent (\"Toujours autoriser\") dans les paramètres pour que le service redémarre seul au boot.")
                    .setPositiveButton("Aller aux paramètres") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", packageName, null)
                        startActivity(intent)
                    }
                    .setNegativeButton("Démarrer quand même") { _, _ -> startGPSService() }
                    .show()
                return
            }
        }

        AppLogger.d(TAG, "Démarrage direct du service GPS Sender")
        startGPSService()
    }

    private fun startGPSService() {
        AppPreferences.setServiceEnabled(this, true)
        PositionService.start(this)
        GpsWatchdogWorker.schedule(this)
        AppLogger.d(TAG, "Service PositionService démarré")
        // Le service démarre de façon asynchrone : on laisse le temps à onStartCommand de s'exécuter
        statusText.postDelayed({ updateStatus() }, STATUS_REFRESH_DELAY_MS)
        requestBatteryOptimizationExemption()
    }

    /**
     * Sans cette exemption, Android 12+ refuse au watchdog de relancer le service depuis l'arrière-plan
     * et Doze peut retarder les envois. Le système affiche sa propre demande de confirmation.
     */
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AppLogger.i(TAG, "Demande d'exemption d'optimisation de batterie")
        try {
            @SuppressLint("BatteryLife") // Appli de suivi de flotte : le service doit tourner en continu
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Certains constructeurs n'exposent pas cette demande : on ouvre la liste des applis à la place
            AppLogger.w(TAG, "Demande d'exemption indisponible, ouverture des paramètres de batterie")
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                AppLogger.e(TAG, "Paramètres d'optimisation de batterie introuvables sur cet appareil")
            }
        }
    }

    private fun stopGPSService() {
        AppPreferences.setServiceEnabled(this, false)
        GpsWatchdogWorker.cancel(this)
        stopService(Intent(this, PositionService::class.java))
        AppLogger.d(TAG, "Service PositionService arrêté")
        statusText.postDelayed({ updateStatus() }, STATUS_REFRESH_DELAY_MS)
    }

    private fun updateStatus() {
        statusText.text = if (PositionService.isActive) "GPS Sender en cours" else "GPS Sender arrêté"
    }

    companion object {
        private const val STATUS_REFRESH_DELAY_MS = 500L

        /** Au premier plan : l'écran de confirmation d'une mise à jour peut s'ouvrir directement. */
        @Volatile
        var isInForeground = false
            private set
    }
}

fun logDeviceInfo() {
    AppLogger.d("DEVICE_INFO", "Manufacturer: ${Build.MANUFACTURER}")
    AppLogger.d("DEVICE_INFO", "Model: ${Build.MODEL}")
    AppLogger.d("DEVICE_INFO", "Device: ${Build.DEVICE}")
    AppLogger.d("DEVICE_INFO", "Android SDK: ${Build.VERSION.SDK_INT}")
    AppLogger.d("DEVICE_INFO", "Android version: ${Build.VERSION.RELEASE}")
}

package com.mathieu.gpssender

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.content.pm.PackageManager


class PositionService : Service() {

    private val TAG = "GPS_SENDER_LOG"
    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener

    private val httpClient = OkHttpClient()

    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "PositionServiceChannel"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PositionService onCreate()")

        createNotificationChannel()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationListener = LocationListener { location ->
            if (location != null) {
                val message = "Lat: ${location.latitude}, Lon: ${location.longitude}"
                Log.d(TAG, message)
                updateNotification(message)
                sendLocation(location)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "PositionService onStartCommand()")

        val notification = createNotification("Service en cours…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val hasFineLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasFineLocation) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L, // intervalle 5 sec
                    0f, // distance minimale
                    locationListener
                )
                Log.d(TAG, "Mises à jour GPS activées via LocationManager")
            } else {
                Log.e(TAG, "Permission ACCESS_FINE_LOCATION manquante")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors du démarrage du GPS", e)
            stopSelf()
        }

        return START_STICKY
    }

    private fun sendLocation(location: Location) {
        Thread {
            try {
                val json = JSONObject().apply {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("id", "6290")
                    put("timestamp", System.currentTimeMillis())
                    put("key", "yVi9gGEvcL2E")
                }

                val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("https://sellier.alwaysdata.net/api/location")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Log.i(TAG, "Position envoyée : ${response.code}")
                    else Log.e(TAG, "Erreur envoi : ${response.code} - ${response.body?.string()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de l'envoi de la position", e)
            }
        }.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Service GPS", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Sender")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(locationListener)
        Log.d(TAG, "PositionService détruit, GPS arrêté")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

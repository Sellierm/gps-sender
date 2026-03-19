package com.mathieu.gpssender

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class GpsWatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private val TAG = "GPS_SENDER_LOG"

    override fun doWork(): Result {
        val isRunning = isServiceRunning(PositionService::class.java)
        if (!isRunning) {
            Log.w(TAG, "Watchdog: PositionService mort, redémarrage...")
            val intent = Intent(applicationContext, PositionService::class.java)
            ContextCompat.startForegroundService(applicationContext, intent)
        } else {
            Log.d(TAG, "Watchdog: PositionService actif, rien à faire")
        }
        return Result.success()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    companion object {
        private const val WORK_NAME = "GpsWatchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GpsWatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d("GPS_SENDER_LOG", "Watchdog WorkManager planifié")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
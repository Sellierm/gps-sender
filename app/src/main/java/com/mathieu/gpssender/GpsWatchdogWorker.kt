package com.mathieu.gpssender

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class GpsWatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private val TAG = "GPS_SENDER_LOG"

    override fun doWork(): Result {
        if (!AppPreferences.isServiceEnabled(applicationContext)) {
            AppLogger.d(TAG, "Watchdog: service désactivé par l'utilisateur, rien à faire")
            return Result.success()
        }
        if (!PositionService.isActive) {
            AppLogger.w(TAG, "Watchdog: PositionService mort, redémarrage...")
            PositionService.start(applicationContext)
        } else {
            AppLogger.d(TAG, "Watchdog: PositionService actif, rien à faire")
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "GpsWatchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GpsWatchdogWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            AppLogger.d("GPS_SENDER_LOG", "Watchdog WorkManager planifié")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

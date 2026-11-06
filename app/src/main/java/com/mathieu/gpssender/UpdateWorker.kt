package com.mathieu.gpssender

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Vérification périodique des mises à jour ([UpdateManager]) : dès que le réseau est là, puis toutes les 6 h. */
class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        UpdateManager.checkAndInstall(applicationContext)
        // Un échec (réseau, serveur) attend simplement la vérification suivante.
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "MiseAJourGpsSender"

        /** Idempotent : appelé à chaque démarrage du process ([GpsSenderApp]). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

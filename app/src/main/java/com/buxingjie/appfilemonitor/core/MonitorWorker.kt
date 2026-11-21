package com.buxingjie.appfilemonitor.core

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MonitorWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pkgs = Prefs.monitored(applicationContext)
        if (pkgs.isEmpty()) return@withContext Result.success()
        Shell.awaitBinder(3000)
        Shell.refresh(applicationContext)
        for (pkg in pkgs) {
            runCatching {
                Scanner(applicationContext).scan(pkg, Prefs.includeDownload(applicationContext))
            }
        }
        Result.success()
    }

    companion object {
        private const val WORK_NAME = "app-file-monitor"

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }
}

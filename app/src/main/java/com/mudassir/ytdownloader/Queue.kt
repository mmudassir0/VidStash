package com.mudassir.ytdownloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/** Everything the UI does to the download queue goes through here. */
object Queue {

    const val RUNNER = "queue-runner"

    /** Jobs of items currently downloading, so a single item can be skipped. */
    internal val running = ConcurrentHashMap<String, Job>()

    class Skipped : CancellationException("Skipped")

    fun add(ctx: Context, items: List<DownloadItem>) {
        if (items.isEmpty()) return
        Store.add(items)
        kick(ctx)
    }

    /** Starts the background runner if it isn't running already. */
    fun kick(ctx: Context) {
        val settings = Settings(ctx)
        if (settings.paused || !Store.hasQueued()) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(RUNNER, ExistingWorkPolicy.KEEP, request(ctx))
    }

    /** Runs again once the current run ends (used when the rules paused the queue for now). */
    fun kickAfterCurrent(ctx: Context) {
        val settings = Settings(ctx)
        if (settings.paused || !Store.hasQueued()) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(RUNNER, ExistingWorkPolicy.APPEND_OR_REPLACE, request(ctx))
    }

    /** Restarts the runner so changed settings (Wi-Fi only, parallel) apply. Downloads resume. */
    fun restart(ctx: Context) {
        val settings = Settings(ctx)
        if (settings.paused || !Store.hasQueued()) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(RUNNER, ExistingWorkPolicy.REPLACE, request(ctx))
    }

    /** Network, charging and time-of-day rules for the runner. */
    private fun request(ctx: Context): androidx.work.OneTimeWorkRequest {
        val settings = Settings(ctx)
        // Over today's mobile-data limit: wait for Wi-Fi.
        val unmetered = settings.wifiOnly || DownloadRules.overMobileLimit(ctx)
        val b = OneTimeWorkRequestBuilder<QueueWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresCharging(settings.onlyWhileCharging)
                    .build()
            )
        val delay = DownloadRules.delayUntilAllowed(ctx)
        if (delay > 0) b.setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        return b.build()
    }

    fun pauseAll(ctx: Context) {
        Settings(ctx).paused = true
        WorkManager.getInstance(ctx).cancelUniqueWork(RUNNER)
    }

    fun resumeAll(ctx: Context) {
        Settings(ctx).paused = false
        kick(ctx)
    }

    fun skip(id: String) {
        val job = running[id]
        if (job != null) job.cancel(Skipped())
        else Store.update(id) { if (it.status == Status.QUEUED) it.copy(status = Status.SKIPPED, error = "Skipped") else it }
    }

    fun retry(ctx: Context, id: String) {
        Store.update(id) { it.copy(status = Status.QUEUED, error = null, attempts = 0) }
        kick(ctx)
    }

    fun retryAllFailed(ctx: Context) {
        Store.items.value.filter { it.status == Status.FAILED || it.status == Status.SKIPPED }
            .forEach { item -> Store.update(item.id) { it.copy(status = Status.QUEUED, error = null, attempts = 0) } }
        kick(ctx)
    }

    /** Removes an entry from the list. The downloaded file (if any) is kept. */
    fun remove(ctx: Context, id: String) {
        running[id]?.cancel(Skipped())
        Store.remove(id)
        ItemDownloader.deletePartial(ctx, id)
        Store.setProgress(id, null)
    }

    fun clearFinished(ctx: Context) {
        Store.items.value.filter { !it.isActive }.forEach { ItemDownloader.deletePartial(ctx, it.id) }
        Store.clearFinished()
    }

    /** Notification buttons. */
    class Control : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PAUSE -> pauseAll(ctx)
                ACTION_RESUME -> resumeAll(ctx)
            }
        }
    }

    const val ACTION_PAUSE = "com.mudassir.ytdownloader.PAUSE"
    const val ACTION_RESUME = "com.mudassir.ytdownloader.RESUME"
}

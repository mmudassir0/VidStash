package com.mudassir.ytdownloader

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * Background runner for the download queue. Downloads up to N items at once (Settings),
 * keeps going when the app is closed, and stops when the queue is empty.
 *
 * If it is stopped (paused, Wi-Fi lost, phone restarted), running items go back to the
 * queue and their partial files are resumed next time.
 */
class QueueWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val downloader = ItemDownloader(ctx)
    private val saved = AtomicInteger(0)
    private val failed = AtomicInteger(0)
    /** Items saved during this run, in order, for the "Play" button on the finished notification. */
    private val savedIds = java.util.Collections.synchronizedList(mutableListOf<String>())

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(foregroundInfo("Starting downloads…", -1))
        } catch (e: Exception) {
            // Android 12+ may refuse a foreground start from the background (e.g. nightly sync).
            // Keep going: the system may stop us early, and the queue then resumes later.
        }
        try {
            coroutineScope {
                val ticker = launch {
                    while (isActive) {
                        nm.notify(NOTIF_PROGRESS, progressNotification().build())
                        delay(1000)
                    }
                }
                while (Store.hasQueued() && !Settings(applicationContext).paused && DownloadRules.canStartNow(applicationContext)) {
                    val slots = Settings(applicationContext).parallel
                    (1..slots).map { launch { slotLoop() } }.forEach { it.join() }
                    delay(1500) // pick up anything added while the last items finished
                }
                ticker.cancel()
            }
        } finally {
            // Stopped mid-way: put running items back in the queue (partials are kept).
            Store.items.value.filter { it.status == Status.RUNNING }.forEach { item ->
                Store.update(item.id) { if (it.status == Status.RUNNING) it.copy(status = Status.QUEUED) else it }
                Store.setProgress(item.id, null)
            }
            nm.cancel(NOTIF_PROGRESS)
            summary()
            // Held back by the rules (night, charger, data limit): run again when allowed.
            if (!Settings(applicationContext).paused && Store.hasQueued() && !DownloadRules.canStartNow(applicationContext)) {
                Queue.kickAfterCurrent(applicationContext)
            }
        }
        Result.success()
    }

    // supervisorScope: a failed download must not cancel the slot (or the other slots).
    private suspend fun slotLoop() = supervisorScope {
        while (isActive && !Settings(applicationContext).paused) {
            // Night-only / charger / mobile data limit: finish what's running, start nothing new.
            if (!DownloadRules.canStartNow(applicationContext)) break
            val item = Store.claimNext() ?: break
            val job = async { downloader.process(item) }
            Queue.running[item.id] = job
            try {
                job.await()
                saved.incrementAndGet()
                Settings(applicationContext).youtubeTrouble = 0 // reading YouTube works
                Store.get(item.id)?.let { DownloadRules.addMobileUsage(applicationContext, it.bytes) }
                savedIds += item.id
            } catch (e: CancellationException) {
                if (!isActive) throw e // runner stopped: the finally in doWork re-queues it
                // Only this item was cancelled: skipped (or removed) from the Downloads screen.
                Store.update(item.id) { it.copy(status = Status.SKIPPED, error = "Skipped") }
                Store.setProgress(item.id, null)
                ItemDownloader.deletePartial(applicationContext, item.id)
            } catch (e: Exception) {
                onError(item, e)
                delay(3000)
            } finally {
                Queue.running.remove(item.id)
            }
        }
    }

    private fun onError(item: DownloadItem, e: Exception) {
        val why = e.message ?: e.javaClass.simpleName
        // The library couldn't make sense of YouTube's page (not a private/removed/region-locked
        // video, and not a rate limit): usually YouTube changed something and an update is needed.
        if (e is ExtractionException && e !is ContentNotAvailableException && e !is ReCaptchaException) {
            Settings(applicationContext).youtubeTrouble = System.currentTimeMillis()
        }
        val attempts = item.attempts + 1
        val fatal = e is ItemDownloader.Fatal
        Store.update(item.id) {
            if (!fatal && attempts < MAX_ATTEMPTS) it.copy(status = Status.QUEUED, attempts = attempts, error = "Retrying: $why")
            else it.copy(status = Status.FAILED, attempts = attempts, error = why, finishedAt = System.currentTimeMillis())
        }
        Store.setProgress(item.id, null)
        if (fatal || attempts >= MAX_ATTEMPTS) failed.incrementAndGet()
    }

    // ---------- notifications ----------

    private fun progressNotification(): NotificationCompat.Builder {
        val items = Store.items.value
        val running = items.filter { it.status == Status.RUNNING }
        val queued = items.count { it.status == Status.QUEUED }
        val prog = Store.progress.value
        val bytes = running.sumOf { prog[it.id]?.done ?: 0L }
        val total = running.sumOf { prog[it.id]?.total?.takeIf { t -> t > 0 } ?: 0L }
        val speed = running.sumOf { prog[it.id]?.speed ?: 0L }
        val pct = if (total > 0) ((bytes * 100) / total).toInt() else -1

        val title = when (running.size) {
            0 -> "Preparing…"
            1 -> running[0].title
            else -> "Downloading ${running.size} videos"
        }
        val parts = mutableListOf<String>()
        if (total > 0) parts += "${formatBytes(bytes)} / ${formatBytes(total)}"
        if (speed > 0) parts += "${formatBytes(speed)}/s"
        if (speed > 0 && total > bytes) parts += "${formatDuration((total - bytes) / speed)} left"
        if (queued > 0) parts += "$queued waiting"
        val stage = running.singleOrNull()?.let { prog[it.id]?.stage }?.takeIf { it.endsWith("…") }
        return base(title, stage ?: parts.joinToString(" · "), pct)
    }

    private fun base(title: String, text: String, pct: Int) =
        NotificationCompat.Builder(applicationContext, App.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .setContentIntent(App.openApp(applicationContext, MainActivity.TAB_DOWNLOADS))
            .addAction(android.R.drawable.ic_media_pause, "Pause all", control(Queue.ACTION_PAUSE))

    private fun control(action: String) = PendingIntent.getBroadcast(
        applicationContext, action.hashCode(),
        Intent(applicationContext, Queue.Control::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun foregroundInfo(text: String, pct: Int): ForegroundInfo {
        val n = base("YT Downloader", text, pct).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_PROGRESS, n)
    }

    private fun summary() {
        val s = saved.get()
        val f = failed.get()
        val paused = Settings(applicationContext).paused
        if (s + f == 0 && !paused) return
        val waiting = !paused && Store.hasQueued() // e.g. stopped because Wi-Fi was lost
        val text = buildList {
            if (s > 0) add("$s saved")
            if (f > 0) add("$f failed")
            if (paused) add("queue paused")
        }.joinToString(" · ")
        val b = NotificationCompat.Builder(applicationContext, App.CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(
                when {
                    paused -> "Downloads paused"
                    waiting -> "Downloads waiting for network"
                    else -> "Downloads finished"
                }
            )
            .setContentText(text)
            .setContentIntent(App.openApp(applicationContext, if (f == 0 && !paused) MainActivity.TAB_LIBRARY else MainActivity.TAB_DOWNLOADS))
            .setAutoCancel(true)
        if (paused) b.addAction(android.R.drawable.ic_media_play, "Resume", control(Queue.ACTION_RESUME))

        // Play straight from the notification.
        val done = savedIds.toList().mapNotNull { Store.get(it) }.filter { it.status == Status.DONE && it.fileUri != null }
        if (done.isNotEmpty()) {
            if (done.size == 1) {
                b.setContentTitle("Saved: ${done[0].title}")
                b.setContentText(listOfNotNull(done[0].folder, if (f > 0) "$f failed" else null).joinToString(" · ").ifEmpty { "Ready to watch offline" })
            }
            val play = PendingIntent.getActivity(
                applicationContext, 3001,
                PlayerActivity.intent(applicationContext, done, 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(android.R.drawable.ic_media_play, if (done.size == 1) "Play" else "Play all", play)
            b.addAction(android.R.drawable.ic_menu_agenda, "Library", App.openApp(applicationContext, MainActivity.TAB_LIBRARY))
        }
        nm.notify(NOTIF_DONE, b.build())
    }

    companion object {
        private const val NOTIF_PROGRESS = 1001
        private const val NOTIF_DONE = 1002
        private const val MAX_ATTEMPTS = 3
    }
}

package com.mudassir.vidstash

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Queues playlists/channels and checks saved ones for new videos. */
object Sync {

    /** How many of a channel's newest uploads a sync looks at. */
    const val CHANNEL_SYNC_DEPTH = 30

    /**
     * Queues [selected] videos from [list]. If [remember] is true the list is saved, and
     * every video in it now counts as "seen", so a later sync only picks up new uploads.
     */
    fun queue(ctx: Context, list: VideoList, selected: List<VideoEntry>, maxHeight: Int, remember: Boolean) {
        val position = list.videos.withIndex().associate { (i, v) -> v.id to i + 1 }
        val items = selected.map { v ->
            DownloadItem(
                url = v.url, title = v.title, thumbnail = v.thumbnail, maxHeight = maxHeight,
                durationSec = v.durationSec, uploader = v.uploader.ifBlank { null },
                prefix = if (list.isChannel) null else "%03d".format(position[v.id] ?: 0),
                folder = list.name
            )
        }
        Queue.add(ctx, items)
        if (remember) {
            val old = Store.playlists.value.firstOrNull { it.url == list.url }
            Store.savePlaylist(
                SavedPlaylist(
                    url = list.url, name = list.name, isChannel = list.isChannel, maxHeight = maxHeight,
                    seen = (old?.seen ?: emptySet()) + list.videos.map { it.id }
                )
            )
        }
    }

    /** Fetches the list again and queues videos not seen before. Returns how many were queued. */
    fun sync(ctx: Context, p: SavedPlaylist): Int {
        val list = if (p.isChannel) YouTube.getChannel(p.url, CHANNEL_SYNC_DEPTH) else YouTube.getPlaylist(p.url)
        val fresh = list.videos.filter { it.id !in p.seen }
        queue(ctx, list.copy(name = p.name), fresh, p.maxHeight, remember = true)
        return fresh.size
    }

    /** Syncs every saved list; returns (new videos, lists that failed). */
    fun syncAll(ctx: Context): Pair<Int, Int> {
        var added = 0
        var errors = 0
        Store.playlists.value.forEach { p ->
            runCatching { added += sync(ctx, p) }.onFailure { errors++ }
        }
        return added to errors
    }

    /** Turns the nightly background sync on or off to match Settings. */
    fun schedule(ctx: Context) {
        val s = Settings(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (!s.autoSync) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private const val PERIODIC = "auto-sync"
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (Store.playlists.value.isEmpty()) return Result.success()
        val (added, _) = Sync.syncAll(applicationContext)
        if (added > 0) {
            applicationContext.getSystemService(NotificationManager::class.java).notify(
                2001,
                NotificationCompat.Builder(applicationContext, App.CH_DONE)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Playlist sync")
                    .setContentText("$added new video(s) queued for download")
                    .setContentIntent(App.openApp(applicationContext, MainActivity.TAB_DOWNLOADS))
                    .setAutoCancel(true)
                    .build()
            )
        }
        return Result.success()
    }
}

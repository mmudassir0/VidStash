package com.mudassir.ytdownloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.schabi.newpipe.extractor.NewPipe

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The crash screen runs in its own process: skip normal start-up there.
        if (CrashReporter.isCrashProcess(this)) return
        CrashReporter.install(this)
        NewPipe.init(OkHttpDownloader.instance)
        Store.init(this)
        Store.onChange = { WidgetProvider.update(this) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        Sync.schedule(this)
        Queue.kick(this) // continue anything left in the queue
    }

    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"

        fun openApp(ctx: Context, tab: Int) = PendingIntent.getActivity(
            ctx, tab,
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

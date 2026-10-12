package com.mudassir.ytdownloader

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: what's downloading, plus Paste link / Search / Library. */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        Store.ensureLoaded(ctx)
        update(ctx, force = true)
    }

    companion object {
        private var lastStatus: String? = null

        /** Redraws all widgets; cheap when nothing visible changed. */
        fun update(ctx: Context, force: Boolean = false) {
            val app = ctx.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            val status = status()
            if (!force && status == lastStatus) return
            lastStatus = status

            val views = RemoteViews(app.packageName, R.layout.widget)
            views.setTextViewText(R.id.widgetStatus, status)
            views.setOnClickPendingIntent(
                R.id.widgetPaste,
                PendingIntent.getActivity(
                    app, 4001,
                    Intent(app, QuickDownloadActivity::class.java).setAction(QuickDownloadActivity.ACTION_PASTE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            views.setOnClickPendingIntent(
                R.id.widgetSearch,
                PendingIntent.getActivity(
                    app, 4002,
                    Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_SEARCH, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            views.setOnClickPendingIntent(R.id.widgetDownloads, App.openApp(app, MainActivity.TAB_LIBRARY))
            views.setOnClickPendingIntent(R.id.widgetStatus, App.openApp(app, MainActivity.TAB_DOWNLOADS))
            manager.updateAppWidget(ids, views)
        }

        private fun status(): String {
            val items = Store.items.value
            val running = items.count { it.status == Status.RUNNING }
            val queued = items.count { it.status == Status.QUEUED }
            val files = items.count { it.status == Status.DONE && it.fileUri != null }
            return when {
                running > 0 -> "Downloading $running" + if (queued > 0) " · $queued waiting" else ""
                queued > 0 -> "$queued waiting"
                else -> "$files file" + if (files == 1) "" else "s"
            }
        }
    }
}

package com.mudassir.ytdownloader

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saves the download list, synced playlists, settings, search history and "continue
 * watching" positions to one JSON file, and brings them back (e.g. after reinstalling
 * or on a new phone). The video files themselves stay in Downloads/YTDownloader.
 */
object Backup {

    private const val FORMAT = 1

    /** Settings that only make sense on the phone they were made on. */
    private val SKIP_PREFS = setOf("paused", "last_update_check", "last_clipboard_link")

    data class Restored(val playlists: Int, val files: Int, val missing: Int, val queued: Int)

    fun export(ctx: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx).all
        val p = JSONObject()
        prefs.forEach { (key, value) ->
            if (key in SKIP_PREFS || value == null) return@forEach
            val typed = when (value) {
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is String -> JSONObject().put("t", "s").put("v", value)
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                else -> null
            }
            if (typed != null) p.put(key, typed)
        }
        return JSONObject()
            .put("app", "YT Downloader")
            .put("format", FORMAT)
            .put("createdAt", System.currentTimeMillis())
            .put("build", Updater.installedBuild(ctx))
            .put("store", Store.snapshot())
            .put("prefs", p)
            .toString(2)
    }

    /**
     * Merges a backup into what is on the phone: playlists and downloads are added
     * (nothing is deleted), settings are replaced. Finished downloads whose file is
     * no longer on the phone are left out.
     */
    fun restore(ctx: Context, text: String): Restored {
        val root = JSONObject(text)
        require(root.optString("app") == "YT Downloader") { "This isn't a YT Downloader backup file." }

        val store = root.optJSONObject("store") ?: JSONObject()
        val itemsJson = store.optJSONArray("items") ?: JSONArray()
        val listsJson = store.optJSONArray("playlists") ?: JSONArray()
        val items = (0 until itemsJson.length()).mapNotNull {
            runCatching { DownloadItem.fromJson(itemsJson.getJSONObject(it)) }.getOrNull()
        }
        val lists = (0 until listsJson.length()).mapNotNull {
            runCatching { SavedPlaylist.fromJson(listsJson.getJSONObject(it)) }.getOrNull()
        }

        var missing = 0
        val keep = items.mapNotNull { item ->
            when (item.status) {
                Status.DONE -> if (Storage.exists(ctx, item.fileUri)) item else { missing++; null }
                Status.RUNNING -> item.copy(status = Status.QUEUED)
                else -> item
            }
        }
        val added = Store.merge(keep, lists)

        val prefs = root.optJSONObject("prefs")
        if (prefs != null) {
            val e = PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            prefs.keys().forEach { key ->
                if (key in SKIP_PREFS) return@forEach
                val o = prefs.optJSONObject(key) ?: return@forEach
                when (o.optString("t")) {
                    "b" -> e.putBoolean(key, o.optBoolean("v"))
                    "i" -> e.putInt(key, o.optInt("v"))
                    "l" -> e.putLong(key, o.optLong("v"))
                    "f" -> e.putFloat(key, o.optDouble("v").toFloat())
                    "s" -> e.putString(key, o.optString("v"))
                    "ss" -> o.optJSONArray("v")?.let { a -> e.putStringSet(key, (0 until a.length()).map { a.getString(it) }.toSet()) }
                }
            }
            e.apply()
        }

        Sync.schedule(ctx)
        Queue.kick(ctx)
        return Restored(
            playlists = lists.size,
            files = keep.count { it.status == Status.DONE },
            missing = missing,
            queued = added.count { it.isActive }
        )
    }
}

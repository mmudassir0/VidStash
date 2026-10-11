package com.mudassir.ytdownloader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Status { QUEUED, RUNNING, DONE, FAILED, SKIPPED }

/** One video in the download queue / history. Saved to disk so the queue survives restarts. */
data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    /** Highest resolution allowed; 0 = audio only. */
    val maxHeight: Int,
    /** File name prefix, e.g. "007" for playlist position. */
    val prefix: String? = null,
    /** Sub-folder inside Downloads/YTDownloader (playlist or channel name). */
    val folder: String? = null,
    /** Video length in seconds, -1 if unknown. */
    val durationSec: Long = -1,
    /** Channel name. */
    val uploader: String? = null,
    /** Audio only: save as MP3 even if the default format is M4A. */
    val mp3: Boolean = false,
    val status: Status = Status.QUEUED,
    val error: String? = null,
    val attempts: Int = 0,
    /** Where the finished file was saved (content:// or file://). */
    val fileUri: String? = null,
    val fileName: String? = null,
    val mime: String? = null,
    val bytes: Long = -1,
    /** Formats chosen for this download, so a partial file can be resumed with the same streams. */
    val videoItag: Int = -1,
    val audioItag: Int = -1,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0
) {
    val isActive get() = status == Status.QUEUED || status == Status.RUNNING

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("title", title); put("thumbnail", thumbnail)
        put("maxHeight", maxHeight); put("prefix", prefix); put("folder", folder)
        put("durationSec", durationSec); put("uploader", uploader); put("mp3", mp3)
        put("status", status.name); put("error", error); put("attempts", attempts)
        put("fileUri", fileUri); put("fileName", fileName); put("mime", mime); put("bytes", bytes)
        put("videoItag", videoItag); put("audioItag", audioItag)
        put("createdAt", createdAt); put("finishedAt", finishedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = DownloadItem(
            id = o.getString("id"),
            url = o.getString("url"),
            title = o.optString("title", "Video"),
            thumbnail = o.str("thumbnail"),
            maxHeight = o.optInt("maxHeight", 720),
            prefix = o.str("prefix"),
            folder = o.str("folder"),
            durationSec = o.optLong("durationSec", -1),
            uploader = o.str("uploader"),
            mp3 = o.optBoolean("mp3", false),
            status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.QUEUED),
            error = o.str("error"),
            attempts = o.optInt("attempts", 0),
            fileUri = o.str("fileUri"),
            fileName = o.str("fileName"),
            mime = o.str("mime"),
            bytes = o.optLong("bytes", -1),
            videoItag = o.optInt("videoItag", -1),
            audioItag = o.optInt("audioItag", -1),
            createdAt = o.optLong("createdAt", 0),
            finishedAt = o.optLong("finishedAt", 0)
        )
    }
}

/** A playlist or channel that can be synced later to fetch only new videos. */
data class SavedPlaylist(
    val url: String,
    val name: String,
    val isChannel: Boolean,
    val maxHeight: Int,
    /** Video ids already seen (downloaded or deliberately left out). */
    val seen: Set<String>,
    val lastSync: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url); put("name", name); put("isChannel", isChannel)
        put("maxHeight", maxHeight); put("seen", JSONArray(seen.toList())); put("lastSync", lastSync)
    }

    companion object {
        fun fromJson(o: JSONObject): SavedPlaylist {
            val arr = o.optJSONArray("seen") ?: JSONArray()
            return SavedPlaylist(
                url = o.getString("url"),
                name = o.optString("name", "Playlist"),
                isChannel = o.optBoolean("isChannel", false),
                maxHeight = o.optInt("maxHeight", 720),
                seen = (0 until arr.length()).map { arr.getString(it) }.toSet(),
                lastSync = o.optLong("lastSync", 0)
            )
        }
    }
}

/** Live progress of a running download (kept in memory only). */
data class Progress(val done: Long, val total: Long, val speed: Long, val stage: String) {
    val percent get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
    val etaSec get() = if (speed > 0 && total > done) (total - done) / speed else -1
}

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key, "").ifEmpty { null }

/** The queue, download history and saved playlists, persisted as JSON in app storage. */
object Store {
    private lateinit var file: File
    private val lock = Any()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _playlists = MutableStateFlow<List<SavedPlaylist>>(emptyList())
    val playlists: StateFlow<List<SavedPlaylist>> = _playlists.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "store.json")
        synchronized(lock) {
            runCatching {
                if (!file.exists()) return@runCatching
                val root = JSONObject(file.readText())
                val items = root.optJSONArray("items") ?: JSONArray()
                val pls = root.optJSONArray("playlists") ?: JSONArray()
                _items.value = (0 until items.length()).mapNotNull {
                    runCatching { DownloadItem.fromJson(items.getJSONObject(it)) }.getOrNull()
                }
                _playlists.value = (0 until pls.length()).mapNotNull {
                    runCatching { SavedPlaylist.fromJson(pls.getJSONObject(it)) }.getOrNull()
                }
            }
            // Anything "running" when the app was killed goes back to the queue.
            _items.value = _items.value.map { if (it.status == Status.RUNNING) it.copy(status = Status.QUEUED) else it }
        }
    }

    fun get(id: String) = _items.value.firstOrNull { it.id == id }

    fun add(newItems: List<DownloadItem>) = mutate { it + newItems }

    fun update(id: String, change: (DownloadItem) -> DownloadItem) =
        mutate { list -> list.map { if (it.id == id) change(it) else it } }

    fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    /** Removes finished, failed and skipped entries from the list (files are kept). */
    fun clearFinished() = mutate { list -> list.filter { it.isActive } }

    /** Takes the next queued item and marks it running, atomically. */
    fun claimNext(): DownloadItem? = synchronized(lock) {
        val next = _items.value.firstOrNull { it.status == Status.QUEUED } ?: return null
        val claimed = next.copy(status = Status.RUNNING)
        _items.value = _items.value.map { if (it.id == next.id) claimed else it }
        save()
        claimed
    }

    fun hasQueued() = _items.value.any { it.status == Status.QUEUED }

    fun setProgress(id: String, p: Progress?) {
        val m = _progress.value.toMutableMap()
        if (p == null) m.remove(id) else m[id] = p
        _progress.value = m
    }

    fun savePlaylist(p: SavedPlaylist) = synchronized(lock) {
        _playlists.value = _playlists.value.filterNot { it.url == p.url } + p
        save()
    }

    fun removePlaylist(url: String) = synchronized(lock) {
        _playlists.value = _playlists.value.filterNot { it.url == url }
        save()
    }

    private fun mutate(change: (List<DownloadItem>) -> List<DownloadItem>) = synchronized(lock) {
        _items.value = change(_items.value)
        save()
    }

    private fun save() {
        val root = JSONObject()
            .put("items", JSONArray(_items.value.map { it.toJson() }))
            .put("playlists", JSONArray(_playlists.value.map { it.toJson() }))
        val tmp = File(file.parentFile, "store.json.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }
}

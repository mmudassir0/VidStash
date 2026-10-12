package com.mudassir.vidstash

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Adds video and audio files that are already on the phone (for example the folder the old
 * YT Downloader app saved to) to the library, so they can be played, searched and managed.
 * Files stay where they are. Sub-folders become library folders (playlists).
 */
object FolderImport {

    data class Result(val added: Int, val alreadyThere: Int)

    private val MEDIA_EXT = setOf("mp4", "webm", "mkv", "m4a", "mp3", "opus", "ogg", "aac", "3gp", "mov")

    fun import(ctx: Context, tree: Uri, onProgress: (Int) -> Unit = {}): Result {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IllegalStateException("Can't open that folder")
        val known = Store.items.value.mapNotNull { it.fileUri }.toSet()
        val found = mutableListOf<Pair<DocumentFile, String?>>()
        root.listFiles().forEach { f ->
            when {
                f.isDirectory -> f.listFiles().filter { it.isFile && isMedia(it) }.forEach { found += it to f.name }
                f.isFile && isMedia(f) -> found += f to null
            }
        }
        var already = 0
        val items = mutableListOf<DownloadItem>()
        found.forEachIndexed { i, (f, folder) ->
            onProgress(i + 1)
            if (f.uri.toString() in known) { already++; return@forEachIndexed }
            val name = f.name ?: "Video"
            val (title, duration) = readMeta(ctx, f.uri)
            items += DownloadItem(
                url = "",
                title = title ?: cleanTitle(name),
                maxHeight = 0,
                folder = folder,
                status = Status.DONE,
                fileUri = f.uri.toString(),
                fileName = name,
                mime = f.type ?: guessMime(name),
                bytes = f.length(),
                durationSec = duration,
                createdAt = f.lastModified(),
                finishedAt = f.lastModified()
            )
        }
        if (items.isNotEmpty()) Store.add(items)
        return Result(items.size, already)
    }

    private fun isMedia(f: DocumentFile): Boolean {
        val type = f.type.orEmpty()
        if (type.startsWith("video/") || type.startsWith("audio/")) return true
        return f.name?.substringAfterLast('.', "")?.lowercase() in MEDIA_EXT
    }

    /** "007 - Song name.mp4" → "Song name". */
    private fun cleanTitle(fileName: String): String =
        fileName.substringBeforeLast('.').replace(Regex("^\\d{1,4} - "), "").trim().ifEmpty { fileName }

    private fun guessMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }

    /** Title tag and length in seconds, if the file has them. */
    private fun readMeta(ctx: Context, uri: Uri): Pair<String?, Long> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1
            title to (if (ms > 0) ms / 1000 else -1)
        } catch (e: Exception) {
            null to -1L
        } finally {
            runCatching { r.release() }
        }
    }
}

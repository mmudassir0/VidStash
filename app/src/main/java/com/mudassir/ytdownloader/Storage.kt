package com.mudassir.ytdownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

object Storage {

    // ---------- folders picked by the user (any folder, including an SD card) ----------

    /** The picked folder for this kind of file, if any and still allowed. */
    fun pickedFolder(ctx: Context, mime: String): Uri? {
        val s = Settings(ctx)
        val value = if (mime.startsWith("audio")) s.audioFolder else s.videoFolder
        if (value.isEmpty()) return null
        val uri = Uri.parse(value)
        val allowed = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return uri.takeIf { allowed }
    }

    /** "SD card/Music/YT" style name for a picked folder. */
    fun describeFolder(value: String): String {
        if (value.isEmpty()) return "Downloads/$FOLDER (default)"
        return runCatching {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(value))
            val (volume, path) = id.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            (if (volume == "primary") "Phone" else "SD card") + (if (path.isNotEmpty()) "/$path" else "")
        }.getOrDefault("Chosen folder")
    }

    private fun treeDir(ctx: Context, tree: Uri, subFolder: String?, create: Boolean): DocumentFile? {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return null
        if (subFolder == null) return root
        val name = sanitize(subFolder)
        return root.findFile(name)?.takeIf { it.isDirectory } ?: if (create) root.createDirectory(name) else null
    }

    private fun saveToTree(ctx: Context, tree: Uri, src: File, fileName: String, mime: String, subFolder: String?): Uri {
        val dir = treeDir(ctx, tree, subFolder, create = true) ?: throw IllegalStateException("Can't open the chosen folder")
        dir.findFile(fileName)?.delete()
        // octet-stream keeps our file name exactly (some folders add their own extension otherwise).
        val doc = dir.createFile(if (mime.startsWith("video") || mime.startsWith("audio")) mime else "application/octet-stream", fileName)
            ?: throw IllegalStateException("Can't create a file in the chosen folder")
        try {
            ctx.contentResolver.openOutputStream(doc.uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            runCatching { doc.delete() }
            throw e
        }
        return doc.uri
    }

    /** Deletes a saved file of any kind (MediaStore, picked folder, or plain file). */
    fun delete(ctx: Context, uri: Uri): Boolean = when {
        uri.scheme == "file" -> File(uri.path!!).let { !it.exists() || it.delete() }
        DocumentsContract.isDocumentUri(ctx, uri) ->
            runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }.getOrDefault(false) || !exists(ctx, uri.toString())
        else -> runCatching { ctx.contentResolver.delete(uri, null, null) >= 0 }.getOrDefault(false)
    }

    /** True if the picked folder for [mime] is on another volume, so the phone's free space doesn't apply. */
    fun usesPickedFolder(ctx: Context, mime: String) = pickedFolder(ctx, mime) != null


    const val FOLDER = "YTDownloader"

    fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        -1L
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(120).ifEmpty { "video" }

    /** "Download/YTDownloader[/subFolder]" */
    fun relativePath(subFolder: String?): String =
        listOfNotNull(Environment.DIRECTORY_DOWNLOADS, FOLDER, subFolder?.let(::sanitize)).joinToString("/")

    /**
     * Uri of an existing file with this name in the target folder, or null.
     * On Android 10+ this only sees files this install of the app saved.
     */
    fun find(ctx: Context, fileName: String, subFolder: String?, mime: String = "video/*"): Uri? {
        pickedFolder(ctx, mime)?.let { tree ->
            return runCatching { treeDir(ctx, tree, subFolder, create = false)?.findFile(fileName)?.uri }.getOrNull()
        }
        val rel = relativePath(subFolder)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf("$rel/", rel, fileName),
                null
            )?.use { c ->
                if (c.moveToFirst()) Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                else null
            }
        } else {
            @Suppress("DEPRECATION")
            File(File(Environment.getExternalStorageDirectory(), rel), fileName).takeIf { it.exists() }?.let(Uri::fromFile)
        }
    }

    /** True if the saved file behind [uri] still exists (the user may have deleted it). */
    fun exists(ctx: Context, uri: String?): Boolean {
        if (uri == null) return false
        val u = Uri.parse(uri)
        return if (u.scheme == "file") File(u.path ?: return false).exists()
        else runCatching { ctx.contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false }.getOrDefault(false)
    }

    /** Copies a finished file into Downloads/YTDownloader[/subFolder]. Returns the saved file's Uri. */
    fun saveToDownloads(ctx: Context, src: File, fileName: String, mime: String, subFolder: String?): Uri {
        // Subtitles go next to the videos.
        val kind = if (mime == "application/x-subrip") "video/*" else mime
        pickedFolder(ctx, kind)?.let { return saveToTree(ctx, it, src, fileName, mime, subFolder) }
        val rel = relativePath(subFolder)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file in Downloads")
            try {
                resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStorageDirectory(), rel).apply { mkdirs() }
            val dest = File(dir, fileName)
            src.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf(mime), null)
            Uri.fromFile(dest)
        }
    }
}

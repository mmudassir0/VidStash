package com.mudassir.ytdownloader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.mudassir.ytdownloader.DownloadItem
import com.mudassir.ytdownloader.PlayerActivity
import com.mudassir.ytdownloader.Storage
import com.mudassir.ytdownloader.Store
import java.io.File

/** Play, open, share and delete downloaded files. */
object Files {

    fun play(ctx: Context, item: DownloadItem) {
        if (!checkExists(ctx, item)) return
        ctx.startActivity(PlayerActivity.intent(ctx, item))
    }

    /** Plays [items] in the built-in player, starting at [index]; the rest follow. */
    fun playList(ctx: Context, items: List<DownloadItem>, index: Int) {
        val item = items.getOrNull(index) ?: return
        if (!checkExists(ctx, item)) return
        ctx.startActivity(PlayerActivity.intent(ctx, items, index))
    }

    fun openWith(ctx: Context, item: DownloadItem) {
        if (!checkExists(ctx, item)) return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(shareable(ctx, item), item.mime ?: "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(ctx: Context, item: DownloadItem) {
        if (!checkExists(ctx, item)) return
        val intent = Intent(Intent.ACTION_SEND)
            .setType(item.mime ?: "video/*")
            .putExtra(Intent.EXTRA_STREAM, shareable(ctx, item))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "Share"))
    }

    /** Deletes the file from the phone and removes it from the list. */
    fun delete(ctx: Context, item: DownloadItem) {
        val uri = item.fileUri?.let(Uri::parse)
        val ok = when {
            uri == null -> true
            uri.scheme == "file" -> File(uri.path!!).let { !it.exists() || it.delete() }
            else -> runCatching { ctx.contentResolver.delete(uri, null, null) >= 0 }.getOrDefault(false)
        }
        if (ok) Store.remove(item.id)
        Toast.makeText(ctx, if (ok) "Deleted" else "Couldn't delete the file", Toast.LENGTH_SHORT).show()
    }

    /** Uri other apps can read: content:// as-is, file:// (Android 9 and older) via FileProvider. */
    private fun shareable(ctx: Context, item: DownloadItem): Uri {
        val uri = Uri.parse(item.fileUri)
        return if (uri.scheme == "file") FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(uri.path!!))
        else uri
    }

    private fun checkExists(ctx: Context, item: DownloadItem): Boolean {
        if (Storage.exists(ctx, item.fileUri)) return true
        Toast.makeText(ctx, "This file was moved or deleted", Toast.LENGTH_SHORT).show()
        return false
    }
}

package com.mudassir.ytdownloader

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Streams YouTube media for the player in pieces using the "range" URL parameter, like the
 * downloader does (plain requests to YouTube's servers get throttled or refused).
 * Any other http(s) link is passed to the normal HTTP source.
 */
@OptIn(UnstableApi::class)
class YtDataSource : BaseDataSource(/* isNetwork = */ true) {

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = YtDataSource()
    }

    private var uri: Uri? = null
    private var base = ""
    private var position = 0L
    private var remaining = 0L
    private var total = -1L
    private var response: Response? = null
    private var body: InputStream? = null
    private var chunkLeft = 0L
    private var opened = false
    private var other: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        total = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        if (!url.contains("googlevideo.com") || total <= 0) {
            // Not a YouTube media link: plain HTTP.
            val d = DefaultHttpDataSource.Factory().setUserAgent(OkHttpDownloader.USER_AGENT)
                .setAllowCrossProtocolRedirects(true).createDataSource()
            other = d
            uri = dataSpec.uri
            return d.open(dataSpec)
        }
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        base = url
        position = dataSpec.position
        if (position >= total) throw IOException("Position $position past end $total")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else total - position
        openChunk()
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    private fun openChunk() {
        closeChunk()
        val end = minOf(position + CHUNK - 1, position + remaining - 1, total - 1)
        val req = Request.Builder().url("$base&range=$position-$end")
            .header("User-Agent", OkHttpDownloader.USER_AGENT).build()
        val res = client.newCall(req).execute()
        if (!res.isSuccessful) {
            res.close()
            throw IOException("HTTP ${res.code} while streaming")
        }
        response = res
        body = res.body!!.byteStream()
        chunkLeft = end - position + 1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        other?.let { return it.read(buffer, offset, length) }
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        if (chunkLeft <= 0) openChunk()
        val want = minOf(length.toLong(), chunkLeft, remaining).toInt()
        var n = body!!.read(buffer, offset, want)
        if (n < 0) {
            // The server ended this piece early: ask again from where we are.
            openChunk()
            n = body!!.read(buffer, offset, want)
            if (n < 0) return C.RESULT_END_OF_INPUT
        }
        position += n
        remaining -= n
        chunkLeft -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    private fun closeChunk() {
        runCatching { body?.close() }
        runCatching { response?.close() }
        body = null
        response = null
        chunkLeft = 0
    }

    override fun close() {
        other?.let {
            other = null
            uri = null
            it.close()
            return
        }
        closeChunk()
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    companion object {
        private const val CHUNK = 4L * 1024 * 1024
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

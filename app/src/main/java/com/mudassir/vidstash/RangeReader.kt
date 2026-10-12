package com.mudassir.vidstash

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Reads [length] bytes of a YouTube media file from [start], fetching it in pieces with the
 * "range" URL parameter (how the official apps do it; plain requests get throttled or refused).
 * [total] is the file size (the "clen" parameter).
 */
class RangeReader(private val url: String, start: Long, length: Long, private val total: Long) {

    private var position = start
    var remaining = length
        private set
    private var response: Response? = null
    private var body: InputStream? = null
    private var chunkLeft = 0L

    init {
        if (start >= total) throw IOException("Position $start past end $total")
    }

    private fun openChunk() {
        closeChunk()
        val end = minOf(position + chunkSize - 1, position + remaining - 1, total - 1)
        val req = Request.Builder().url("$url&range=$position-$end")
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

    /** Like InputStream.read; -1 at the end. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return -1
        if (chunkLeft <= 0 || body == null) openChunk()
        val want = minOf(length.toLong(), chunkLeft, remaining).toInt()
        var n = body!!.read(buffer, offset, want)
        if (n < 0) {
            // The server ended this piece early: ask again from where we are.
            openChunk()
            n = body!!.read(buffer, offset, want)
            if (n < 0) return -1
        }
        position += n
        remaining -= n
        chunkLeft -= n
        return n
    }

    private fun closeChunk() {
        runCatching { body?.close() }
        runCatching { response?.close() }
        body = null
        response = null
        chunkLeft = 0
    }

    fun close() = closeChunk()

    companion object {
        internal var chunkSize = 4L * 1024 * 1024

        /** Hosts read in pieces (YouTube's media servers; tests add their own). */
        internal var extraHosts = emptyList<String>()

        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** File size if [url] is a YouTube media link that can be read in pieces, else -1. */
        fun totalFor(url: String): Long {
            if (!url.contains("googlevideo.com") && extraHosts.none { url.contains(it) }) return -1
            return Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1
        }
    }
}

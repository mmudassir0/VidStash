package com.mudassir.vidstash

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** The server ignored a Range request; the caller restarts the file from zero. */
class RangeIgnored : IOException("Server does not support resuming")

/** HTTP error that retrying the same URL won't fix (expired or forbidden link). */
class HttpClientError(val code: Int) : IOException("HTTP $code")

/**
 * Downloads YouTube media in 10 MB pieces (the "range" URL parameter), the same way
 * the official apps do. Plain full-file requests get throttled or rejected for HD tracks.
 *
 * Each piece is written at its exact offset, and a failed attempt is rolled back before
 * retrying, so a dropped connection can never leave duplicated bytes in the file.
 * With resume = true, an existing partial file is continued instead of restarted.
 */
object ChunkDownloader {

    private const val CHUNK = 10L * 1024 * 1024
    private const val ATTEMPTS = 3
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** @param onBytes called with bytes written so far for this file (including resumed bytes). */
    suspend fun download(url: String, dest: File, resume: Boolean = false, onBytes: (Long) -> Unit) {
        val chunked = url.contains("googlevideo.com")
        val ctx = currentCoroutineContext()
        val total = if (chunked) Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L else -1L
        var written = if (resume && dest.exists()) dest.length() else 0L
        if (total in 1..written) { onBytes(written); return } // already complete
        try {
            written = fetchAll(url, dest, chunked, total, written, ctx, onBytes)
        } catch (e: RangeIgnored) {
            dest.delete()
            return download(url, dest, resume = false, onBytes = onBytes)
        }
        if (written == 0L) throw IOException("Empty download")
    }

    private suspend fun fetchAll(
        url: String, dest: File, chunked: Boolean, total: Long, start: Long,
        ctx: kotlin.coroutines.CoroutineContext, onBytes: (Long) -> Unit
    ): Long {
        var written = start
        RandomAccessFile(dest, "rw").use { out ->
            out.setLength(written)
            val buf = ByteArray(64 * 1024)
            onBytes(written)
            while (true) {
                ctx.ensureActive()
                val reqUrl = if (chunked) "$url&range=$written-${written + CHUNK - 1}" else url
                // Plain (non-YouTube CDN) URLs resume with a standard Range header.
                val rangeHeader = if (!chunked && written > 0) "bytes=$written-" else null
                val got = fetchWithRetry(
                    url = reqUrl,
                    rangeHeader = rangeHeader,
                    // Start every attempt from a clean end-of-file at this chunk's offset.
                    beforeAttempt = { out.setLength(written); out.seek(written) },
                    read = { input ->
                        var n = 0L
                        while (true) {
                            ctx.ensureActive() // lets Skip / Stop take effect mid-chunk
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            n += r
                            onBytes(written + n)
                        }
                        n
                    }
                )
                written += got
                if (!chunked || got < CHUNK || (total > 0 && written >= total)) break
            }
        }
        return written
    }

    private suspend fun fetchWithRetry(
        url: String,
        rangeHeader: String?,
        beforeAttempt: () -> Unit,
        read: (InputStream) -> Long
    ): Long {
        var last: IOException? = null
        for (attempt in 0 until ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            try {
                beforeAttempt()
                val req = Request.Builder().url(url).header("User-Agent", OkHttpDownloader.USER_AGENT)
                    .apply { if (rangeHeader != null) header("Range", rangeHeader) }
                    .build()
                return client.newCall(req).execute().use { res ->
                    if (res.code == 416) return 0L // range starts at the end: nothing left to fetch
                    if (res.code in 400..499) throw HttpClientError(res.code)
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                    // Asked to resume but the server sent the whole file: start over.
                    if (rangeHeader != null && res.code == 200) throw RangeIgnored()
                    read(res.body!!.byteStream())
                }
            } catch (e: HttpClientError) {
                throw e
            } catch (e: RangeIgnored) {
                throw e // expired/forbidden link: the worker re-extracts a fresh one on retry
            } catch (e: IOException) {
                last = e
                if (attempt < ATTEMPTS - 1) delay(1500L * (attempt + 1))
            }
        }
        throw last ?: IOException("Download failed")
    }
}

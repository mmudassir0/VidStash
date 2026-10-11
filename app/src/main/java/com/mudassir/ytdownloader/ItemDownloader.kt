package com.mudassir.ytdownloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Downloads one queue item end to end: look up formats, download (resuming partial files),
 * merge / cut sponsor segments / tag, save to Downloads, and save subtitles.
 *
 * Partial files live in files/partial/<item id>/ so they survive app restarts.
 */
class ItemDownloader(private val ctx: Context) {

    private val settings = Settings(ctx)
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    class Fatal(msg: String) : Exception(msg)

    suspend fun process(start: DownloadItem) {
        var item = start
        val details = YouTube.getVideo(item.url, settings.allowWebm)
        val opt = YouTube.pickBest(details.options, item.maxHeight, item.videoItag, item.audioItag)
            ?: throw Fatal("No downloadable format")

        val partial = partialDir(item.id)
        // A different format than last time means old partial files can't be continued.
        if (opt.videoItag != item.videoItag || opt.audioItag != item.audioItag) {
            partial.deleteRecursively()
            item = updated(item.id) { it.copy(videoItag = opt.videoItag, audioItag = opt.audioItag) }
        }
        partial.mkdirs()
        item = updated(item.id) { it.copy(title = details.title, thumbnail = it.thumbnail ?: details.thumbnail) }

        val clipLabel = if (item.isClip) " (${clipTime(item.clipStartSec)} to ${clipTime(item.clipEndSec)})" else ""
        val baseName = Storage.sanitize(
            (if (item.prefix != null) "${item.prefix} - ${details.title}" else details.title).take(100) + clipLabel
        )
        val wantMp3 = opt.kind == Kind.AUDIO && (item.mp3 || settings.audioFormat == "mp3")
        var fileName = "$baseName.${if (wantMp3) "mp3" else opt.extension}"
        var mime = if (wantMp3) "audio/mpeg" else opt.mime

        // Skip files that are already in the folder (e.g. re-downloading a playlist).
        Storage.find(ctx, fileName, item.folder)?.let { existing ->
            finish(item, existing.toString(), fileName, mime, -1, note = "Already downloaded")
            partial.deleteRecursively()
            return
        }

        val total = opt.bytes
        val free = Storage.freeBytes()
        if (total > 0 && free in 0 until total * 2 + 50_000_000) {
            throw Fatal("Not enough space: needs ${formatBytes(total * 2)}, free ${formatBytes(free)}")
        }

        val meter = SpeedMeter(item.id, total)
        val label = opt.label
        val webm = opt.extension == "webm"
        val wantCuts = settings.sponsorBlock

        // ---- download ----
        val v = File(partial, "v.part")
        val a = File(partial, "a.part")
        when (opt.kind) {
            Kind.MERGE -> {
                ChunkDownloader.download(opt.videoUrl!!, v, resume = true) { meter.update(it, label) }
                val vLen = v.length()
                ChunkDownloader.download(opt.audioUrl!!, a, resume = true) { meter.update(vLen + it, label) }
            }
            Kind.SINGLE_VIDEO -> ChunkDownloader.download(opt.videoUrl!!, v, resume = true) { meter.update(it, label) }
            Kind.AUDIO -> ChunkDownloader.download(opt.audioUrl!!, a, resume = true) { meter.update(it, label) }
        }

        // ---- post-process ----
        // A section download keeps only [start, end): everything before and after is cut away.
        val clipCuts = if (item.isClip) listOf(
            Cut(0, item.clipStartSec * 1_000_000),
            Cut(item.clipEndSec * 1_000_000, Long.MAX_VALUE)
        ) else emptyList()
        val sponsorCuts = if (wantCuts) runCatching { SponsorBlock.segments(details.id) }.getOrDefault(emptyList()) else emptyList()
        val cuts = sponsorCuts + clipCuts
        var out = File(partial, "out.${opt.extension}")
        var mp3Note: String? = null
        out.delete()
        when (opt.kind) {
            Kind.MERGE -> {
                meter.stage(
                    when {
                        item.isClip -> "Merging and cutting your section…"
                        cuts.isEmpty() -> "Merging video + audio…"
                        else -> "Merging, removing ${cuts.size} sponsor segment(s)…"
                    }
                )
                Remux.run(listOf(Remux.Source(v, "video/"), Remux.Source(a, "audio/")), out, webm, cuts)
            }
            Kind.SINGLE_VIDEO -> {
                if (item.isClip || (cuts.isNotEmpty() && opt.extension == "mp4")) {
                    meter.stage(if (item.isClip) "Cutting your section…" else "Removing ${cuts.size} sponsor segment(s)…")
                    Remux.run(listOf(Remux.Source(v, "video/"), Remux.Source(v, "audio/")), out, webm, cuts)
                } else v.renameTo(out)
            }
            Kind.AUDIO -> {
                // YouTube serves fragmented MP4; a plain MP4 seeks better and shows tags everywhere.
                meter.stage(
                    when {
                        item.isClip -> "Cutting your section…"
                        cuts.isEmpty() -> "Preparing audio…"
                        else -> "Removing ${cuts.size} sponsor segment(s)…"
                    }
                )
                val remuxed = File(partial, "remux.m4a")
                val audio = try {
                    Remux.run(listOf(Remux.Source(a, "audio/")), remuxed, false, cuts)
                    remuxed
                } catch (e: Exception) {
                    if (item.isClip) throw e // without remuxing the section can't be cut
                    a // keep the original if the phone can't remux it
                }
                val art = cover(details.id)
                val mp3Out = File(partial, "out.mp3")
                val converted = wantMp3 && try {
                    val ctxJob = currentCoroutineContext()
                    Mp3Converter.convert(
                        audio, mp3Out,
                        Mp3Converter.Tags(details.title, details.uploader, item.folder ?: details.uploader, art),
                        onProgress = { meter.stage("Converting to MP3… $it%") },
                        checkCancelled = { ctxJob.ensureActive() }
                    )
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Keep the audio as M4A rather than failing the download.
                    mp3Note = "MP3 conversion failed, saved as M4A"
                    false
                }
                if (converted) {
                    out = mp3Out
                } else {
                    if (wantMp3) {
                        fileName = "$baseName.${opt.extension}"
                        mime = opt.mime
                    }
                    meter.stage("Adding title and cover art…")
                    try {
                        Mp4Tagger.tag(audio, out, details.title, details.uploader, item.folder ?: details.uploader, art)
                    } catch (e: Exception) {
                        audio.copyTo(out, overwrite = true) // tags are a nice-to-have; keep the audio
                    }
                }
            }
        }

        // ---- save ----
        meter.stage("Saving…")
        val uri = Storage.saveToDownloads(ctx, out, fileName, mime, item.folder)

        // Subtitles would be out of sync with a cut-out section, so only for whole videos.
        if (opt.kind != Kind.AUDIO && !item.isClip) saveSubtitles(details, baseName, item.folder)

        val note = listOfNotNull(
            if (item.isClip) "Section ${formatDuration(item.clipStartSec)}–${formatDuration(item.clipEndSec)}" else null,
            if (sponsorCuts.isNotEmpty()) "Removed ${sponsorCuts.size} sponsor segment(s)" else null,
            mp3Note
        ).joinToString(" · ").ifEmpty { null }
        finish(item, uri.toString(), fileName, mime, out.length(), note)
        partial.deleteRecursively()
    }

    private fun saveSubtitles(details: VideoDetails, baseName: String, folder: String?) {
        val track = YouTube.pickSubtitle(details.subtitles, settings.subtitleLang) ?: return
        runCatching {
            val vtt = http.newCall(
                Request.Builder().url(track.url).header("User-Agent", OkHttpDownloader.USER_AGENT).build()
            ).execute().use { if (it.isSuccessful) it.body!!.string() else throw IOException("HTTP ${it.code}") }
            val srt = File(partialDir("subs-${details.id}").apply { mkdirs() }, "s.srt")
            srt.writeText(Subtitles.vttToSrt(vtt))
            Storage.saveToDownloads(ctx, srt, "$baseName.srt", "application/x-subrip", folder)
            srt.parentFile?.deleteRecursively()
        }
    }

    /** Square JPEG cover art from the video thumbnail (center crop of the 4:3 image). */
    private fun cover(videoId: String): ByteArray? = runCatching {
        val bytes = http.newCall(Request.Builder().url("https://i.ytimg.com/vi/$videoId/hqdefault.jpg").build())
            .execute().use { if (it.isSuccessful) it.body!!.bytes() else null } ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        // hqdefault has black bars top and bottom; the middle 75% is the real picture.
        val h = (bmp.height * 0.75).toInt()
        val side = minOf(bmp.width, h)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }.getOrNull()

    private fun finish(item: DownloadItem, uri: String, fileName: String, mime: String, bytes: Long, note: String?) {
        Store.update(item.id) {
            it.copy(
                status = Status.DONE, error = note, fileUri = uri, fileName = fileName,
                mime = mime, bytes = bytes, finishedAt = System.currentTimeMillis()
            )
        }
        Store.setProgress(item.id, null)
    }

    private fun updated(id: String, change: (DownloadItem) -> DownloadItem): DownloadItem {
        Store.update(id, change)
        return Store.get(id) ?: throw Fatal("Removed from queue")
    }

    /** "1m20s"-style time for file names (":" isn't allowed in them). */
    private fun clipTime(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%dh%02dm%02ds".format(h, m, s) else "%dm%02ds".format(m, s)
    }

    private fun partialDir(name: String) = File(ctx.filesDir, "partial/$name")

    companion object {
        fun deletePartial(ctx: Context, id: String) = File(ctx.filesDir, "partial/$id").deleteRecursively()
    }

    /** Publishes progress with a smoothed speed, at most twice a second. */
    private class SpeedMeter(private val id: String, private val total: Long) {
        private var lastTime = 0L
        private var lastBytes = -1L
        private var speed = 0.0
        private var done = 0L

        fun update(bytes: Long, label: String) {
            done = bytes
            val now = System.currentTimeMillis()
            if (lastBytes < 0) { lastBytes = bytes; lastTime = now }
            val dt = now - lastTime
            if (dt < 500) return
            val instant = (bytes - lastBytes) * 1000.0 / dt
            speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
            lastTime = now
            lastBytes = bytes
            Store.setProgress(id, Progress(bytes, total, speed.toLong(), label))
        }

        fun stage(text: String) = Store.setProgress(id, Progress(done, total, 0, text))
    }
}

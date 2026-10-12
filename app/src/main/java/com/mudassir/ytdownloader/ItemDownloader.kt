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

        // Save every chapter as its own file (whole videos with 2+ chapters only).
        val split = item.splitChapters && !item.isClip && details.chapters.size >= 2

        // Skip files that are already in the folder (e.g. re-downloading a playlist).
        if (!split) Storage.find(ctx, fileName, item.folder, mime)?.let { existing ->
            finish(item, existing.toString(), fileName, mime, -1, note = "Already downloaded")
            partial.deleteRecursively()
            return
        }

        val total = opt.bytes
        val free = Storage.freeBytes()
        // (Space on a picked SD card folder can't be measured here; the phone's own space is
        // still needed for the temporary files, so only the temporary part is checked then.)
        val need = if (Storage.usesPickedFolder(ctx, mime)) total + 50_000_000 else total * 2 + 50_000_000
        if (total > 0 && free in 0 until need) {
            throw Fatal("Not enough space: needs ${formatBytes(total * 2)}, free ${formatBytes(free)}")
        }

        val meter = SpeedMeter(item.id, total)
        val label = opt.label
        val webm = opt.extension == "webm"
        // Sponsor cuts would shift chapter times, so they're skipped when splitting.
        val wantCuts = settings.sponsorBlock && !split

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
                if (split) {
                    splitIntoChapters(item, details, audio, isAudio = true, webm = false, wantMp3 = wantMp3, meter = meter, partial = partial)
                    partial.deleteRecursively()
                    return
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

        if (split) {
            splitIntoChapters(item, details, out, isAudio = false, webm = webm, wantMp3 = false, meter = meter, partial = partial)
            partial.deleteRecursively()
            return
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
        // Chapters for the player, moved to match what was cut out.
        val chapters = adjustChapters(details.chapters, cuts, details.durationSec)
        if (chapters.size >= 2) Store.update(item.id) { it.copy(chapters = chapters) }
        partial.deleteRecursively()
    }

    /** Saves each chapter of [source] as its own file in a folder named after the video. */
    private suspend fun splitIntoChapters(
        item: DownloadItem, details: VideoDetails, source: File, isAudio: Boolean, webm: Boolean,
        wantMp3: Boolean, meter: SpeedMeter, partial: File
    ) {
        val chs = details.chapters
        val dur = details.durationSec
        val folder = item.folder ?: Storage.sanitize(details.title).take(80)
        val art = if (isAudio) cover(details.id) else null
        val job = currentCoroutineContext()
        var count = 0
        for ((i, ch) in chs.withIndex()) {
            job.ensureActive()
            val end = chs.getOrNull(i + 1)?.startSec ?: dur
            if (end <= ch.startSec) continue
            meter.stage("Splitting into chapters… ${i + 1} of ${chs.size}")
            val cuts = listOf(Cut(0, ch.startSec * 1_000_000), Cut(end * 1_000_000, Long.MAX_VALUE))
            val name = Storage.sanitize("%02d - %s".format(i + 1, ch.title)).take(110)
            val ext: String
            val mime: String
            val file: File
            if (!isAudio) {
                ext = if (webm) "webm" else "mp4"
                mime = if (webm) "video/webm" else "video/mp4"
                file = File(partial, "ch$i.$ext").apply { delete() }
                Remux.run(listOf(Remux.Source(source, "video/"), Remux.Source(source, "audio/")), file, webm, cuts)
            } else {
                val m4a = File(partial, "ch$i.m4a").apply { delete() }
                Remux.run(listOf(Remux.Source(source, "audio/")), m4a, false, cuts)
                val mp3 = File(partial, "ch$i.mp3")
                val asMp3 = wantMp3 && runCatching {
                    Mp3Converter.convert(
                        m4a, mp3, Mp3Converter.Tags(ch.title, details.uploader, details.title, art),
                        onProgress = { meter.stage("Splitting into chapters… ${i + 1} of ${chs.size} (MP3 $it%)") },
                        checkCancelled = { job.ensureActive() }
                    )
                }.onFailure { if (it is CancellationException) throw it }.isSuccess
                if (asMp3) {
                    ext = "mp3"; mime = "audio/mpeg"; file = mp3
                } else {
                    ext = "m4a"; mime = "audio/mp4"
                    file = File(partial, "ch$i.tagged.m4a").apply { delete() }
                    runCatching { Mp4Tagger.tag(m4a, file, ch.title, details.uploader, details.title, art) }
                        .onFailure { m4a.copyTo(file, overwrite = true) }
                }
            }
            val uri = Storage.saveToDownloads(ctx, file, "$name.$ext", mime, folder)
            Store.add(
                listOf(
                    DownloadItem(
                        url = item.url, title = ch.title, thumbnail = item.thumbnail ?: details.thumbnail,
                        maxHeight = item.maxHeight, folder = folder, status = Status.DONE,
                        fileUri = uri.toString(), fileName = "$name.$ext", mime = mime, bytes = file.length(),
                        durationSec = end - ch.startSec, uploader = details.uploader.ifBlank { null },
                        prefix = "%02d".format(i + 1), finishedAt = System.currentTimeMillis()
                    )
                )
            )
            file.delete()
            count++
        }
        Store.update(item.id) {
            it.copy(
                status = Status.DONE, error = "Split into $count chapter files in “$folder”", fileUri = null,
                finishedAt = System.currentTimeMillis()
            )
        }
        Store.setProgress(item.id, null)
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
        /**
         * Chapter start times in the saved file: chapters inside a removed part start where it
         * ends, and every removed second before a chapter moves it earlier.
         */
        internal fun adjustChapters(chapters: List<Chapter>, cuts: List<Cut>, durationSec: Long): List<Chapter> {
            if (chapters.isEmpty()) return emptyList()
            val dur = if (durationSec > 0) durationSec.toDouble() else Double.MAX_VALUE
            val ranges = cuts.map { it.startUs / 1e6 to minOf(it.endUs / 1e6, dur) }.filter { it.second > it.first }
            val out = sortedMapOf<Long, Chapter>()
            chapters.forEachIndexed { i, c ->
                val end = chapters.getOrNull(i + 1)?.startSec?.toDouble() ?: dur
                var start = c.startSec.toDouble()
                ranges.forEach { (a, b) -> if (start >= a && start < b) start = b }
                if (start >= end) return@forEachIndexed
                val removed = ranges.filter { it.second <= start }.sumOf { it.second - it.first }
                val t = (start - removed).toLong().coerceAtLeast(0)
                out[t] = Chapter(c.title, t)
            }
            return out.values.toList()
        }

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

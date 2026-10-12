package com.mudassir.vidstash

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** A time range to remove from the output, in microseconds. */
data class Cut(val startUs: Long, val endUs: Long)

/**
 * Copies audio/video tracks from one or more files into a single MP4 or WebM, without
 * re-encoding. Used to merge HD video with audio, and to cut out SponsorBlock segments.
 *
 * Cuts are snapped so the video always resumes on a keyframe (no broken frames), and the
 * same snapped ranges are applied to audio so sound stays in sync.
 */
object Remux {

    class Source(val file: File, val mimePrefix: String)

    private class Track(val ex: MediaExtractor, val outIndex: Int, val isVideo: Boolean) {
        var done = false
    }

    fun run(sources: List<Source>, output: File, webm: Boolean, cuts: List<Cut> = emptyList()) {
        val extractors = mutableListOf<MediaExtractor>()
        val muxer = MediaMuxer(
            output.path,
            if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        try {
            var maxInput = 2 * 1024 * 1024
            val tracks = sources.map { src ->
                val ex = MediaExtractor().apply { setDataSource(src.file.path) }
                extractors += ex
                val index = selectTrack(ex, src.mimePrefix)
                val format = ex.getTrackFormat(index)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInput = maxOf(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                Track(ex, muxer.addTrack(format), src.mimePrefix == "video/")
            }

            val ranges = resolveCuts(cuts, tracks.firstOrNull { it.isVideo }?.ex)
            muxer.start()

            val buffer = ByteBuffer.allocate(maxOf(maxInput, 8 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()

            // Interleave samples by timestamp so the result streams and seeks well.
            while (true) {
                val t = tracks.filter { !it.done }.minByOrNull { it.ex.sampleTime } ?: break
                buffer.clear()
                val size = t.ex.readSampleData(buffer, 0)
                if (size < 0) {
                    t.done = true
                    continue
                }
                val pts = t.ex.sampleTime
                if (ranges.none { pts >= it.startUs && pts < it.endUs }) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = pts - removedBefore(ranges, pts)
                    info.flags = if (t.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(t.outIndex, buffer, info)
                }
                t.ex.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }

    /** Snaps each cut's end forward to the next video keyframe and merges overlaps. */
    private fun resolveCuts(cuts: List<Cut>, video: MediaExtractor?): List<Cut> {
        if (cuts.isEmpty()) return emptyList()
        val keyframes = mutableListOf<Long>()
        if (video != null) {
            while (true) {
                val t = video.sampleTime
                if (t < 0) break
                if (video.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) keyframes += t
                video.advance()
            }
            video.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }
        val snapped = cuts.filter { it.endUs > it.startUs }.map { c ->
            val end = if (video == null) c.endUs
            else keyframes.firstOrNull { it >= c.endUs } ?: Long.MAX_VALUE
            Cut(c.startUs, end)
        }.sortedBy { it.startUs }

        val merged = mutableListOf<Cut>()
        for (c in snapped) {
            val last = merged.lastOrNull()
            if (last != null && c.startUs <= last.endUs) {
                merged[merged.size - 1] = Cut(last.startUs, maxOf(last.endUs, c.endUs))
            } else merged += c
        }
        return merged
    }

    /** Total time removed by cuts that end at or before [pts]. */
    private fun removedBefore(ranges: List<Cut>, pts: Long): Long =
        ranges.filter { it.endUs <= pts }.sumOf { it.endUs - it.startUs }

    private fun selectTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) {
                ex.selectTrack(i)
                return i
            }
        }
        throw IllegalStateException("No $prefix track found")
    }
}

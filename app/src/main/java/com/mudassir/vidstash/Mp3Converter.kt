package com.mudassir.vidstash

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a downloaded audio file (AAC/M4A or Opus) to a 192 kbps MP3 with title,
 * artist, album and cover art. Decoding uses the phone's own decoder; encoding uses
 * LAME (pure Java port), so no native libraries are needed.
 */
object Mp3Converter {

    const val BITRATE_KBPS = 192

    class Tags(val title: String, val artist: String?, val album: String?, val coverJpeg: ByteArray?)

    /**
     * @param onProgress 0–100
     * @param checkCancelled throws to stop (e.g. the download was skipped)
     */
    fun convert(input: File, output: File, tags: Tags, onProgress: (Int) -> Unit, checkCancelled: () -> Unit) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var encoder: LameEncoder? = null
        try {
            ex.setDataSource(input.path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("No audio track")
            ex.selectTrack(track)
            val inFormat = ex.getTrackFormat(track)
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else -1L

            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            BufferedOutputStream(FileOutputStream(output), 256 * 1024).use { out ->
                Id3.write(out, tags)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var lastPct = -1
                var pcmFloat = false
                while (!outputDone) {
                    checkCancelled()
                    if (!inputDone) {
                        val i = dec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = dec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = dec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            pcmFloat = isFloat(dec.outputFormat)
                        }
                        o >= 0 -> {
                            if (info.size > 0) {
                                if (encoder == null) {
                                    val f = dec.outputFormat
                                    pcmFloat = isFloat(f)
                                    encoder = LameEncoder(
                                        f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                                        f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    )
                                }
                                val buf = dec.getOutputBuffer(o)!!
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                encoder!!.encode(buf.slice().order(ByteOrder.nativeOrder()), pcmFloat, out)
                            }
                            dec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (durationUs > 0) {
                                val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
                encoder?.finish(out) ?: throw IllegalStateException("No audio decoded")
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
            runCatching { encoder?.close() }
        }
    }

    private fun isFloat(f: MediaFormat): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT

    /** Thin wrapper around jump3r's LAME port (same set-up as its own LameEncoder, minus javax.sound). */
    internal class LameEncoder(channels: Int, sampleRate: Int) {
        private val lame = Lame()
        private val gfp: LameGlobalFlags
        private val inChannels = channels
        private val outChannels = channels.coerceIn(1, 2)
        private var left = IntArray(0)
        private var right = IntArray(0)
        private var mp3 = ByteArray(0)

        init {
            val gaud = GetAudio()
            val ga = GainAnalysis()
            val bs = BitStream()
            val p = Presets()
            val qupvt = QuantizePVT()
            val qu = Quantize()
            val vbr = VBRTag()
            val ver = Version()
            val id3 = ID3Tag()
            val rv = Reservoir()
            val tak = Takehiro()
            val parse = Parse()
            val mpg = MPGLib()
            val intf = Interface()
            val common = Common()

            lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
            bs.setModules(ga, mpg, ver, vbr)
            id3.setModules(bs, ver)
            p.setModules(lame)
            qu.setModules(bs, rv, qupvt, tak)
            qupvt.setModules(tak, rv, lame.enc.psy)
            rv.setModules(bs)
            tak.setModules(qupvt)
            vbr.setModules(lame, bs, ver)
            gaud.setModules(parse, mpg)
            parse.setModules(ver, id3, p)
            mpg.setModules(intf, common)
            intf.setModules(vbr, common)

            gfp = lame.lame_init()
            gfp.num_channels = outChannels
            gfp.in_samplerate = sampleRate
            gfp.mode = if (outChannels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
            gfp.VBR = VbrMode.vbr_off // constant bitrate: correct length shown in every player
            gfp.brate = BITRATE_KBPS
            gfp.quality = 5 // good quality at a speed a phone can manage
            id3.id3tag_init(gfp)
            gfp.write_id3tag_automatic = false // we write our own tag (with cover art)
            gfp.bWriteVbrTag = false
            gfp.findReplayGain = false
            val rc = lame.lame_init_params(gfp)
            if (rc < 0) throw IllegalStateException("MP3 encoder setup failed ($rc)")
        }

        fun encode(pcm: ByteBuffer, isFloat: Boolean, out: OutputStream) {
            val frames: Int
            if (isFloat) {
                val fb = pcm.asFloatBuffer()
                frames = fb.remaining() / inChannels
                ensure(frames)
                for (i in 0 until frames) {
                    left[i] = toInt(fb.get(i * inChannels))
                    right[i] = if (inChannels > 1) toInt(fb.get(i * inChannels + 1)) else left[i]
                }
            } else {
                val sb = pcm.asShortBuffer()
                frames = sb.remaining() / inChannels
                ensure(frames)
                for (i in 0 until frames) {
                    left[i] = sb.get(i * inChannels).toInt() shl 16
                    right[i] = if (inChannels > 1) sb.get(i * inChannels + 1).toInt() shl 16 else left[i]
                }
            }
            if (frames == 0) return
            val n = lame.lame_encode_buffer_int(gfp, left, right, frames, mp3, 0, mp3.size)
            if (n < 0) throw IllegalStateException("MP3 encoding failed ($n)")
            out.write(mp3, 0, n)
        }

        fun finish(out: OutputStream) {
            if (mp3.size < 7200) mp3 = ByteArray(7200)
            val n = lame.lame_encode_flush(gfp, mp3, 0, mp3.size)
            if (n > 0) out.write(mp3, 0, n)
        }

        fun close() {
            runCatching { lame.lame_close(gfp) }
        }

        private fun ensure(frames: Int) {
            if (left.size < frames) {
                left = IntArray(frames)
                right = IntArray(frames)
            }
            val need = (frames * 5 / 4) + 7200
            if (mp3.size < need) mp3 = ByteArray(need)
        }

        private fun toInt(f: Float): Int = (f.coerceIn(-1f, 0.99999994f) * Int.MAX_VALUE).toInt()
    }

    /** ID3v2.3 tag: title, artist, album and front cover. Text in UTF-16 so Urdu/Arabic titles work. */
    internal object Id3 {
        fun write(out: OutputStream, t: Tags) {
            val frames = ByteArrayOutputStream()
            text(frames, "TIT2", t.title)
            t.artist?.takeIf { it.isNotBlank() }?.let { text(frames, "TPE1", it) }
            t.album?.takeIf { it.isNotBlank() }?.let { text(frames, "TALB", it) }
            text(frames, "TSSE", "VidStash (LAME ${BITRATE_KBPS} kbps)")
            t.coverJpeg?.let { jpeg ->
                val body = ByteArrayOutputStream()
                body.write(0) // ISO-8859-1 description
                body.write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
                body.write(0)
                body.write(3) // front cover
                body.write(0) // empty description
                body.write(jpeg)
                frame(frames, "APIC", body.toByteArray())
            }
            val data = frames.toByteArray()
            val size = data.size
            out.write("ID3".toByteArray(Charsets.ISO_8859_1))
            out.write(byteArrayOf(3, 0, 0)) // v2.3, no flags
            // Tag size is "syncsafe": 7 bits per byte.
            out.write(byteArrayOf(
                (size shr 21 and 0x7f).toByte(), (size shr 14 and 0x7f).toByte(),
                (size shr 7 and 0x7f).toByte(), (size and 0x7f).toByte()
            ))
            out.write(data)
        }

        private fun text(out: ByteArrayOutputStream, id: String, value: String) {
            val body = ByteArrayOutputStream()
            body.write(1) // UTF-16 with byte order mark
            body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            body.write(value.toByteArray(Charsets.UTF_16LE))
            frame(out, id, body.toByteArray())
        }

        private fun frame(out: ByteArrayOutputStream, id: String, body: ByteArray) {
            out.write(id.toByteArray(Charsets.ISO_8859_1))
            val n = body.size
            out.write(byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte()))
            out.write(byteArrayOf(0, 0)) // flags
            out.write(body)
        }
    }
}

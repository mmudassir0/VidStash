package com.mudassir.ytdownloader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class Mp3ConverterTest {

    private fun sine(seconds: Int, rate: Int, channels: Int): ByteBuffer {
        val frames = seconds * rate
        val buf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val s = (sin(2 * PI * 440 * i / rate) * 12000).toInt().toShort()
            repeat(channels) { buf.putShort(s) }
        }
        buf.flip()
        return buf
    }

    @Test
    fun encodesStereoToValidMp3Frames() {
        val enc = Mp3Converter.LameEncoder(2, 44100)
        val out = ByteArrayOutputStream()
        val pcm = sine(3, 44100, 2)
        // Feed in decoder-sized chunks, like MediaCodec does.
        val chunk = 4096 * 2
        while (pcm.hasRemaining()) {
            val n = minOf(chunk, pcm.remaining())
            val part = pcm.slice().order(ByteOrder.nativeOrder())
            part.limit(n)
            enc.encode(part, false, out)
            pcm.position(pcm.position() + n)
        }
        enc.finish(out)
        enc.close()
        val bytes = out.toByteArray()
        // 3 s at 192 kbps is about 72 KB.
        assertTrue("size ${bytes.size}", bytes.size in 60_000..85_000)
        // First MP3 frame header: sync word + MPEG-1 Layer III.
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        assertEquals(0xFA, bytes[1].toInt() and 0xFE)
        // Bitrate index 11 = 192 kbps for MPEG-1 Layer III; 44.1 kHz = index 0.
        assertEquals(11, (bytes[2].toInt() and 0xF0) shr 4)
        assertEquals(0, (bytes[2].toInt() and 0x0C) shr 2)
    }

    @Test
    fun encodesMonoAndFloat() {
        val enc = Mp3Converter.LameEncoder(1, 48000)
        val out = ByteArrayOutputStream()
        val frames = 48000
        val buf = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) buf.putFloat((sin(2 * PI * 440 * i / 48000) * 0.4).toFloat())
        buf.flip()
        enc.encode(buf, true, out)
        enc.finish(out)
        val bytes = out.toByteArray()
        assertTrue("size ${bytes.size}", bytes.size in 18_000..30_000)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
    }

    @Test
    fun writesId3TagWithCover() {
        val out = ByteArrayOutputStream()
        val cover = ByteArray(1000) { it.toByte() }
        Mp3Converter.Id3.write(out, Mp3Converter.Tags("نعت – Title", "Artist", "Album", cover))
        val b = out.toByteArray()
        assertArrayEquals("ID3".toByteArray(), b.copyOfRange(0, 3))
        val size = (b[6].toInt() shl 21) or (b[7].toInt() shl 14) or (b[8].toInt() shl 7) or b[9].toInt()
        assertEquals(b.size - 10, size)
        assertTrue(String(b, Charsets.ISO_8859_1).contains("APIC"))
        assertTrue(String(b, Charsets.ISO_8859_1).contains("TIT2"))
    }
}

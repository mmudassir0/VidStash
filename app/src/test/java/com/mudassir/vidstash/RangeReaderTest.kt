package com.mudassir.vidstash

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The player's YouTube reader against a fake media server that only answers "&range=a-b" requests. */
class RangeReaderTest {

    private val media = ByteArray(1_000_003) { (it * 31 % 251).toByte() }
    private lateinit var server: MockWebServer
    private val ranges = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val r = request.requestUrl!!.queryParameter("range") ?: return MockResponse().setResponseCode(403)
                ranges += r
                val (a, b) = r.split('-').map { it.toInt() }
                return MockResponse().setBody(Buffer().write(media, a, minOf(b, media.size - 1) - a + 1))
            }
        }
        server.start()
        RangeReader.extraHosts = listOf(server.hostName)
        RangeReader.chunkSize = 100_000 // several pieces per read-through
    }

    @After
    fun tearDown() = server.shutdown()

    private fun url() = server.url("/videoplayback?itag=136&clen=${media.size}").toString()

    private fun readAll(start: Long, length: Long?): Pair<Long, ByteArray> {
        val total = RangeReader.totalFor(url())
        assertEquals(media.size.toLong(), total)
        val len = length ?: (total - start)
        val r = RangeReader(url(), start, len, total)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = r.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        r.close()
        return len to out.toByteArray()
    }

    @Test
    fun readsWholeFileInPieces() {
        val (len, data) = readAll(0, null)
        assertEquals(media.size.toLong(), len)
        assertArrayEquals(media, data)
        assertTrue("used range pieces: $ranges", ranges.size >= 10 && ranges.first() == "0-99999")
    }

    @Test
    fun seeksAndReadsAPart() {
        val (len, data) = readAll(250_000, 123_456)
        assertEquals(123_456L, len)
        assertArrayEquals(media.copyOfRange(250_000, 250_000 + 123_456), data)
    }

    @Test
    fun readsFromAnOffsetToTheEnd() {
        val (len, data) = readAll(999_000, null)
        assertEquals(1_003L, len)
        assertArrayEquals(media.copyOfRange(999_000, media.size), data)
    }

    @Test
    fun otherHostsAreNotChunked() {
        assertEquals(-1L, RangeReader.totalFor("https://example.com/video.mp4?clen=5"))
    }
}

package com.mudassir.vidstash

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import java.util.concurrent.TimeUnit

/**
 * Checks that the app can still read YouTube: get a video's formats, fetch the first bytes
 * of the video and audio tracks the way the downloader does, and run a search.
 *
 * Only runs when YT_HEALTH=1 (the daily GitHub check), not in normal builds.
 * If YouTube refuses GitHub's servers ("confirm you're not a bot"), the test is skipped,
 * because that says nothing about phones.
 */
class HealthCheckTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue("Set YT_HEALTH=1 to run", System.getenv("YT_HEALTH") == "1")
            NewPipe.init(OkHttpDownloader.instance)
        }

        private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

        /** Same request the downloader makes for its first 10 MB piece, but only 64 KB. */
        private fun firstBytes(url: String): Int {
            val req = Request.Builder().url("$url&range=0-65535")
                .header("User-Agent", OkHttpDownloader.USER_AGENT).build()
            return http.newCall(req).execute().use { res ->
                assertTrue("HTTP ${res.code} for media request", res.isSuccessful)
                res.body!!.bytes().size
            }
        }

        private fun <T> notBlocked(block: () -> T): T = try {
            block()
        } catch (e: Exception) {
            val text = generateSequence<Throwable>(e) { it.cause }.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
            assumeTrue("YouTube blocked this server: $text",
                !text.contains("not a bot", true) && !text.contains("SignInConfirm", true) && !text.contains("429"))
            throw e
        }
    }

    @Test
    fun videoFormatsAndMedia() {
        // Big Buck Bunny (official upload): long-lived, has HD formats.
        val d = notBlocked { YouTube.getVideo("https://www.youtube.com/watch?v=aqz-KE-bpKQ", allowWebm = false) }
        assertTrue("no formats", d.options.isNotEmpty())
        assertTrue("no chapters/duration info", d.durationSec > 0)
        val merged = d.options.firstOrNull { it.kind == Kind.MERGE && it.height >= 720 }
        assertTrue("no HD MP4 format", merged != null)
        assertTrue("video bytes", firstBytes(merged!!.videoUrl!!) > 1000)
        assertTrue("audio bytes", firstBytes(merged.audioUrl!!) > 1000)
    }

    @Test
    fun search() {
        val page = notBlocked { YouTube.search("big buck bunny") }
        assertTrue("no search results", page.videos.isNotEmpty())
    }
}

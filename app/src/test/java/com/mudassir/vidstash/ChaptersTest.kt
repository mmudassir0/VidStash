package com.mudassir.vidstash

import org.junit.Assert.assertEquals
import org.junit.Test

class ChaptersTest {
    private val chapters = listOf(
        Chapter("Intro", 0), Chapter("Song A", 60), Chapter("Song B", 240), Chapter("Outro", 500)
    )

    private fun us(sec: Long) = sec * 1_000_000

    @Test
    fun noCutsKeepsTimes() {
        assertEquals(chapters, ItemDownloader.adjustChapters(chapters, emptyList(), 600))
    }

    @Test
    fun sectionShiftsAndDropsChapters() {
        // Keep 100 s – 300 s: "Song A" (cut start) begins at 0, "Song B" at 140, the rest is gone.
        val cuts = listOf(Cut(0, us(100)), Cut(us(300), Long.MAX_VALUE))
        val got = ItemDownloader.adjustChapters(chapters, cuts, 600)
        assertEquals(listOf(Chapter("Song A", 0), Chapter("Song B", 140)), got)
    }

    @Test
    fun sponsorCutMovesLaterChapters() {
        // 30 s removed at 200–230: chapters after it move 30 s earlier.
        val got = ItemDownloader.adjustChapters(chapters, listOf(Cut(us(200), us(230))), 600)
        assertEquals(listOf(Chapter("Intro", 0), Chapter("Song A", 60), Chapter("Song B", 210), Chapter("Outro", 470)), got)
    }
}

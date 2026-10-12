package com.mudassir.ytdownloader

import java.util.concurrent.ConcurrentHashMap

/**
 * Details of videos being watched online (not downloaded), shared between the screen that
 * starts playback, the playback service and the player: chapters, and the original link.
 */
object StreamCache {

    /** Media id of an online video in the player, e.g. "yt:https://www.youtube.com/watch?v=…". */
    const val SCHEME = "yt:"

    private val chapters = ConcurrentHashMap<String, List<Chapter>>()

    fun isStream(id: String?) = id?.startsWith(SCHEME) == true
    fun idFor(url: String) = SCHEME + url
    fun urlOf(id: String) = id.removePrefix(SCHEME)

    fun putChapters(id: String, list: List<Chapter>) {
        chapters[id] = list
    }

    fun chapters(id: String): List<Chapter> = chapters[id].orEmpty()
}

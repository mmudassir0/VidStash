package com.mudassir.ytdownloader

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** App settings, backed by the same SharedPreferences the Settings screen edits. */
class Settings(ctx: Context) {
    private val p: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    /** Only download on Wi-Fi / unmetered networks. */
    val wifiOnly get() = p.getBoolean(K_WIFI_ONLY, false)

    /** How many videos download at the same time (1–3). */
    val parallel get() = (p.getString(K_PARALLEL, "1")?.toIntOrNull() ?: 1).coerceIn(1, 3)

    /** Default max height for new downloads; 0 = audio only. */
    val defaultQuality get() = p.getString(K_QUALITY, "720")?.toIntOrNull() ?: 720

    /** Subtitle language code to save with videos ("" = off), e.g. "en". */
    val subtitleLang get() = p.getString(K_SUBS, "")?.trim().orEmpty()

    /** Remove sponsor / self-promotion segments using SponsorBlock. */
    val sponsorBlock get() = p.getBoolean(K_SPONSORBLOCK, false)

    /** Allow 1440p / 4K (VP9 + Opus in WebM). Off = MP4 only, max 1080p. */
    val allowWebm get() = p.getBoolean(K_WEBM, true)

    /** Check synced playlists for new videos every night. */
    val autoSync get() = p.getBoolean(K_AUTO_SYNC, false)

    /** Check GitHub for a newer version on launch. */
    val autoUpdateCheck get() = p.getBoolean(K_UPDATE_CHECK, true)

    /** Play the next file in the list when one finishes. */
    val autoplayNext get() = p.getBoolean(K_AUTOPLAY, true)

    /** "m4a" keeps YouTube's audio as is; "mp3" converts for car stereos and old players. */
    val audioFormat get() = p.getString(K_AUDIO_FORMAT, "m4a") ?: "m4a"

    /** Offer to download a YouTube link found on the clipboard when the app opens. */
    val clipboardPrompt get() = p.getBoolean(K_CLIPBOARD, true)

    /** Remember searches and show them as suggestions. */
    val searchHistory get() = p.getBoolean(K_HISTORY, true)

    /** Player speed, kept between videos. */
    var playbackSpeed: Float
        get() = p.getFloat(K_SPEED, 1f)
        set(v) = p.edit().putFloat(K_SPEED, v).apply()

    /** Library sort order: "date", "name", "size", "length". */
    var librarySort: String
        get() = p.getString(K_LIB_SORT, "date") ?: "date"
        set(v) = p.edit().putString(K_LIB_SORT, v).apply()

    /** Last clipboard link we already asked about, so we don't ask twice. */
    var lastClipboardLink: String
        get() = p.getString(K_LAST_CLIP, "") ?: ""
        set(v) = p.edit().putString(K_LAST_CLIP, v).apply()

    /** Recent searches, newest first. */
    var recentSearches: List<String>
        get() = p.getString(K_RECENT, "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = p.edit().putString(K_RECENT, v.take(MAX_RECENT).joinToString("\n")).apply()

    fun removeSearch(q: String) {
        recentSearches = recentSearches.filterNot { it == q }
    }

    fun addSearch(q: String) {
        if (!searchHistory) return
        val t = q.trim().replace('\n', ' ')
        if (t.isEmpty()) return
        recentSearches = listOf(t) + recentSearches.filterNot { it.equals(t, ignoreCase = true) }
    }

    var paused: Boolean
        get() = p.getBoolean(K_PAUSED, false)
        set(v) = p.edit().putBoolean(K_PAUSED, v).apply()

    var lastUpdateCheck: Long
        get() = p.getLong(K_LAST_UPDATE_CHECK, 0)
        set(v) = p.edit().putLong(K_LAST_UPDATE_CHECK, v).apply()

    /** Saved playback position (ms) for a file, used by the player to continue watching. */
    fun position(uri: String): Long = p.getLong("pos:$uri", 0)
    fun setPosition(uri: String, ms: Long) = p.edit().putLong("pos:$uri", ms).apply()

    companion object {
        const val K_WIFI_ONLY = "wifi_only"
        const val K_PARALLEL = "parallel"
        const val K_QUALITY = "default_quality"
        const val K_SUBS = "subtitle_lang"
        const val K_SPONSORBLOCK = "sponsorblock"
        const val K_WEBM = "allow_webm"
        const val K_AUTO_SYNC = "auto_sync"
        const val K_UPDATE_CHECK = "update_check"
        const val K_AUTOPLAY = "autoplay_next"
        const val K_AUDIO_FORMAT = "audio_format"
        const val K_CLIPBOARD = "clipboard_prompt"
        const val K_HISTORY = "search_history"
        const val K_CLEAR_HISTORY = "clear_history"
        private const val K_SPEED = "playback_speed"
        private const val K_LIB_SORT = "library_sort"
        private const val K_LAST_CLIP = "last_clipboard_link"
        private const val K_RECENT = "recent_searches"
        private const val MAX_RECENT = 30
        private const val K_PAUSED = "paused"
        private const val K_LAST_UPDATE_CHECK = "last_update_check"

        /** Quality choices shown everywhere: label to max height (0 = audio only). */
        val QUALITIES = listOf(
            "2160p (4K)" to 2160, "1440p" to 1440, "1080p" to 1080, "720p" to 720,
            "480p" to 480, "360p" to 360, "Audio only" to 0
        )
    }
}

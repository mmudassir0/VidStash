package com.mudassir.vidstash

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Looks up community-submitted sponsor segments (sponsor.ajay.app) for a video. */
object SponsorBlock {

    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val CATEGORIES = listOf("sponsor", "selfpromo", "interaction", "intro", "outro")

    /** Segments to remove, or empty if none / the service can't be reached. */
    fun segments(videoId: String): List<Cut> = try {
        val cats = URLEncoder.encode(JSONArray(CATEGORIES).toString(), "UTF-8")
        val req = Request.Builder()
            .url("https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=$cats")
            .header("User-Agent", "VidStash (Android app)")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList() // 404 = no segments for this video
            val arr = JSONArray(res.body!!.string())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                if (o.optString("actionType", "skip") != "skip") return@mapNotNull null
                val seg = o.getJSONArray("segment")
                Cut((seg.getDouble(0) * 1_000_000).toLong(), (seg.getDouble(1) * 1_000_000).toLong())
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

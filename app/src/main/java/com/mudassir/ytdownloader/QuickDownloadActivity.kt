package com.mudassir.ytdownloader

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.mudassir.ytdownloader.databinding.SheetQuickBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opened from the share menu (e.g. Share → YT Downloader in the YouTube app).
 * Shows a small sheet over the other app: pick a quality, tap Download, done,
 * without opening the full app. Playlists can be queued whole from here too.
 */
class QuickDownloadActivity : AppCompatActivity() {

    private lateinit var b: SheetQuickBinding
    private lateinit var sheet: BottomSheetDialog
    private var sharedText = ""

    /** One choice in the quality row. */
    private class Choice(val label: String, val maxHeight: Int, val bytes: Long, val mp3: Boolean = false,
                         val videoItag: Int = -1, val audioItag: Int = -1)

    private var choices: List<Choice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = SheetQuickBinding.inflate(layoutInflater)
        sheet = BottomSheetDialog(this).apply {
            setContentView(b.root)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            setOnDismissListener { finish() }
        }
        b.openAppButton.setOnClickListener { openInApp() }
        sheet.show()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        val url = Regex("https?://\\S+").find(sharedText)?.value
        if (url == null) {
            // Plain text: treat it as a search in the app.
            if (sharedText.isNotEmpty()) openInApp() else showError("Nothing to download in what was shared.")
            return
        }
        when (YouTube.linkType(url)) {
            YouTube.Link.VIDEO -> loadVideo(url)
            YouTube.Link.PLAYLIST -> loadPlaylist(url)
            YouTube.Link.CHANNEL -> openInApp()
            YouTube.Link.NONE -> showError("That isn't a YouTube video or playlist link.")
        }
    }

    // ---------- one video ----------

    private fun loadVideo(url: String) {
        showLoading("Getting video details…")
        val settings = Settings(this)
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { YouTube.getVideo(url, settings.allowWebm) } }
            val d = result.getOrElse {
                showError("Couldn't load this video: ${it.message ?: "unknown error"}")
                return@launch
            }
            if (d.options.isEmpty()) {
                showError("No downloadable formats found for this video.")
                return@launch
            }
            // One chip per resolution (best file for each), then audio.
            val video = d.options.filter { it.kind != Kind.AUDIO }
                .groupBy { it.height }
                .map { (_, opts) -> opts.maxByOrNull { if (it.extension == "mp4") 1 else 0 }!! }
                .sortedByDescending { it.height }
            val audio = d.options.firstOrNull { it.kind == Kind.AUDIO }
            choices = buildList {
                video.forEach { add(Choice("${it.height}p", it.height, it.bytes, videoItag = it.videoItag, audioItag = it.audioItag)) }
                if (audio != null) {
                    // Audio in the default format, plus MP3 if that isn't already the default.
                    val mp3Default = settings.audioFormat == "mp3"
                    add(Choice(if (mp3Default) "MP3" else "Audio M4A", 0, audio.bytes, mp3 = mp3Default,
                        audioItag = audio.audioItag, videoItag = audio.videoItag))
                    if (!mp3Default) add(Choice("MP3", 0, audio.bytes, mp3 = true, audioItag = audio.audioItag, videoItag = audio.videoItag))
                }
            }
            val default = YouTube.pickBest(d.options, settings.defaultQuality)
            val defIndex = choices.indexOfFirst {
                if (default?.kind == Kind.AUDIO) it.maxHeight == 0 else it.maxHeight == default?.height
            }.coerceAtLeast(0)

            b.title.text = d.title
            b.meta.text = d.uploader
            b.thumb.load(d.thumbnail) { crossfade(true) }
            b.duration.text = formatDuration(d.durationSec)
            b.duration.isVisible = d.durationSec > 0
            showChips(defIndex)
            b.downloadButton.text = "Download"
            b.downloadButton.setOnClickListener {
                val c = selected() ?: return@setOnClickListener
                Queue.add(
                    this@QuickDownloadActivity, listOf(
                        DownloadItem(
                            url = url, title = d.title, thumbnail = d.thumbnail, maxHeight = c.maxHeight,
                            videoItag = c.videoItag, audioItag = c.audioItag, mp3 = c.mp3,
                            durationSec = d.durationSec, uploader = d.uploader.ifBlank { null }
                        )
                    )
                )
                done("Downloading “${d.title}”")
            }
            showContent()
        }
    }

    // ---------- playlist ----------

    private fun loadPlaylist(url: String) {
        showLoading("Loading playlist…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    YouTube.getPlaylist(url) { n -> lifecycleScope.launch { b.loadingText.text = "Loading playlist… $n videos" } }
                }
            }
            val list = result.getOrElse {
                showError("Couldn't load this playlist: ${it.message ?: "unknown error"}")
                return@launch
            }
            if (list.videos.isEmpty()) {
                showError("This playlist has no videos.")
                return@launch
            }
            val settings = Settings(this@QuickDownloadActivity)
            choices = Settings.QUALITIES.filter { it.second in listOf(1080, 720, 480, 360, 0) }
                .map { (label, h) -> Choice(if (h == 0) "Audio" else label, h, -1) }
            val defIndex = choices.indexOfFirst { it.maxHeight == settings.defaultQuality }.coerceAtLeast(0)
            val total = list.videos.sumOf { maxOf(it.durationSec, 0L) }
            b.title.text = list.name
            b.meta.text = "Playlist · ${list.videos.size} videos · ${formatDuration(total)}"
            b.thumb.load(list.videos.first().thumbnail) { crossfade(true) }
            b.duration.text = "${list.videos.size}"
            b.duration.isVisible = true
            showChips(defIndex)
            b.sizeText.text = "Each video downloads at this quality or the closest lower one."
            b.downloadButton.text = "Download all ${list.videos.size} videos"
            b.downloadButton.setOnClickListener {
                val c = selected() ?: return@setOnClickListener
                Sync.queue(this@QuickDownloadActivity, list, list.videos, c.maxHeight, remember = false)
                done("Queued ${list.videos.size} videos from “${list.name}”")
            }
            showContent()
        }
    }

    // ---------- helpers ----------

    private fun showChips(selectedIndex: Int) {
        b.qualityChips.removeAllViews()
        choices.forEachIndexed { i, c ->
            b.qualityChips.addView(Chip(this).apply {
                id = View.generateViewId()
                tag = i
                text = if (c.bytes > 0) "${c.label} · ${formatBytes(c.bytes)}" else c.label
                isCheckable = true
                isChecked = i == selectedIndex
            })
        }
        b.qualityChips.setOnCheckedStateChangeListener { _, _ -> updateSize() }
        updateSize()
    }

    private fun selected(): Choice? {
        val chip = b.qualityChips.findViewById<Chip>(b.qualityChips.checkedChipId) ?: return null
        return choices.getOrNull(chip.tag as Int)
    }

    private fun updateSize() {
        val c = selected() ?: return
        if (c.bytes <= 0) return
        val free = Storage.freeBytes()
        b.sizeText.text = "Size: ${formatBytes(c.bytes)}" + if (free >= 0) "   •   Free on phone: ${formatBytes(free)}" else ""
        b.sizeText.isVisible = true
    }

    private fun showLoading(text: String) {
        b.loading.isVisible = true
        b.loadingText.text = text
        b.content.isVisible = false
        b.errorText.isVisible = false
    }

    private fun showContent() {
        b.loading.isVisible = false
        b.content.isVisible = true
        b.errorText.isVisible = false
    }

    private fun showError(message: String) {
        b.loading.isVisible = false
        b.content.isVisible = false
        b.errorText.isVisible = true
        b.errorText.text = message
    }

    private fun done(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        sheet.dismiss()
    }

    /** Hands the shared text to the full app (all options, search, channels). */
    private fun openInApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, sharedText)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        sheet.dismiss()
    }

    override fun onDestroy() {
        if (sheet.isShowing) sheet.setOnDismissListener(null)
        runCatching { sheet.dismiss() }
        super.onDestroy()
    }
}

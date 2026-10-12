package com.mudassir.vidstash

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
import com.mudassir.vidstash.databinding.SheetQuickBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opened from the share menu (e.g. Share → VidStash in the YouTube app).
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

    /** Widget's "Paste link": read the clipboard once our sheet has focus. */
    private var pendingPaste = false

    private fun handle(intent: Intent) {
        if (intent.action == ACTION_PASTE) {
            pendingPaste = true
            showLoading(getString(R.string.quick_reading_link))
            b.root.viewTreeObserver.addOnWindowFocusChangeListener { focus -> if (focus) readClipboard() }
            b.root.postDelayed({ readClipboard() }, 400)
            return
        }
        handleText(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
    }

    private fun readClipboard() {
        if (!pendingPaste) return
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val text = runCatching { cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        if (text.isNullOrBlank()) {
            // Not readable yet (no focus) — the focus listener tries again.
            if (b.root.hasWindowFocus()) {
                pendingPaste = false
                showError(getString(R.string.quick_copy_first))
            }
            return
        }
        pendingPaste = false
        handleText(text)
    }

    private fun handleText(text: String) {
        sharedText = text.trim()
        val url = Regex("https?://\\S+").find(sharedText)?.value
        if (url == null) {
            // Plain text: treat it as a search in the app.
            if (sharedText.isNotEmpty()) openInApp() else showError(getString(R.string.quick_nothing))
            return
        }
        when (YouTube.linkType(url)) {
            YouTube.Link.VIDEO -> loadVideo(url)
            YouTube.Link.PLAYLIST -> loadPlaylist(url)
            YouTube.Link.CHANNEL -> openInApp()
            YouTube.Link.NONE -> showError(getString(R.string.quick_not_youtube))
        }
    }

    // ---------- one video ----------

    private fun loadVideo(url: String) {
        showLoading(getString(R.string.quick_getting_details))
        val settings = Settings(this)
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { YouTube.getVideo(url, settings.allowWebm) } }
            val d = result.getOrElse {
                showError(getString(R.string.quick_video_failed, it.message ?: getString(R.string.common_unknown_error)))
                return@launch
            }
            if (d.options.isEmpty()) {
                showError(getString(R.string.get_no_formats))
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
                    add(Choice(getString(if (mp3Default) R.string.quick_mp3 else R.string.quick_audio_m4a), 0, audio.bytes, mp3 = mp3Default,
                        audioItag = audio.audioItag, videoItag = audio.videoItag))
                    if (!mp3Default) add(Choice(getString(R.string.quick_mp3), 0, audio.bytes, mp3 = true, audioItag = audio.audioItag, videoItag = audio.videoItag))
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
            val have = Store.downloaded(YouTube.videoId(url))
            b.downloadButton.text = getString(if (have != null) R.string.get_download_again else R.string.common_download)
            if (have != null) {
                b.meta.text = getString(R.string.quick_already, d.uploader)
            }
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
                done(getString(R.string.quick_downloading, d.title))
            }
            showContent()
        }
    }

    // ---------- playlist ----------

    private fun loadPlaylist(url: String) {
        showLoading(getString(R.string.quick_loading_playlist))
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    YouTube.getPlaylist(url) { n -> lifecycleScope.launch { b.loadingText.text = getString(R.string.quick_loading_playlist_n, n) } }
                }
            }
            val list = result.getOrElse {
                showError(getString(R.string.quick_playlist_failed, it.message ?: getString(R.string.common_unknown_error)))
                return@launch
            }
            if (list.videos.isEmpty()) {
                showError(getString(R.string.quick_playlist_empty))
                return@launch
            }
            val settings = Settings(this@QuickDownloadActivity)
            choices = Settings.QUALITIES.filter { it.second in listOf(1080, 720, 480, 360, 0) }
                .map { (label, h) -> Choice(if (h == 0) getString(R.string.quick_audio) else label, h, -1) }
            val defIndex = choices.indexOfFirst { it.maxHeight == settings.defaultQuality }.coerceAtLeast(0)
            val total = list.videos.sumOf { maxOf(it.durationSec, 0L) }
            b.title.text = list.name
            b.meta.text = getString(R.string.quick_playlist_meta, list.videos.size, formatDuration(total))
            b.thumb.load(list.videos.first().thumbnail) { crossfade(true) }
            b.duration.text = "${list.videos.size}"
            b.duration.isVisible = true
            showChips(defIndex)
            b.sizeText.text = getString(R.string.quick_closest_quality)
            b.downloadButton.text = getString(R.string.quick_download_all, list.videos.size)
            b.downloadButton.setOnClickListener {
                val c = selected() ?: return@setOnClickListener
                Sync.queue(this@QuickDownloadActivity, list, list.videos, c.maxHeight, remember = false)
                done(getString(R.string.quick_queued_from, list.videos.size, list.name))
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
        b.sizeText.text = getString(R.string.quick_size, formatBytes(c.bytes)) + if (free >= 0) getString(R.string.quick_free, formatBytes(free)) else ""
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

    companion object {
        const val ACTION_PASTE = "com.mudassir.vidstash.PASTE"
    }

    override fun onDestroy() {
        if (sheet.isShowing) sheet.setOnDismissListener(null)
        runCatching { sheet.dismiss() }
        super.onDestroy()
    }
}

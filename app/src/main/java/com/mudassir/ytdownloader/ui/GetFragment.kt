package com.mudassir.ytdownloader.ui

import android.content.ClipboardManager
import android.content.Intent
import android.content.ClipData
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mudassir.ytdownloader.Settings
import com.mudassir.ytdownloader.VideoEntry
import com.mudassir.ytdownloader.Storage
import com.mudassir.ytdownloader.YouTube
import com.mudassir.ytdownloader.databinding.FragmentGetBinding
import com.mudassir.ytdownloader.databinding.ItemFooterBinding
import com.mudassir.ytdownloader.databinding.ViewGetHeaderBinding
import com.mudassir.ytdownloader.formatBytes
import com.mudassir.ytdownloader.formatDuration
import android.os.Build
import android.view.ViewTreeObserver
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.chip.Chip
import com.mudassir.ytdownloader.R
import kotlinx.coroutines.launch

/** Paste a link or search; shows one video, a playlist/channel to pick from, or search results. */
class GetFragment : Fragment() {

    private var _b: FragmentGetBinding? = null
    private val b get() = _b!!
    private lateinit var h: ViewGetHeaderBinding
    private lateinit var footer: ItemFooterBinding
    private lateinit var headerAdapter: SingleViewAdapter
    private lateinit var footerAdapter: SingleViewAdapter
    private lateinit var videos: VideoEntryAdapter

    private val vm: GetViewModel by viewModels()
    private val qualities = Settings.QUALITIES

    companion object {
        /** Clipboard text already looked at (by its copy time). */
        private var lastClipStamp = 0L
    }

    /** Text to submit once the view exists (e.g. shared from the YouTube app). */
    private var pending: String? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            vm.goBack()
            updateBack()
        }
    }

    /** Back steps through screens only while this tab is the one being shown. */
    private fun updateBack() {
        backCallback.isEnabled = !isHidden && vm.canGoBack
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        updateBack()
        if (!hidden) checkClipboard()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentGetBinding.inflate(inflater, container, false)
        // The RecyclerView needs its LayoutManager before views can be inflated against it.
        b.results.layoutManager = LinearLayoutManager(requireContext())
        h = ViewGetHeaderBinding.inflate(inflater, b.results, false)
        footer = ItemFooterBinding.inflate(inflater, b.results, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        videos = VideoEntryAdapter(
            onClick = { entry ->
                when (vm.state.value) {
                    is GetState.Videos -> vm.toggle(entry.id)
                    else -> vm.openVideo(entry.url)
                }
            },
            onMore = { entry, anchor -> showVideoMenu(entry, anchor) }
        )
        headerAdapter = SingleViewAdapter(h.root)
        footerAdapter = SingleViewAdapter(footer.root).apply { shown = false }
        b.results.itemAnimator = null // header/footer views are reused; no remove/insert animations
        b.results.adapter = ConcatAdapter(headerAdapter, videos, footerAdapter)
        b.results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // Infinite scroll for search results.
                if (dy > 0 && !rv.canScrollVertically(1)) vm.loadMoreResults()
            }
        })
        footer.moreButton.setOnClickListener { vm.loadMoreResults() }

        b.pasteButton.setOnClickListener { paste() }
        b.inputLayout.setEndIconOnClickListener { submit() }
        b.input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { submit(); true } else false
        }
        suggestions = SuggestionAdapter(requireContext())
        b.input.setAdapter(suggestions)
        b.input.setOnItemClickListener { _, _, _, _ -> submit() }
        // Tapping the empty box shows recent searches.
        b.input.setOnClickListener { if (b.input.text.isNullOrEmpty()) showRecent() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)

        h.qualitySpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, qualities.map { it.first })
        h.qualitySpinner.onItemSelectedListener = onSelected { updateSizeText() }
        h.formatSpinner.onItemSelectedListener = onSelected { updateSizeText() }
        h.limitSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item,
            GetViewModel.CHANNEL_LIMITS.map { "$it videos" }
        )
        h.selectAllButton.setOnClickListener { vm.selectAll(true) }
        h.selectNoneButton.setOnClickListener { vm.selectAll(false) }
        h.calcSizeButton.setOnClickListener { vm.calculateSizes() }
        h.openPlaylistButton.setOnClickListener {
            (vm.state.value as? GetState.Video)?.playlistUrl?.let { vm.openPlaylist(it) }
        }
        h.downloadButton.setOnClickListener { download() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect { render(it) } }
                launch { vm.sizes.collect { updateSizeText() } }
                launch {
                    vm.toast.collect { msg ->
                        if (msg != null) {
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                            vm.toastShown()
                        }
                    }
                }
            }
        }

        pending?.let { submitText(it) }
        pending = null
    }

    /** Called by MainActivity for links shared from other apps. */
    fun openShared(text: String) {
        if (_b == null) pending = text else submitText(text)
    }

    private fun submitText(text: String) {
        b.input.setText(text, false)
        submit()
    }

    private fun submit() {
        b.input.dismissDropDown()
        vm.submit(b.input.text?.toString().orEmpty())
        hideKeyboard()
    }

    private lateinit var suggestions: SuggestionAdapter

    private fun showRecent() {
        suggestions.filter.filter("") { count ->
            if (count > 0 && _b != null && b.input.hasFocus()) b.input.showDropDown()
        }
    }

    /** Recent searches as chips on the start screen; tap to search again. */
    private fun renderRecent() {
        val recent = Settings(requireContext()).let { if (it.searchHistory) it.recentSearches.take(12) else emptyList() }
        b.recentTitle.isVisible = recent.isNotEmpty()
        b.recentChips.isVisible = recent.isNotEmpty()
        b.recentChips.removeAllViews()
        recent.forEach { q ->
            b.recentChips.addView(Chip(requireContext()).apply {
                text = q
                isCheckable = false
                setChipIconResource(R.drawable.ic_history)
                isChipIconVisible = true
                chipIconSize = resources.displayMetrics.density * 18
                setOnClickListener { submitText(q) }
            })
        }
    }

    // ---------- copied link ----------

    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (hasFocus) checkClipboard()
    }

    /** Offers to open a YouTube link that was copied (e.g. from the YouTube app or a browser). */
    private fun checkClipboard() {
        if (_b == null || isHidden) return
        val ctx = context ?: return
        val settings = Settings(ctx)
        if (!settings.clipboardPrompt) return
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        val desc = cm.primaryClipDescription ?: return
        if (!desc.hasMimeType("text/*")) return
        // Read each copied text only once (reading shows Android's "pasted" notice).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (desc.timestamp == lastClipStamp) return
            lastClipStamp = desc.timestamp
        }
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull() ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        if (url == settings.lastClipboardLink) return
        if (YouTube.linkType(url) == YouTube.Link.NONE) return
        settings.lastClipboardLink = url
        if (b.input.text?.toString()?.contains(url) == true) return
        Snackbar.make(b.root, "You copied a YouTube link", Snackbar.LENGTH_LONG)
            .setAction("Open") { if (_b != null) submitText(url) }
            .show()
    }

    private fun paste() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()?.trim()
        if (text.isNullOrBlank()) toast("Clipboard is empty") else submitText(text)
    }

    // ---------- rendering ----------

    private var renderedList: Any? = null

    private fun render(state: GetState) {
        updateBack()
        b.progress.isVisible = state is GetState.Loading
        b.inputLayout.isEndIconVisible = state !is GetState.Loading
        b.emptyState.isVisible = state is GetState.Idle
        if (state is GetState.Idle) renderRecent()
        b.statusText.isVisible = state is GetState.Loading || state is GetState.Error
        b.statusText.text = when (state) {
            is GetState.Loading -> state.message
            is GetState.Error -> "Error: ${state.message}"
            else -> ""
        }

        // Reset header pieces; each state turns on what it needs.
        h.card.isVisible = false
        h.formatRow.isVisible = false
        h.qualityRow.isVisible = false
        h.limitRow.isVisible = false
        h.selectRow.isVisible = false
        h.rememberCheck.isVisible = false
        h.sizeText.isVisible = false
        h.calcSizeButton.isVisible = false
        h.downloadButton.isVisible = false
        h.openPlaylistButton.isVisible = false
        h.listTitle.isVisible = false
        footerAdapter.shown = false

        when (state) {
            is GetState.Video -> renderVideo(state)
            is GetState.Videos -> renderVideos(state)
            is GetState.Search -> renderSearch(state)
            else -> showList(emptyList(), selectable = false, key = null, resetScroll = true)
        }
    }

    private fun renderVideo(s: GetState.Video) {
        val d = s.details
        h.card.isVisible = true
        h.thumb.isVisible = true
        h.thumb.load(d.thumbnail) { crossfade(true) }
        h.titleText.text = d.title
        h.subtitleText.text = listOf(d.uploader, formatDuration(d.durationSec)).filter { it.isNotBlank() }.joinToString(" · ")
        showList(emptyList(), selectable = false, key = null, resetScroll = true)
        if (d.options.isEmpty()) {
            h.sizeText.isVisible = true
            h.sizeText.text = "No downloadable formats found for this video."
            return
        }
        if (renderedList !== d) {
            h.formatSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, d.options.map { it.displayLabel })
            val wanted = Settings(requireContext()).defaultQuality
            val def = YouTube.pickBest(d.options, wanted)?.let { d.options.indexOf(it) }?.takeIf { it >= 0 } ?: 0
            h.formatSpinner.setSelection(def)
            renderedList = d
        }
        h.formatRow.isVisible = true
        h.sizeText.isVisible = true
        h.downloadButton.isVisible = true
        h.downloadButton.text = "Download"
        h.openPlaylistButton.isVisible = s.playlistUrl != null
        updateSizeText()
    }

    private fun renderVideos(s: GetState.Videos) {
        val list = s.list
        h.card.isVisible = true
        h.thumb.isVisible = list.videos.firstOrNull()?.thumbnail != null
        h.thumb.load(list.videos.firstOrNull()?.thumbnail) { crossfade(true) }
        h.titleText.text = list.name
        val total = list.videos.sumOf { maxOf(it.durationSec, 0L) }
        h.subtitleText.text = (if (list.isChannel) "Channel · newest ${list.videos.size} videos" else "Playlist · ${list.videos.size} videos") +
            " · ${formatDuration(total)}"

        if (renderedList !== list) {
            val def = Settings(requireContext()).defaultQuality
            h.qualitySpinner.setSelection(qualities.indexOfFirst { it.second == def }.coerceAtLeast(0))
            if (list.isChannel) {
                h.limitSpinner.onItemSelectedListener = null
                h.limitSpinner.setSelection(GetViewModel.CHANNEL_LIMITS.indexOf(s.channelLimit).coerceAtLeast(0), false)
                h.limitSpinner.onItemSelectedListener = onSelected { pos ->
                    val limit = GetViewModel.CHANNEL_LIMITS[pos]
                    val cur = vm.state.value as? GetState.Videos
                    if (cur != null && cur.channelLimit != limit) vm.openChannel(cur.list.url, limit, push = false)
                }
            }
            renderedList = list
        }
        h.qualityRow.isVisible = true
        h.limitRow.isVisible = list.isChannel
        h.selectRow.isVisible = true
        h.selectedText.text = "${s.selected.size} of ${list.videos.size} selected"
        h.rememberCheck.isVisible = true
        h.rememberCheck.text = if (list.isChannel) "Keep for Sync (later, only new uploads)" else "Keep for Sync (later, only new videos)"
        h.sizeText.isVisible = true
        h.downloadButton.isVisible = true
        h.downloadButton.text = "Download ${s.selected.size} video(s)"
        h.listTitle.isVisible = true
        h.listTitle.text = "Tap a video to include or leave it out"
        showList(list.videos, selectable = true, key = list, resetScroll = true)
        videos.selected = s.selected
        updateSizeText()
    }

    private fun renderSearch(s: GetState.Search) {
        h.listTitle.isVisible = true
        h.listTitle.text = if (s.page.videos.isEmpty()) "No results for “${s.page.query}”" else "Results for “${s.page.query}”"
        // Loading more results keeps the scroll position; a new search starts at the top.
        showList(s.page.videos, selectable = false, key = s.page.videos, resetScroll = s.page.query != shownQuery, large = true)
        shownQuery = s.page.query
        footerAdapter.shown = s.page.next != null && s.page.videos.isNotEmpty()
        footer.moreButton.isEnabled = !s.loadingMore
        footer.moreButton.text = if (s.loadingMore) "Loading…" else "Load more results"
    }

    private var listKey: Any? = null
    private var shownQuery: String? = null

    private fun showList(items: List<VideoEntry>, selectable: Boolean, key: Any?, resetScroll: Boolean, large: Boolean = false) {
        videos.selectable = selectable
        videos.large = large
        if (key != null && key === listKey) return
        if (key !is List<*>) shownQuery = null
        listKey = key
        videos.submitList(items) { if (resetScroll) _b?.results?.scrollToPosition(0) }
    }

    private fun updateSizeText() {
        if (_b == null) return
        val free = Storage.freeBytes()
        when (val s = vm.state.value) {
            is GetState.Video -> {
                val opt = s.details.options.getOrNull(h.formatSpinner.selectedItemPosition) ?: return
                h.sizeText.text = sizeLine(opt.bytes, exact = true, free = free)
            }
            is GetState.Videos -> {
                val maxHeight = qualities[h.qualitySpinner.selectedItemPosition.coerceAtLeast(0)].second
                val info = vm.sizes.value
                val chosen = s.list.videos.filter { it.id in s.selected }
                if (info.running) {
                    h.sizeText.text = "Calculating size… ${info.checked} / ${chosen.size}"
                    h.calcSizeButton.isVisible = false
                    return
                }
                var total = 0L
                var unknown = 0
                chosen.forEach { v ->
                    val opt = info.resolved[v.id]?.let { YouTube.pickBest(it, maxHeight) }
                    if (opt == null || opt.bytes <= 0) unknown++ else total += opt.bytes
                }
                h.sizeText.text = when {
                    chosen.isEmpty() -> "Nothing selected"
                    unknown == chosen.size -> "Size not calculated yet   •   Free on phone: ${formatBytes(free)}"
                    else -> sizeLine(total, exact = unknown == 0, free = free) +
                        if (unknown > 0) "\n$unknown video(s) not checked or unavailable" else ""
                }
                h.calcSizeButton.isVisible = unknown > 0 && chosen.isNotEmpty()
            }
            else -> {}
        }
    }

    private fun sizeLine(bytes: Long, exact: Boolean, free: Long): String {
        val size = if (bytes > 0) (if (exact) "" else "at least ") + formatBytes(bytes) else "unknown"
        val warn = if (bytes > 0 && free in 0 until bytes * 2) "  ⚠ not enough space" else ""
        return "Data needed: $size   •   Free on phone: ${formatBytes(free)}$warn"
    }

    private fun download() {
        when (val s = vm.state.value) {
            is GetState.Video -> {
                val opt = s.details.options.getOrNull(h.formatSpinner.selectedItemPosition) ?: return
                vm.downloadVideo(opt)
            }
            is GetState.Videos -> {
                val maxHeight = qualities[h.qualitySpinner.selectedItemPosition.coerceAtLeast(0)].second
                vm.downloadSelected(maxHeight, h.rememberCheck.isChecked)
            }
            else -> {}
        }
    }

    /** Three-dots menu on a video in search results, playlists and channels. */
    private fun showVideoMenu(v: VideoEntry, anchor: View) {
        val ctx = requireContext()
        val default = Settings(ctx).defaultQuality
        val label = qualities.firstOrNull { it.second == default }?.first ?: "${default}p"
        PopupMenu(ctx, anchor).apply {
            if (default > 0) menu.add(0, 1, 0, "Download ($label)")
            menu.add(0, 2, 1, "Download audio only")
            if (Settings(ctx).audioFormat != "mp3") menu.add(0, 6, 1, "Download as MP3")
            menu.add(0, 3, 2, "Choose quality…")
            menu.add(0, 4, 3, "Share link")
            menu.add(0, 5, 4, "Copy link")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> vm.quickDownload(v, default)
                    2 -> vm.quickDownload(v, 0)
                    6 -> vm.quickDownload(v, 0, mp3 = true)
                    3 -> vm.openVideo(v.url)
                    4 -> startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, v.title)
                                .putExtra(Intent.EXTRA_TEXT, v.url),
                            "Share link"
                        )
                    )
                    5 -> {
                        ctx.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(v.title, v.url))
                        toast("Link copied")
                    }
                }
                true
            }
        }.show()
    }

    // ---------- helpers ----------

    private fun onSelected(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = action(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.input.windowToken, 0)
        b.input.clearFocus()
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        view?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
        super.onDestroyView()
        b.results.adapter = null
        renderedList = null
        listKey = null
        shownQuery = null
        _b = null
    }
}

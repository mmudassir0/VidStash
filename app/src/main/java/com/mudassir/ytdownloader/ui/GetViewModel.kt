package com.mudassir.ytdownloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mudassir.ytdownloader.DownloadItem
import com.mudassir.ytdownloader.VideoEntry
import com.mudassir.ytdownloader.DownloadOption
import com.mudassir.ytdownloader.Kind
import com.mudassir.ytdownloader.Queue
import com.mudassir.ytdownloader.SearchPage
import com.mudassir.ytdownloader.Settings
import com.mudassir.ytdownloader.Sync
import com.mudassir.ytdownloader.VideoDetails
import com.mudassir.ytdownloader.VideoList
import com.mudassir.ytdownloader.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What the Get screen is showing. */
sealed class GetState {
    data object Idle : GetState()
    data class Loading(val message: String) : GetState()
    data class Error(val message: String) : GetState()
    data class Video(val details: VideoDetails, val url: String, val playlistUrl: String?) : GetState()
    data class Videos(val list: VideoList, val selected: Set<String>, val channelLimit: Int) : GetState()
    data class Search(val page: SearchPage, val loadingMore: Boolean = false) : GetState()
}

/** Exact sizes for the videos of a playlist/channel, filled in on request. */
data class SizeInfo(val resolved: Map<String, List<DownloadOption>>, val checked: Int, val running: Boolean)

class GetViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    private val _state = MutableStateFlow<GetState>(GetState.Idle)
    val state: StateFlow<GetState> = _state.asStateFlow()

    private val _sizes = MutableStateFlow(SizeInfo(emptyMap(), 0, false))
    val sizes: StateFlow<SizeInfo> = _sizes.asStateFlow()

    /** One-off messages for a toast. */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun toastShown() { _toast.value = null }

    /** Screens to go back to (e.g. search results after opening a video). */
    private val back = ArrayDeque<GetState>()
    val canGoBack get() = back.isNotEmpty()

    private var loadJob: Job? = null
    private var sizeJob: Job? = null

    fun submit(text: String) {
        val input = text.trim()
        if (input.isEmpty()) return
        back.clear()
        val url = Regex("https?://\\S+").find(input)?.value
        if (url == null) {
            search(input)
            return
        }
        when (YouTube.linkType(url)) {
            YouTube.Link.VIDEO -> openVideo(url, push = false)
            YouTube.Link.PLAYLIST -> openPlaylist(url, push = false)
            YouTube.Link.CHANNEL -> openChannel(url, DEFAULT_CHANNEL_LIMIT, push = false)
            YouTube.Link.NONE -> _state.value = GetState.Error("That isn't a YouTube video, playlist or channel link.")
        }
    }

    fun goBack(): Boolean {
        val prev = back.removeLastOrNull() ?: return false
        loadJob?.cancel()
        sizeJob?.cancel()
        _state.value = prev
        return true
    }

    private fun load(push: Boolean, message: String, block: suspend () -> GetState) {
        val current = _state.value
        if (push && (current is GetState.Search || current is GetState.Videos || current is GetState.Video)) {
            back.addLast(current)
        }
        loadJob?.cancel()
        sizeJob?.cancel()
        _sizes.value = SizeInfo(emptyMap(), 0, false)
        _state.value = GetState.Loading(message)
        loadJob = viewModelScope.launch {
            _state.value = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                GetState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun openVideo(url: String, push: Boolean = true) = load(push, "Fetching video…") {
        GetState.Video(YouTube.getVideo(url, Settings(ctx).allowWebm), url, YouTube.playlistOf(url))
    }

    fun openPlaylist(url: String, push: Boolean = true) = load(push, "Loading playlist…") {
        val list = YouTube.getPlaylist(url) { n -> _state.value = GetState.Loading("Loading playlist… $n videos") }
        GetState.Videos(list, list.videos.map { it.id }.toSet(), 0)
    }

    fun openChannel(url: String, limit: Int, push: Boolean = true) = load(push, "Loading channel…") {
        val list = YouTube.getChannel(url, limit) { n -> _state.value = GetState.Loading("Loading channel… $n videos") }
        GetState.Videos(list, list.videos.map { it.id }.toSet(), limit)
    }

    private fun search(query: String) = load(false, "Searching…") {
        Settings(ctx).addSearch(query)
        GetState.Search(YouTube.search(query))
    }

    fun loadMoreResults() {
        val s = _state.value as? GetState.Search ?: return
        if (s.loadingMore || s.page.next == null) return
        _state.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            val more = runCatching { withContext(Dispatchers.IO) { YouTube.searchMore(s.page) } }.getOrNull()
            val now = _state.value as? GetState.Search ?: return@launch
            _state.value = now.copy(page = more ?: now.page.copy(next = null), loadingMore = false)
        }
    }

    // ---- selection (playlist / channel) ----

    fun toggle(id: String) = updateVideos { s ->
        s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }

    fun selectAll(all: Boolean) = updateVideos { s ->
        s.copy(selected = if (all) s.list.videos.map { it.id }.toSet() else emptySet())
    }

    private fun updateVideos(change: (GetState.Videos) -> GetState.Videos) {
        val s = _state.value as? GetState.Videos ?: return
        _state.value = change(s)
    }

    /** Looks up every selected video (4 at a time) to get exact file sizes. */
    fun calculateSizes() {
        val s = _state.value as? GetState.Videos ?: return
        sizeJob?.cancel()
        val todo = s.list.videos.filter { it.id in s.selected && it.id !in _sizes.value.resolved }
        _sizes.value = _sizes.value.copy(checked = 0, running = true)
        sizeJob = viewModelScope.launch(Dispatchers.IO) {
            val gate = Semaphore(4)
            todo.map { v ->
                launch {
                    gate.withPermit {
                        val opts = runCatching { YouTube.getVideo(v.url, Settings(ctx).allowWebm).options }.getOrNull()
                        synchronized(this@GetViewModel) {
                            val cur = _sizes.value
                            _sizes.value = cur.copy(
                                resolved = if (opts != null) cur.resolved + (v.id to opts) else cur.resolved,
                                checked = cur.checked + 1
                            )
                        }
                    }
                }
            }.forEach { it.join() }
            _sizes.value = _sizes.value.copy(running = false)
        }
    }

    // ---- downloads ----

    /** [clip] = (start, end) in seconds to save only part of the video. */
    fun downloadVideo(option: DownloadOption, clip: Pair<Long, Long>? = null, split: Boolean = false) {
        val s = _state.value as? GetState.Video ?: return
        val d = s.details
        Queue.add(
            ctx, listOf(
                DownloadItem(
                    url = s.url, title = d.title, thumbnail = d.thumbnail,
                    durationSec = clip?.let { it.second - it.first } ?: d.durationSec,
                    uploader = d.uploader.ifBlank { null },
                    clipStartSec = clip?.first ?: -1, clipEndSec = clip?.second ?: -1,
                    splitChapters = split,
                    maxHeight = if (option.kind == Kind.AUDIO) 0 else option.height,
                    // Pre-select the exact format chosen in the list.
                    videoItag = option.videoItag, audioItag = option.audioItag
                )
            )
        )
        _toast.value = when {
            split -> "Added: will be saved as ${d.chapters.size} chapter files"
            clip != null -> "Added the section to downloads"
            else -> "Added to downloads"
        }
    }

    /** Queues one video straight from a list (three-dots menu). [maxHeight] 0 = audio only. */
    fun quickDownload(v: VideoEntry, maxHeight: Int, mp3: Boolean = false) {
        Queue.add(ctx, listOf(DownloadItem(
            url = v.url, title = v.title, thumbnail = v.thumbnail, maxHeight = maxHeight,
            durationSec = v.durationSec, uploader = v.uploader.ifBlank { null }, mp3 = mp3
        )))
        _toast.value = when {
            mp3 -> "Added to downloads (MP3)"
            maxHeight == 0 -> "Added to downloads (audio)"
            else -> "Added to downloads"
        }
    }

    fun downloadSelected(maxHeight: Int, remember: Boolean) {
        val s = _state.value as? GetState.Videos ?: return
        val chosen = s.list.videos.filter { it.id in s.selected }
        if (chosen.isEmpty()) {
            _toast.value = "Select at least one video"
            return
        }
        Sync.queue(ctx, s.list, chosen, maxHeight, remember)
        _toast.value = "Queued ${chosen.size} video(s)" + if (remember) " · saved for Sync" else ""
    }

    companion object {
        const val DEFAULT_CHANNEL_LIMIT = 25
        val CHANNEL_LIMITS = listOf(10, 25, 50, 100, 250)
    }
}

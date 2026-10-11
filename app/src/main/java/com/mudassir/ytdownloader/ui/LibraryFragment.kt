package com.mudassir.ytdownloader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mudassir.ytdownloader.DownloadItem
import com.mudassir.ytdownloader.R
import com.mudassir.ytdownloader.SavedPlaylist
import com.mudassir.ytdownloader.Settings
import com.mudassir.ytdownloader.Status
import com.mudassir.ytdownloader.Storage
import com.mudassir.ytdownloader.Store
import com.mudassir.ytdownloader.Sync
import com.mudassir.ytdownloader.databinding.FragmentLibraryBinding
import com.mudassir.ytdownloader.databinding.ItemPlaylistBinding
import com.mudassir.ytdownloader.databinding.ItemVideoBinding
import com.mudassir.ytdownloader.formatBytes
import com.mudassir.ytdownloader.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Downloaded files (search, sort, filter by folder, select several to play, share or
 * delete) and playlists/channels kept for Sync.
 */
class LibraryFragment : Fragment() {

    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!
    private val files = FileAdapter()
    private val lists = ListsAdapter()
    private val syncing = mutableSetOf<String>()

    private var query = ""
    /** Folder filter: [ALL], [SINGLES] or a folder name. */
    private var folder = ALL
    private var shownFolders: List<String>? = null
    private val selected = linkedSetOf<String>()
    /** Files currently shown, in order (what "play" plays through). */
    private var visible: List<DownloadItem> = emptyList()

    private val selecting get() = selected.isNotEmpty()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = clearSelection()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) clearSelection()
        updateBack()
    }

    private fun updateBack() {
        backCallback.isEnabled = selecting && !isHidden
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentLibraryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.tabs.addOnButtonCheckedListener { _, _, _ ->
            clearSelection()
            render()
        }
        b.syncAllButton.setOnClickListener { syncAll() }

        b.searchInput.doAfterTextChanged {
            query = it?.toString()?.trim().orEmpty()
            render()
        }
        b.sortButton.setOnClickListener { showSortMenu() }
        b.folderChips.setOnCheckedStateChangeListener { group, ids ->
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            folder = chip.tag as? String ?: ALL
            render()
        }

        b.closeSelection.setOnClickListener { clearSelection() }
        b.selectAllButton.setOnClickListener {
            if (selected.size == visible.size) selected.clear() else selected.addAll(visible.map { it.id })
            selectionChanged()
        }
        b.playSelected.setOnClickListener {
            val items = visible.filter { it.id in selected }
            clearSelection()
            Files.playList(requireContext(), items, 0)
        }
        b.shareSelected.setOnClickListener {
            Files.shareMany(requireContext(), visible.filter { it.id in selected })
        }
        b.deleteSelected.setOnClickListener { confirmDelete(visible.filter { it.id in selected }) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Store.items.combine(Store.playlists) { _, _ -> }.collect { render() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render() // refresh "continue at" positions after using the player
    }

    private val showFiles get() = b.tabs.checkedButtonId == R.id.filesTab

    private fun render() {
        if (_b == null) return
        val showFiles = showFiles
        b.syncAllButton.isVisible = !showFiles && Store.playlists.value.isNotEmpty()
        b.filesHeader.isVisible = showFiles && !selecting
        b.selectionBar.isVisible = showFiles && selecting
        if (showFiles) renderFiles() else renderLists()
    }

    private fun renderFiles() {
        if (b.list.adapter !== files) b.list.adapter = files
        val all = Store.items.value.filter { it.status == Status.DONE && it.fileUri != null }

        val used = all.sumOf { it.bytes.coerceAtLeast(0) }
        val free = Storage.freeBytes()
        b.storageText.text = listOfNotNull(
            "${all.size} file" + if (all.size == 1) "" else "s",
            "${formatBytes(used)} used",
            if (free >= 0) "${formatBytes(free)} free on phone" else null
        ).joinToString(" · ")
        b.storageText.isVisible = all.isNotEmpty()

        renderFolderChips(all)

        val q = query.lowercase()
        val filtered = all.asSequence()
            .filter {
                when (folder) {
                    ALL -> true
                    SINGLES -> it.folder == null
                    else -> it.folder == folder
                }
            }
            .filter {
                q.isEmpty() || it.title.lowercase().contains(q) ||
                    it.uploader?.lowercase()?.contains(q) == true ||
                    it.folder?.lowercase()?.contains(q) == true
            }
            .toList()
        visible = sorted(filtered)
        selected.retainAll(visible.map { it.id }.toSet())

        files.submitList(visible)
        files.notifyItemRangeChanged(0, visible.size)
        b.emptyText.text = if (all.isEmpty()) "Finished downloads show up here. Tap one to play it."
        else "No files match."
        b.emptyText.isVisible = visible.isEmpty()
        b.searchLayout.isVisible = all.isNotEmpty()
        b.sortButton.isVisible = all.isNotEmpty()
        updateSelectionBar()
    }

    private fun sorted(items: List<DownloadItem>): List<DownloadItem> = when (Settings(requireContext()).librarySort) {
        "oldest" -> items.sortedBy { it.finishedAt }
        "name" -> items.sortedWith(compareBy<DownloadItem> { it.folder.orEmpty().lowercase() }.thenBy { it.fileName ?: it.title.lowercase() })
        "size" -> items.sortedByDescending { it.bytes }
        "length" -> items.sortedByDescending { it.durationSec }
        else -> items.sortedByDescending { it.finishedAt }
    }

    private fun showSortMenu() {
        val ctx = requireContext()
        val current = Settings(ctx).librarySort
        PopupMenu(ctx, b.sortButton).apply {
            SORTS.forEachIndexed { i, (key, label) ->
                menu.add(0, i, i, (if (key == current) "✓  " else "     ") + label)
            }
            setOnMenuItemClickListener {
                Settings(ctx).librarySort = SORTS[it.itemId].first
                render()
                b.list.scrollToPosition(0)
                true
            }
        }.show()
    }

    /** "All", one chip per playlist/channel folder, and "Single videos". Rebuilt only when folders change. */
    private fun renderFolderChips(all: List<DownloadItem>) {
        val folders = all.mapNotNull { it.folder }.distinct().sortedBy { it.lowercase() }
        val hasSingles = all.any { it.folder == null }
        val keys = buildList {
            add(ALL)
            addAll(folders)
            if (hasSingles && folders.isNotEmpty()) add(SINGLES)
        }
        if (folder !in keys) folder = ALL
        b.folderScroll.isVisible = folders.isNotEmpty()
        if (keys == shownFolders) return
        shownFolders = keys
        b.folderChips.removeAllViews()
        keys.forEach { key ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                tag = key
                text = when (key) {
                    ALL -> "All"
                    SINGLES -> "Single videos"
                    else -> key
                }
                isCheckable = true
                isCheckedIconVisible = false
            }
            b.folderChips.addView(chip)
            if (key == folder) chip.isChecked = true
        }
    }

    private fun renderLists() {
        if (b.list.adapter !== lists) b.list.adapter = lists
        val pls = Store.playlists.value.sortedBy { it.name.lowercase() }
        lists.submitList(pls)
        lists.notifyItemRangeChanged(0, pls.size)
        b.emptyText.text = "When you download a playlist or channel with “Keep for Sync” ticked, it appears here. " +
            "Sync fetches only the videos you don't have yet."
        b.emptyText.isVisible = pls.isEmpty()
    }

    // ---------- selection ----------

    private fun toggle(item: DownloadItem) {
        if (!selected.remove(item.id)) selected.add(item.id)
        selectionChanged()
    }

    private fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        selectionChanged()
    }

    private fun selectionChanged() {
        updateBack()
        if (_b == null) return
        b.filesHeader.isVisible = showFiles && !selecting
        b.selectionBar.isVisible = showFiles && selecting
        updateSelectionBar()
        files.notifyItemRangeChanged(0, files.itemCount)
    }

    private fun updateSelectionBar() {
        if (!selecting) return
        val items = visible.filter { it.id in selected }
        val bytes = items.sumOf { it.bytes.coerceAtLeast(0) }
        b.selectionText.text = "${items.size} selected" + if (bytes > 0) " · ${formatBytes(bytes)}" else ""
    }

    private fun confirmDelete(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        val ctx = requireContext()
        val bytes = items.sumOf { it.bytes.coerceAtLeast(0) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (items.size == 1) "Delete this file?" else "Delete ${items.size} files?")
            .setMessage(
                if (items.size == 1) items[0].fileName ?: items[0].title
                else "This frees ${formatBytes(bytes)} on your phone."
            )
            .setPositiveButton("Delete") { _, _ ->
                Files.deleteMany(ctx, items)
                clearSelection()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- sync ----------

    private fun sync(p: SavedPlaylist) {
        val ctx = requireContext().applicationContext
        syncing += p.url
        render()
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { Sync.sync(ctx, p) } }
            syncing -= p.url
            render()
            val msg = result.fold(
                { n -> if (n == 0) "${p.name}: nothing new" else "${p.name}: $n new video(s) queued" },
                { e -> "${p.name}: sync failed (${e.message})" }
            )
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncAll() {
        val ctx = requireContext().applicationContext
        b.syncAllButton.isEnabled = false
        lifecycleScope.launch {
            val (added, errors) = withContext(Dispatchers.IO) { Sync.syncAll(ctx) }
            _b?.syncAllButton?.isEnabled = true
            val msg = (if (added == 0) "Nothing new" else "$added new video(s) queued") +
                if (errors > 0) " · $errors list(s) failed" else ""
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        b.list.adapter = null
        shownFolders = null
        _b = null
    }

    // ---------- adapters ----------

    inner class FileAdapter : ListAdapter<DownloadItem, FileAdapter.Holder>(ITEM_DIFF) {
        inner class Holder(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val b = holder.b
            val ctx = b.root.context
            b.title.text = item.title
            b.thumb.load(item.thumbnail) { crossfade(true) }
            b.duration.text = if (item.durationSec > 0) formatDuration(item.durationSec) else ""
            b.duration.isVisible = item.durationSec > 0
            val pos = Settings(ctx).position(item.fileUri!!)
            b.meta.text = listOfNotNull(
                if (item.bytes > 0) formatBytes(item.bytes) else null,
                if (item.mime?.startsWith("audio") == true) "Audio" else null,
                item.folder ?: item.uploader,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.finishedAt)),
                if (pos > 5_000) "▶ continue at ${formatDuration(pos / 1000)}" else null
            ).joinToString(" · ")
            b.meta.isVisible = true
            b.check.isVisible = selecting
            b.check.isChecked = item.id in selected
            b.more.isVisible = !selecting
            b.root.setOnClickListener {
                if (selecting) toggle(item)
                else Files.playList(ctx, visible, visible.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
            }
            b.root.setOnLongClickListener {
                toggle(item)
                true
            }
            b.more.setOnClickListener { v -> showFileMenu(item, v) }
        }
    }

    private fun showFileMenu(item: DownloadItem, anchor: View) {
        val ctx = requireContext()
        PopupMenu(ctx, anchor).apply {
            menu.add(0, 1, 0, "Play")
            menu.add(0, 2, 1, "Open with…")
            menu.add(0, 3, 2, "Share")
            menu.add(0, 4, 3, "Select")
            menu.add(0, 5, 4, "Delete file")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> Files.play(ctx, item)
                    2 -> Files.openWith(ctx, item)
                    3 -> Files.share(ctx, item)
                    4 -> toggle(item)
                    5 -> confirmDelete(listOf(item))
                }
                true
            }
        }.show()
    }

    inner class ListsAdapter : ListAdapter<SavedPlaylist, ListsAdapter.Holder>(LIST_DIFF) {
        inner class Holder(val b: ItemPlaylistBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val p = getItem(position)
            val b = holder.b
            val ctx = b.root.context
            val quality = Settings.QUALITIES.firstOrNull { it.second == p.maxHeight }?.first ?: "${p.maxHeight}p"
            b.title.text = p.name
            b.meta.text = listOf(
                if (p.isChannel) "Channel" else "Playlist",
                "${p.seen.size} videos known",
                quality,
                "synced " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.lastSync))
            ).joinToString(" · ")
            val busy = p.url in syncing
            b.syncButton.isEnabled = !busy
            b.syncButton.text = if (busy) "Syncing…" else "Sync"
            b.syncButton.setOnClickListener { sync(p) }
            b.moreButton.setOnClickListener { v ->
                PopupMenu(ctx, v).apply {
                    menu.add(0, 1, 0, "Show downloaded files")
                    menu.add(0, 2, 1, "Stop syncing this")
                    setOnMenuItemClickListener {
                        when (it.itemId) {
                            1 -> showFolder(p.name)
                            2 -> Store.removePlaylist(p.url)
                        }
                        true
                    }
                }.show()
            }
        }
    }

    /** Switches to Files, filtered to one playlist/channel folder. */
    private fun showFolder(name: String) {
        folder = name
        shownFolders = null // rebuild chips so the right one is checked
        b.tabs.check(R.id.filesTab)
        render()
    }

    companion object {
        private const val ALL = "\u0000all"
        private const val SINGLES = "\u0000singles"
        private val SORTS = listOf(
            "date" to "Newest first",
            "oldest" to "Oldest first",
            "name" to "Name (playlist order)",
            "size" to "Largest first",
            "length" to "Longest first"
        )
        private val ITEM_DIFF = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(a: DownloadItem, b: DownloadItem) = a.id == b.id
            override fun areContentsTheSame(a: DownloadItem, b: DownloadItem) = a == b
        }
        private val LIST_DIFF = object : DiffUtil.ItemCallback<SavedPlaylist>() {
            override fun areItemsTheSame(a: SavedPlaylist, b: SavedPlaylist) = a.url == b.url
            override fun areContentsTheSame(a: SavedPlaylist, b: SavedPlaylist) = a == b
        }
    }
}

package com.mudassir.ytdownloader.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.mudassir.ytdownloader.R
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mudassir.ytdownloader.DownloadItem
import com.mudassir.ytdownloader.MainActivity
import com.mudassir.ytdownloader.Progress
import com.mudassir.ytdownloader.Queue
import com.mudassir.ytdownloader.Settings
import com.mudassir.ytdownloader.Status
import com.mudassir.ytdownloader.Store
import com.mudassir.ytdownloader.databinding.FragmentDownloadsBinding
import com.mudassir.ytdownloader.databinding.ItemDownloadBinding
import com.mudassir.ytdownloader.formatBytes
import com.mudassir.ytdownloader.formatDuration
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The queue and download history, with skip / retry / remove / play. */
class DownloadsFragment : Fragment() {

    private var _b: FragmentDownloadsBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: Adapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentDownloadsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = Adapter()
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter
        b.list.itemAnimator = null // progress updates twice a second; no blinking

        b.troubleDismiss.setOnClickListener {
            Settings(requireContext()).youtubeTrouble = 0
            b.troubleCard.isVisible = false
        }
        b.troubleCheck.setOnClickListener {
            (activity as? MainActivity)?.openUpdates()
        }
        b.pauseButton.setOnClickListener {
            val ctx = requireContext()
            if (Settings(ctx).paused) Queue.resumeAll(ctx) else Queue.pauseAll(ctx)
            render(Store.items.value, Store.progress.value)
        }
        b.menuButton.setOnClickListener { v ->
            PopupMenu(requireContext(), v).apply {
                menu.add(0, 1, 0, "Retry failed & skipped")
                menu.add(0, 2, 1, "Clear finished from list")
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> Queue.retryAllFailed(requireContext())
                        2 -> Queue.clearFinished(requireContext())
                    }
                    true
                }
            }.show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Store.items.combine(Store.progress) { items, prog -> items to prog }
                    .collect { (items, prog) -> render(items, prog) }
            }
        }
    }

    private fun render(items: List<DownloadItem>, prog: Map<String, Progress>) {
        if (_b == null) return
        val ctx = requireContext()
        val paused = Settings(ctx).paused
        // Active first (in queue order), then finished, newest first.
        val sorted = items.filter { it.isActive } + items.filter { !it.isActive }.sortedByDescending { it.finishedAt }
        adapter.submitList(sorted.map { Row(it, prog[it.id]) })

        val running = items.count { it.status == Status.RUNNING }
        val queued = items.count { it.status == Status.QUEUED }
        val failed = items.count { it.status == Status.FAILED }
        val speed = prog.values.sumOf { it.speed }
        val parts = mutableListOf<String>()
        when {
            paused && (running + queued) > 0 -> parts += "Paused"
            running > 0 -> parts += "Downloading $running" + if (speed > 0) " · ${formatBytes(speed)}/s" else ""
            queued > 0 && Settings(ctx).wifiOnly && !onWifi(ctx) -> parts += "Waiting for Wi-Fi"
            queued > 0 -> parts += "Starting…"
        }
        if (queued > 0) parts += "$queued waiting"
        if (failed > 0) parts += "$failed failed"
        b.summaryText.text = parts.joinToString(" · ")
        b.summaryText.isVisible = parts.isNotEmpty()
        b.pauseButton.text = if (paused) "Resume" else "Pause"
        b.pauseButton.setIconResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        b.pauseButton.isVisible = paused || running + queued > 0
        b.emptyText.isVisible = items.isEmpty()
        val trouble = Settings(ctx).youtubeTrouble
        b.troubleCard.isVisible = trouble > 0 && System.currentTimeMillis() - trouble < 7 * 86_400_000L
    }

    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    data class Row(val item: DownloadItem, val progress: Progress?)

    inner class Adapter : ListAdapter<Row, Adapter.Holder>(DIFF) {
        inner class Holder(val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (item, p) = getItem(position)
            val b = holder.b
            val ctx = b.root.context
            b.title.text = item.title
            if (b.thumb.tag != item.thumbnail) {
                b.thumb.tag = item.thumbnail
                b.thumb.load(item.thumbnail) { crossfade(true) }
            }

            b.status.text = statusText(item, p)
            b.status.setTextColor(
                if (item.status == Status.FAILED) ContextCompat.getColor(ctx, R.color.brand_red)
                else ContextCompat.getColor(ctx, R.color.on_bg_muted)
            )
            // Badge on the thumbnail: progress while downloading, otherwise length or state.
            val pct = p?.percent ?: -1
            val badge = when (item.status) {
                Status.RUNNING -> if (pct >= 0) "$pct%" else null
                Status.QUEUED -> "Waiting"
                Status.FAILED -> "Failed"
                Status.SKIPPED -> "Skipped"
                Status.DONE -> if (item.durationSec > 0) formatDuration(item.durationSec) else null
            }
            b.badge.text = badge
            b.badge.isVisible = badge != null
            b.dim.isVisible = item.status != Status.DONE && item.status != Status.RUNNING
            b.bar.isVisible = item.status == Status.RUNNING
            if (item.status == Status.RUNNING) {
                val pct = p?.percent ?: -1
                if (pct < 0 || p?.stage?.endsWith("…") == true) {
                    if (!b.bar.isIndeterminate) {
                        b.bar.visibility = View.INVISIBLE
                        b.bar.isIndeterminate = true
                        b.bar.visibility = View.VISIBLE
                    }
                } else {
                    b.bar.isIndeterminate = false
                    b.bar.setProgressCompat(pct, false)
                }
            }

            // A video split into chapter files has no single file to play.
            b.actionButton.isVisible = !(item.status == Status.DONE && item.fileUri == null)
            when (item.status) {
                Status.RUNNING, Status.QUEUED -> button(b, "Skip", R.drawable.ic_close) { Queue.skip(item.id) }
                Status.FAILED, Status.SKIPPED -> button(b, "Retry", R.drawable.ic_retry) { Queue.retry(ctx, item.id) }
                Status.DONE -> button(b, "Play", R.drawable.ic_play) { Files.play(ctx, item) }
            }

            b.root.setOnClickListener { if (item.status == Status.DONE) Files.play(ctx, item) }
            b.moreButton.setOnClickListener { v ->
                PopupMenu(ctx, v).apply {
                    if (item.status == Status.DONE) {
                        menu.add(0, 1, 0, "Open with…")
                        menu.add(0, 2, 1, "Share")
                    }
                    menu.add(0, 3, 2, "Remove from list")
                    setOnMenuItemClickListener {
                        when (it.itemId) {
                            1 -> Files.openWith(ctx, item)
                            2 -> Files.share(ctx, item)
                            3 -> Queue.remove(ctx, item.id)
                        }
                        true
                    }
                }.show()
            }
        }

        private fun button(b: ItemDownloadBinding, label: String, icon: Int, action: () -> Unit) {
            b.actionButton.setIconResource(icon)
            b.actionButton.contentDescription = label
            b.actionButton.setOnClickListener { action() }
        }

        private fun statusText(item: DownloadItem, p: Progress?): String = when (item.status) {
            Status.QUEUED -> item.error ?: "Waiting"
            Status.RUNNING -> when {
                p == null -> "Starting…"
                p.stage.endsWith("…") -> p.stage
                else -> buildList {
                    add(if (p.total > 0) "${formatBytes(p.done)} / ${formatBytes(p.total)}" else formatBytes(p.done))
                    if (p.speed > 0) add("${formatBytes(p.speed)}/s")
                    if (p.etaSec >= 0) add("${formatDuration(p.etaSec)} left")
                    add(p.stage)
                }.joinToString(" · ")
            }
            Status.DONE -> listOfNotNull(
                if (item.bytes > 0) formatBytes(item.bytes) else null,
                item.folder,
                item.error // notes like "Removed 2 sponsor segment(s)" or "Already downloaded"
            ).joinToString(" · ").ifEmpty { "Saved" }
            Status.FAILED -> "Failed: ${item.error ?: "unknown error"}"
            Status.SKIPPED -> "Skipped"
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.item.id == b.item.id
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}

package com.mudassir.ytdownloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mudassir.ytdownloader.VideoEntry
import com.mudassir.ytdownloader.databinding.ItemVideoBinding
import com.mudassir.ytdownloader.databinding.ItemVideoLargeBinding
import com.mudassir.ytdownloader.formatDuration

/**
 * Wraps a single, already-inflated view (a header or footer) so it can sit in a ConcatAdapter.
 *
 * RecyclerView may briefly keep an old row while creating a new one for the same position
 * (e.g. when the footer is hidden and shown again in one frame). Each row therefore gets its
 * own container and the shared view moves into whichever row is being shown, so the same view
 * is never attached to the list twice.
 */
class SingleViewAdapter(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var shown = true
        set(v) {
            if (field == v) return
            field = v
            if (v) notifyItemInserted(0) else notifyItemRemoved(0)
        }

    private val type = View.generateViewId()

    override fun getItemCount() = if (shown) 1 else 0
    override fun getItemViewType(position: Int) = type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val box = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return object : RecyclerView.ViewHolder(box) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val box = holder.itemView as FrameLayout
        if (view.parent !== box) {
            (view.parent as? ViewGroup)?.removeView(view)
            box.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}

/**
 * Videos from a playlist, channel or search. Search results use big cards ([large]);
 * playlists and channels use compact rows with checkboxes ([selectable]).
 */
class VideoEntryAdapter(
    private val onClick: (VideoEntry) -> Unit,
    /** Three-dots button; gets the button so a menu can be anchored to it. */
    private val onMore: (VideoEntry, View) -> Unit
) : ListAdapter<VideoEntry, VideoEntryAdapter.Holder>(DIFF) {

    var selectable = false
        set(v) {
            if (field != v) { field = v; notifyItemRangeChanged(0, itemCount) }
        }
    var large = false
        set(v) {
            if (field != v) { field = v; notifyItemRangeChanged(0, itemCount) }
        }
    var selected: Set<String> = emptySet()
        set(v) {
            if (field != v) { field = v; notifyItemRangeChanged(0, itemCount, PAYLOAD_CHECK) }
        }

    /** Same views in both layouts. */
    class Holder(
        root: View,
        val thumb: ImageView,
        val duration: TextView,
        val title: TextView,
        val meta: TextView,
        val check: CheckBox,
        val more: View
    ) : RecyclerView.ViewHolder(root)

    override fun getItemViewType(position: Int) = if (large) TYPE_LARGE else TYPE_COMPACT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_LARGE) {
            val b = ItemVideoLargeBinding.inflate(inf, parent, false)
            Holder(b.root, b.thumb, b.duration, b.title, b.meta, b.check, b.more)
        } else {
            val b = ItemVideoBinding.inflate(inf, parent, false)
            Holder(b.root, b.thumb, b.duration, b.title, b.meta, b.check, b.more)
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_CHECK)) {
            holder.check.isChecked = getItem(position).id in selected
        } else super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val v = getItem(position)
        holder.title.text = v.title
        holder.meta.text = v.uploader
        holder.meta.isVisible = v.uploader.isNotBlank()
        // Live streams and unknown lengths come as 0 or -1: no badge then.
        holder.duration.text = if (v.durationSec > 0) formatDuration(v.durationSec) else ""
        holder.duration.isVisible = v.durationSec > 0
        holder.thumb.load(v.thumbnail) { crossfade(true) }
        holder.check.isVisible = selectable
        holder.check.isChecked = v.id in selected
        holder.itemView.setOnClickListener { onClick(v) }
        holder.more.setOnClickListener { onMore(v, it) }
    }

    companion object {
        private const val PAYLOAD_CHECK = "check"
        private const val TYPE_COMPACT = 1
        private const val TYPE_LARGE = 2
        private val DIFF = object : DiffUtil.ItemCallback<VideoEntry>() {
            override fun areItemsTheSame(a: VideoEntry, b: VideoEntry) = a.id == b.id
            override fun areContentsTheSame(a: VideoEntry, b: VideoEntry) = a == b
        }
    }
}

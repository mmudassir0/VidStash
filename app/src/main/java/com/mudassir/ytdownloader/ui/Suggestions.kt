package com.mudassir.ytdownloader.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mudassir.ytdownloader.R
import com.mudassir.ytdownloader.Settings
import com.mudassir.ytdownloader.YouTube

/**
 * Drop-down under the search box: matching recent searches first (clock icon),
 * then what YouTube suggests (search icon). Links get no suggestions.
 */
class SuggestionAdapter(private val ctx: Context) : BaseAdapter(), Filterable {

    data class Suggestion(val text: String, val fromHistory: Boolean)

    private var items: List<Suggestion> = emptyList()

    override fun getCount() = items.size
    override fun getItem(position: Int): String = items[position].text
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView as? TextView ?: LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_dropdown_item_1line, parent, false) as TextView
        val s = items[position]
        v.text = s.text
        val icon = ContextCompat.getDrawable(ctx, if (s.fromHistory) R.drawable.ic_history else R.drawable.ic_search)
        val size = (20 * ctx.resources.displayMetrics.density).toInt()
        icon?.setBounds(0, 0, size, size)
        v.setCompoundDrawablesRelative(icon, null, null, null)
        v.compoundDrawablePadding = (16 * ctx.resources.displayMetrics.density).toInt()
        return v
    }

    override fun getFilter() = object : Filter() {
        // Runs on a background thread, so the network call is fine here.
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val q = constraint?.toString()?.trim().orEmpty()
            val settings = Settings(ctx)
            val history = if (settings.searchHistory) settings.recentSearches else emptyList()
            val result = mutableListOf<Suggestion>()
            if (q.contains("://") || q.contains("youtu")) return FilterResults()
            history.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
                .take(if (q.isEmpty()) 10 else 4)
                .forEach { result += Suggestion(it, true) }
            if (q.length >= 2) {
                YouTube.suggestions(q)
                    .filter { s -> result.none { it.text.equals(s, ignoreCase = true) } }
                    .take(8)
                    .forEach { result += Suggestion(it, false) }
            }
            return FilterResults().apply {
                values = result
                count = result.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            items = (results?.values as? List<Suggestion>).orEmpty()
            if (items.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
        }

        override fun convertResultToString(resultValue: Any?): CharSequence =
            (resultValue as? String) ?: (resultValue as? Suggestion)?.text ?: ""
    }
}

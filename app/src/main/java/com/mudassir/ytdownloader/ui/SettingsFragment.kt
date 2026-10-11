package com.mudassir.ytdownloader.ui

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.mudassir.ytdownloader.Queue
import com.mudassir.ytdownloader.R
import com.mudassir.ytdownloader.Settings
import com.mudassir.ytdownloader.Sync
import com.mudassir.ytdownloader.UpdateManager
import com.mudassir.ytdownloader.Updater
import com.mudassir.ytdownloader.databinding.ViewSettingsHeaderBinding
import kotlinx.coroutines.launch

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var card: ViewSettingsHeaderBinding? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        findPreference<Preference>(Settings.K_CLEAR_HISTORY)?.setOnPreferenceClickListener {
            Settings(requireContext()).recentSearches = emptyList()
            Toast.makeText(requireContext(), "Search history cleared", Toast.LENGTH_SHORT).show()
            true
        }
    }

    /** The title and App update card scroll together with the settings below them. */
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        val prefs = super.onCreateAdapter(preferenceScreen)
        val b = ViewSettingsHeaderBinding.inflate(LayoutInflater.from(requireContext()), listView, false)
        card = b
        @Suppress("UNCHECKED_CAST")
        return ConcatAdapter(SingleViewAdapter(b.root), prefs as RecyclerView.Adapter<RecyclerView.ViewHolder>)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.clipToPadding = false
        listView.setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.list_bottom_space))
        val ctx = requireContext()
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        card?.versionText?.text = "Version ${info.versionName} · build ${Updater.installedBuild(ctx)}"
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.state.collect { renderUpdate(it) }
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Opening Settings checks once by itself, so the card is never stale.
        if (!hidden && UpdateManager.state.value == UpdateManager.State.Idle) UpdateManager.check(requireContext())
    }

    private fun renderUpdate(state: UpdateManager.State) {
        val b = card ?: return
        val ctx = context ?: return
        val red = ContextCompat.getColor(ctx, R.color.brand_red)
        val normal = MaterialColors.getColor(b.statusText, com.google.android.material.R.attr.colorOnSurface)

        b.progress.isVisible = false
        b.notesText.isVisible = false
        b.stateBadge.isVisible = false
        b.secondButton.isVisible = false
        b.mainButton.isEnabled = true
        b.statusText.setTextColor(normal)

        fun badge(text: String, highlight: Boolean) {
            b.stateBadge.text = text
            b.stateBadge.isVisible = true
            b.stateBadge.setBackgroundResource(if (highlight) R.drawable.bg_badge_red else R.drawable.bg_status_pill)
            b.stateBadge.setTextColor(if (highlight) Color.WHITE else normal)
        }

        fun main(text: String, icon: Int, action: () -> Unit) {
            b.mainButton.text = text
            b.mainButton.setIconResource(icon)
            b.mainButton.setOnClickListener { action() }
        }

        fun notes(r: Updater.Release) {
            val text = r.notes.lines()
                .filter { it.isNotBlank() && !it.startsWith("Co-Authored-By") && !it.startsWith("Claude-Session") }
                .joinToString("\n").trim()
            b.notesText.text = if (text.isEmpty()) "No notes for this version." else "What's new\n\n$text"
            b.notesText.isVisible = true
        }

        when (state) {
            UpdateManager.State.Idle -> {
                b.statusText.text = "Check whether a newer version is available."
                main("Check for updates", R.drawable.ic_retry) { UpdateManager.check(ctx) }
            }
            UpdateManager.State.Checking -> {
                b.statusText.text = "Checking for updates…"
                b.progress.isIndeterminate = true
                b.progress.isVisible = true
                main("Checking…", R.drawable.ic_retry) {}
                b.mainButton.isEnabled = false
            }
            is UpdateManager.State.UpToDate -> {
                badge("Up to date", false)
                val ago = DateUtils.getRelativeTimeSpanString(state.checkedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                b.statusText.text = "You have the latest version. Checked ${ago.toString().lowercase()}."
                main("Check again", R.drawable.ic_retry) { UpdateManager.check(ctx) }
            }
            is UpdateManager.State.Available -> {
                badge("New", true)
                b.statusText.text = "Build ${state.release.build} is ready to download."
                notes(state.release)
                main("Download & install", R.drawable.ic_download) { UpdateManager.downloadAndInstall(ctx, state.release) }
            }
            is UpdateManager.State.Downloading -> {
                badge("New", true)
                b.progress.isVisible = true
                if (state.percent < 0) {
                    b.progress.isIndeterminate = true
                    b.statusText.text = "Downloading build ${state.release.build}…"
                } else {
                    b.progress.isIndeterminate = false
                    b.progress.setProgressCompat(state.percent, true)
                    b.statusText.text = "Downloading build ${state.release.build}… ${state.percent}%"
                }
                main("Downloading…", R.drawable.ic_download) {}
                b.mainButton.isEnabled = false
                b.secondButton.text = "Cancel"
                b.secondButton.isVisible = true
                b.secondButton.setOnClickListener { UpdateManager.cancel() }
            }
            is UpdateManager.State.Ready -> {
                badge("New", true)
                b.statusText.text = if (Updater.canInstall(ctx)) "Downloaded. Tap Install to finish updating."
                else "Downloaded. Android will ask you once to allow installs from YT Downloader, then come back and tap Install."
                main("Install", R.drawable.ic_download) { UpdateManager.install(ctx) }
            }
            is UpdateManager.State.Failed -> {
                b.statusText.text = state.message
                b.statusText.setTextColor(red)
                val r = state.release
                if (r != null) main("Try again", R.drawable.ic_retry) { UpdateManager.downloadAndInstall(ctx, r) }
                else main("Try again", R.drawable.ic_retry) { UpdateManager.check(ctx) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(this)
        // Back from Android's "allow installs" screen: carry on installing.
        val s = UpdateManager.state.value
        if (s is UpdateManager.State.Ready && UpdateManager.waitingForPermission && Updater.canInstall(requireContext())) {
            UpdateManager.install(requireContext())
        }
        renderUpdate(UpdateManager.state.value)
    }

    override fun onPause() {
        super.onPause()
        PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        card = null
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        val ctx = context ?: return
        when (key) {
            Settings.K_WIFI_ONLY -> { Queue.restart(ctx); Sync.schedule(ctx) }
            Settings.K_PARALLEL -> Queue.restart(ctx)
            Settings.K_AUTO_SYNC -> Sync.schedule(ctx)
        }
    }
}

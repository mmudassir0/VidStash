package com.mudassir.ytdownloader.ui

import android.content.SharedPreferences
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import com.mudassir.ytdownloader.Storage
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import com.mudassir.ytdownloader.Backup
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

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runBackup(uri)
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    /** Which folder the picker is choosing: true = audio, false = videos. */
    private var pickingAudio = false
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) setFolder(pickingAudio, uri.toString())
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        listOf(false to "pick_video_folder", true to "pick_audio_folder").forEach { (audio, key) ->
            findPreference<Preference>(key)?.apply {
                setOnPreferenceClickListener { chooseFolder(audio); true }
            }
        }
        updateFolderSummaries()
        findPreference<Preference>("backup_export")?.setOnPreferenceClickListener {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            exportFile.launch("YTDownloader-backup-$day.json")
            true
        }
        findPreference<Preference>("backup_import")?.setOnPreferenceClickListener {
            importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            true
        }
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
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        b.versionText.text = "Version ${info.versionName} · build ${Updater.installedBuild(ctx)}"
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

    // ---------- save folders ----------

    private fun updateFolderSummaries() {
        val s = Settings(requireContext())
        findPreference<Preference>("pick_video_folder")?.summary = Storage.describeFolder(s.videoFolder)
        findPreference<Preference>("pick_audio_folder")?.summary = Storage.describeFolder(s.audioFolder)
    }

    private fun chooseFolder(audio: Boolean) {
        val s = Settings(requireContext())
        val current = if (audio) s.audioFolder else s.videoFolder
        if (current.isEmpty()) return launchPicker(audio)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (audio) "Audio folder" else "Videos folder")
            .setMessage("Now saving to ${Storage.describeFolder(current)}.")
            .setPositiveButton("Choose another") { _, _ -> launchPicker(audio) }
            .setNegativeButton("Use default") { _, _ -> setFolder(audio, "") }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun launchPicker(audio: Boolean) {
        pickingAudio = audio
        runCatching { pickFolder.launch(null) }
            .onFailure { Toast.makeText(requireContext(), "This phone has no folder picker", Toast.LENGTH_LONG).show() }
    }

    private fun setFolder(audio: Boolean, value: String) {
        val ctx = requireContext()
        val s = Settings(ctx)
        val old = if (audio) s.audioFolder else s.videoFolder
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (value.isNotEmpty()) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(Uri.parse(value), flags) }.onFailure {
                Toast.makeText(ctx, "Couldn't get access to that folder", Toast.LENGTH_LONG).show()
                return
            }
        }
        if (audio) s.audioFolder = value else s.videoFolder = value
        // Let go of the old folder unless the other kind still uses it.
        if (old.isNotEmpty() && old != value && old != (if (audio) s.videoFolder else s.audioFolder)) {
            runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        updateFolderSummaries()
        Toast.makeText(ctx, "New ${if (audio) "audio" else "videos"} will be saved to ${Storage.describeFolder(value)}", Toast.LENGTH_LONG).show()
    }

    // ---------- backup ----------

    private fun runBackup(uri: Uri) {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val json = Backup.export(ctx)
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                }
            }
            Toast.makeText(
                ctx,
                result.fold({ "Backup saved" }, { "Backup failed: ${it.message}" }),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun confirmRestore(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Restore this backup?")
            .setMessage(
                "Playlists and downloads from the backup are added to what you have now. " +
                    "Your settings are replaced by the ones in the backup."
            )
            .setPositiveButton("Restore") { _, _ -> runRestore(uri) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runRestore(uri: Uri) {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    Backup.restore(ctx, text)
                }
            }
            val msg = result.fold(
                { r ->
                    buildList {
                        add("Restored ${r.playlists} synced list(s) and ${r.files} file(s)")
                        if (r.queued > 0) add("${r.queued} download(s) back in the queue")
                        if (r.missing > 0) add("${r.missing} file(s) not on this phone were skipped")
                    }.joinToString(". ") + "."
                },
                { "Couldn't restore: ${it.message}" }
            )
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (result.isSuccess) "Backup restored" else "Restore failed")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
            // Show restored settings (switches, lists) right away.
            onCreatePreferences(null, null)
            renderUpdate(UpdateManager.state.value)
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

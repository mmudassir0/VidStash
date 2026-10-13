package com.mudassir.vidstash.ui

import android.content.SharedPreferences
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import com.mudassir.vidstash.Storage
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.mudassir.vidstash.Backup
import com.mudassir.vidstash.BuildConfig
import com.mudassir.vidstash.FolderImport
import com.mudassir.vidstash.Queue
import com.mudassir.vidstash.R
import com.mudassir.vidstash.Settings
import com.mudassir.vidstash.Sync
import com.mudassir.vidstash.UpdateManager
import com.mudassir.vidstash.Updater
import com.mudassir.vidstash.databinding.ViewSettingsHeaderBinding
import kotlinx.coroutines.launch

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var card: ViewSettingsHeaderBinding? = null

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runBackup(uri)
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    private val importFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runFolderImport(uri)
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
        if (!BuildConfig.SELF_UPDATE) findPreference<Preference>(Settings.K_UPDATE_CHECK)?.isVisible = false
        findPreference<ListPreference>("app_language")?.apply {
            value = AppCompatDelegate.getApplicationLocales().get(0)?.language.orEmpty()
            setOnPreferenceChangeListener { _, v ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(v as String))
                true
            }
        }
        mapOf(
            "about" to { About.showAbout(requireContext()) },
            "report" to { About.reportProblem(requireContext()) },
            "source" to { About.openSource(requireContext()) },
            "privacy" to { About.showPrivacy(requireContext()) },
            "licenses" to { About.showLicenses(requireContext()) },
        ).forEach { (key, action) ->
            findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
        }
        findPreference<Preference>("backup_export")?.setOnPreferenceClickListener {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            exportFile.launch("VidStash-backup-$day.json")
            true
        }
        findPreference<Preference>("folder_import")?.setOnPreferenceClickListener {
            runCatching { importFolder.launch(null) }
            true
        }
        findPreference<Preference>("backup_import")?.setOnPreferenceClickListener {
            importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            true
        }
        findPreference<Preference>(Settings.K_CLEAR_HISTORY)?.setOnPreferenceClickListener {
            Settings(requireContext()).recentSearches = emptyList()
            Toast.makeText(requireContext(), R.string.set_history_cleared, Toast.LENGTH_SHORT).show()
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
        if (!hidden && BuildConfig.SELF_UPDATE && UpdateManager.state.value == UpdateManager.State.Idle) UpdateManager.check(requireContext())
    }

    private fun renderUpdate(state: UpdateManager.State) {
        val b = card ?: return
        val ctx = context ?: return
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        b.versionText.text = ctx.getString(R.string.set_version, info.versionName)
        if (!BuildConfig.SELF_UPDATE) {
            // Store builds are updated by the store.
            b.statusText.text = ctx.getString(R.string.upd_by_store)
            listOf(b.stateBadge, b.progress, b.notesText, b.mainButton, b.secondButton).forEach { it.isVisible = false }
            return
        }
        val red = ContextCompat.getColor(ctx, R.color.error_red)
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
            b.stateBadge.setBackgroundResource(if (highlight) R.drawable.bg_badge_brand else R.drawable.bg_status_pill)
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
            b.notesText.text = if (text.isEmpty()) ctx.getString(R.string.upd_no_notes) else ctx.getString(R.string.upd_whats_new, text)
            b.notesText.isVisible = true
        }

        when (state) {
            UpdateManager.State.Idle -> {
                b.statusText.text = ctx.getString(R.string.upd_check_prompt)
                main(ctx.getString(R.string.upd_check), R.drawable.ic_retry) { UpdateManager.check(ctx) }
            }
            UpdateManager.State.Checking -> {
                b.statusText.text = ctx.getString(R.string.upd_checking)
                b.progress.isIndeterminate = true
                b.progress.isVisible = true
                main(ctx.getString(R.string.upd_checking_short), R.drawable.ic_retry) {}
                b.mainButton.isEnabled = false
            }
            is UpdateManager.State.UpToDate -> {
                badge(ctx.getString(R.string.upd_up_to_date), false)
                val ago = DateUtils.getRelativeTimeSpanString(state.checkedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                b.statusText.text = ctx.getString(R.string.upd_latest, ago.toString().lowercase())
                main(ctx.getString(R.string.upd_check_again), R.drawable.ic_retry) { UpdateManager.check(ctx) }
            }
            is UpdateManager.State.Available -> {
                badge(ctx.getString(R.string.upd_new), true)
                b.statusText.text = ctx.getString(R.string.upd_ready_build, state.release.build)
                notes(state.release)
                main(ctx.getString(R.string.upd_download_install), R.drawable.ic_download) { UpdateManager.downloadAndInstall(ctx, state.release) }
            }
            is UpdateManager.State.Downloading -> {
                badge(ctx.getString(R.string.upd_new), true)
                b.progress.isVisible = true
                if (state.percent < 0) {
                    b.progress.isIndeterminate = true
                    b.statusText.text = ctx.getString(R.string.upd_downloading_build, state.release.build)
                } else {
                    b.progress.isIndeterminate = false
                    b.progress.setProgressCompat(state.percent, true)
                    b.statusText.text = ctx.getString(R.string.upd_downloading_build_pct, state.release.build, state.percent)
                }
                main(ctx.getString(R.string.upd_downloading), R.drawable.ic_download) {}
                b.mainButton.isEnabled = false
                b.secondButton.text = ctx.getString(R.string.common_cancel)
                b.secondButton.isVisible = true
                b.secondButton.setOnClickListener { UpdateManager.cancel() }
            }
            is UpdateManager.State.Ready -> {
                badge(ctx.getString(R.string.upd_new), true)
                b.statusText.text = if (Updater.canInstall(ctx)) ctx.getString(R.string.upd_downloaded)
                else ctx.getString(R.string.upd_downloaded_allow)
                main(ctx.getString(R.string.upd_install), R.drawable.ic_download) { UpdateManager.install(ctx) }
            }
            is UpdateManager.State.Failed -> {
                b.statusText.text = state.message
                b.statusText.setTextColor(red)
                val r = state.release
                if (r != null) main(ctx.getString(R.string.upd_try_again), R.drawable.ic_retry) { UpdateManager.downloadAndInstall(ctx, r) }
                else main(ctx.getString(R.string.upd_try_again), R.drawable.ic_retry) { UpdateManager.check(ctx) }
            }
        }
    }

    // ---------- save folders ----------

    private fun updateFolderSummaries() {
        val s = Settings(requireContext())
        findPreference<Preference>("pick_video_folder")?.summary = Storage.describeFolder(requireContext(), s.videoFolder)
        findPreference<Preference>("pick_audio_folder")?.summary = Storage.describeFolder(requireContext(), s.audioFolder)
    }

    private fun chooseFolder(audio: Boolean) {
        val s = Settings(requireContext())
        val current = if (audio) s.audioFolder else s.videoFolder
        if (current.isEmpty()) return launchPicker(audio)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (audio) R.string.folder_audio else R.string.folder_videos)
            .setMessage(getString(R.string.folder_now_saving, Storage.describeFolder(requireContext(), current)))
            .setPositiveButton(R.string.folder_choose_another) { _, _ -> launchPicker(audio) }
            .setNegativeButton(R.string.folder_use_default) { _, _ -> setFolder(audio, "") }
            .setNeutralButton(R.string.common_cancel, null)
            .show()
    }

    private fun launchPicker(audio: Boolean) {
        pickingAudio = audio
        runCatching { pickFolder.launch(null) }
            .onFailure { Toast.makeText(requireContext(), R.string.folder_no_picker, Toast.LENGTH_LONG).show() }
    }

    private fun setFolder(audio: Boolean, value: String) {
        val ctx = requireContext()
        val s = Settings(ctx)
        val old = if (audio) s.audioFolder else s.videoFolder
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (value.isNotEmpty()) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(Uri.parse(value), flags) }.onFailure {
                Toast.makeText(ctx, R.string.folder_no_access, Toast.LENGTH_LONG).show()
                return
            }
        }
        if (audio) s.audioFolder = value else s.videoFolder = value
        // Let go of the old folder unless the other kind still uses it.
        if (old.isNotEmpty() && old != value && old != (if (audio) s.videoFolder else s.audioFolder)) {
            runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        updateFolderSummaries()
        Toast.makeText(ctx, ctx.getString(if (audio) R.string.folder_new_audio else R.string.folder_new_videos, Storage.describeFolder(ctx, value)), Toast.LENGTH_LONG).show()
    }

    private fun runFolderImport(uri: Uri) {
        val ctx = requireContext().applicationContext
        // Keep access so the files can be played (and deleted) later.
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        Toast.makeText(ctx, R.string.import_running, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { FolderImport.import(ctx, uri) } }
            Toast.makeText(
                ctx,
                result.fold(
                    { r ->
                        if (r.added == 0 && r.alreadyThere == 0) ctx.getString(R.string.import_none)
                        else ctx.getString(R.string.import_added, r.added) + if (r.alreadyThere > 0) ctx.getString(R.string.import_already, r.alreadyThere) else ""
                    },
                    { ctx.getString(R.string.import_failed, it.message ?: "") }
                ),
                Toast.LENGTH_LONG
            ).show()
        }
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
                result.fold({ ctx.getString(R.string.backup_saved) }, { ctx.getString(R.string.backup_failed, it.message ?: "") }),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun confirmRestore(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_title)
            .setMessage(
                R.string.restore_msg
            )
            .setPositiveButton(R.string.restore_restore) { _, _ -> runRestore(uri) }
            .setNegativeButton(R.string.common_cancel, null)
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
                        add(ctx.getString(R.string.restore_done, r.playlists, r.files))
                        if (r.queued > 0) add(ctx.getString(R.string.restore_queued, r.queued))
                        if (r.missing > 0) add(ctx.getString(R.string.restore_missing, r.missing))
                    }.joinToString(". ") + "."
                },
                { ctx.getString(R.string.restore_failed_msg, it.message ?: "") }
            )
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (result.isSuccess) R.string.restore_ok_title else R.string.restore_failed_title)
                .setMessage(msg)
                .setPositiveButton(R.string.common_ok, null)
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
            Settings.K_PARALLEL, Settings.K_CHARGING, Settings.K_DOWNLOAD_TIME, Settings.K_DATA_LIMIT -> Queue.restart(ctx)
            Settings.K_AUTO_SYNC -> Sync.schedule(ctx)
        }
    }
}

package com.mudassir.ytdownloader.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.mudassir.ytdownloader.MainActivity
import com.mudassir.ytdownloader.Queue
import com.mudassir.ytdownloader.R
import com.mudassir.ytdownloader.Settings
import com.mudassir.ytdownloader.Sync
import com.mudassir.ytdownloader.Updater

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        findPreference<Preference>("check_now")?.setOnPreferenceClickListener {
            (activity as? MainActivity)?.checkForUpdates(manual = true)
            true
        }
        findPreference<Preference>(Settings.K_CLEAR_HISTORY)?.setOnPreferenceClickListener {
            Settings(requireContext()).recentSearches = emptyList()
            Toast.makeText(requireContext(), "Search history cleared", Toast.LENGTH_SHORT).show()
            true
        }
        val ctx = requireContext()
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        findPreference<Preference>("version")?.summary =
            "${info.versionName} · build ${Updater.installedBuild(ctx)}"
    }

    /** Adds a big "Settings" title above the list, like the other tabs. */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val list = super.onCreateView(inflater, container, savedInstanceState)
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply {
                text = "Settings"
                TextViewCompat.setTextAppearance(this, R.style.TextAppearance_App_Headline)
                setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (4 * d).toInt())
            })
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.clipToPadding = false
        listView.setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.list_bottom_space))
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(this)
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

package com.mudassir.ytdownloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mudassir.ytdownloader.databinding.ActivityMainBinding
import com.mudassir.ytdownloader.ui.DownloadsFragment
import com.mudassir.ytdownloader.ui.GetFragment
import com.mudassir.ytdownloader.ui.LibraryFragment
import com.mudassir.ytdownloader.ui.SettingsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val tabs = listOf(R.id.nav_get, R.id.nav_downloads, R.id.nav_library, R.id.nav_settings)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind transparent system bars on every Android version (icons adapt to light/dark).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Android 15 draws behind the system bars; keep content clear of them and of the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardOpen = insets.isVisible(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, if (keyboardOpen) ime.bottom else 0)
            // The floating bar sits above the gesture/nav bar, and hides while typing.
            (b.navCard.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = bars.bottom + dp(12)
            b.navCard.requestLayout()
            b.navCard.isVisible = !keyboardOpen
            insets
        }
        // The bar is inside a card that already handles insets; don't pad it again.
        ViewCompat.setOnApplyWindowInsetsListener(b.bottomNav) { _, _ -> WindowInsetsCompat.CONSUMED }

        requestNeededPermissions()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, GetFragment(), tag(R.id.nav_get))
                .add(R.id.fragmentContainer, DownloadsFragment(), tag(R.id.nav_downloads))
                .add(R.id.fragmentContainer, LibraryFragment(), tag(R.id.nav_library))
                .add(R.id.fragmentContainer, SettingsFragment(), tag(R.id.nav_settings))
                .commitNow()
            show(R.id.nav_get)
        }
        b.bottomNav.setOnItemSelectedListener { show(it.itemId); true }

        // Back on another tab returns to Get; on Get it steps back or leaves the app.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.bottomNav.selectedItemId != R.id.nav_get) {
                    select(R.id.nav_get)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        handleIntent(intent)
        maybeCheckForUpdates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Queue.kick(this) // in case anything is waiting
    }

    private fun tag(id: Int) = "tab-$id"

    private fun fragment(id: Int): Fragment? = supportFragmentManager.findFragmentByTag(tag(id))

    private fun show(id: Int) {
        val tx = supportFragmentManager.beginTransaction()
        tabs.forEach { t -> fragment(t)?.let { if (t == id) tx.show(it) else tx.hide(it) } }
        tx.commitNowAllowingStateLoss()
    }

    private fun select(id: Int) {
        b.bottomNav.selectedItemId = id // triggers show()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            select(R.id.nav_get)
            (fragment(R.id.nav_get) as? GetFragment)?.openShared(text)
            intent.action = null // don't re-open it after rotation
            return
        }
        when (intent.getIntExtra(EXTRA_TAB, -1)) {
            TAB_DOWNLOADS -> select(R.id.nav_downloads)
            TAB_LIBRARY -> select(R.id.nav_library)
        }
        intent.removeExtra(EXTRA_TAB)
    }

    private fun requestNeededPermissions() {
        val perm = when {
            Build.VERSION.SDK_INT >= 33 -> Manifest.permission.POST_NOTIFICATIONS
            Build.VERSION.SDK_INT <= 28 -> Manifest.permission.WRITE_EXTERNAL_STORAGE
            else -> return
        }
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            askPermission.launch(perm)
        }
    }

    // ---------- updates ----------

    private fun maybeCheckForUpdates() {
        val s = Settings(this)
        if (!s.autoUpdateCheck) return
        if (System.currentTimeMillis() - s.lastUpdateCheck < 6 * 3600_000L) return
        checkForUpdates(manual = false)
    }

    fun checkForUpdates(manual: Boolean) {
        if (manual) Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { Updater.latest() } }
            Settings(this@MainActivity).lastUpdateCheck = System.currentTimeMillis()
            val release = result.getOrNull()
            when {
                release == null && manual ->
                    Toast.makeText(this@MainActivity, "Couldn't reach GitHub. Try again later.", Toast.LENGTH_SHORT).show()
                release == null -> {}
                release.build > Updater.installedBuild(this@MainActivity) -> offerUpdate(release)
                manual -> Toast.makeText(this@MainActivity, "You're on the latest version", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun offerUpdate(r: Updater.Release) {
        if (isFinishing) return
        val notes = r.notes.lines().filter { it.isNotBlank() && !it.startsWith("Co-Authored-By") && !it.startsWith("Claude-Session") }
            .joinToString("\n").take(800)
        MaterialAlertDialogBuilder(this)
            .setTitle("Update available (build ${r.build})")
            .setMessage(if (notes.isBlank()) "A newer version is ready to install." else "What's new:\n\n$notes")
            .setPositiveButton("Update") { _, _ -> downloadUpdate(r) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadUpdate(r: Updater.Release) {
        if (!Updater.canInstall(this)) {
            Toast.makeText(this, "Allow installs from YT Downloader, then tap Update again", Toast.LENGTH_LONG).show()
            startActivity(Updater.allowInstallsIntent(this))
            return
        }
        val bar = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            max = 100
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Downloading update…")
            .setView(bar)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val apk = runCatching {
                withContext(Dispatchers.IO) {
                    Updater.download(this@MainActivity, r) { pct ->
                        runOnUiThread { bar.isIndeterminate = false; bar.setProgressCompat(pct, true) }
                    }
                }
            }
            dialog.dismiss()
            apk.onSuccess { Updater.install(this@MainActivity, it) }
                .onFailure { Toast.makeText(this@MainActivity, "Update failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_DOWNLOADS = 1
        const val TAB_LIBRARY = 2
    }
}

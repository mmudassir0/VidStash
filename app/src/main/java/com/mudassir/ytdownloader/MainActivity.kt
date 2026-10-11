package com.mudassir.ytdownloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mudassir.ytdownloader.databinding.ActivityMainBinding
import com.mudassir.ytdownloader.ui.DownloadsFragment
import com.mudassir.ytdownloader.ui.GetFragment
import com.mudassir.ytdownloader.ui.LibraryFragment
import com.mudassir.ytdownloader.ui.SettingsFragment
import kotlinx.coroutines.launch

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
        watchUpdates()
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
        UpdateManager.cleanUp(this)
        val s = Settings(this)
        if (!s.autoUpdateCheck) return
        if (System.currentTimeMillis() - s.lastUpdateCheck < 6 * 3600_000L) return
        UpdateManager.check(this) { release -> if (release != null) offerUpdate(release) }
    }

    /** Red dot on the Settings tab while an update is waiting. */
    private fun watchUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.state.collect { st ->
                    val pending = st is UpdateManager.State.Available || st is UpdateManager.State.Downloading ||
                        st is UpdateManager.State.Ready
                    if (pending) b.bottomNav.getOrCreateBadge(R.id.nav_settings).apply {
                        clearNumber()
                        backgroundColor = ContextCompat.getColor(this@MainActivity, R.color.brand_red)
                        isVisible = true
                    } else b.bottomNav.removeBadge(R.id.nav_settings)
                }
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
            .setPositiveButton("Update") { _, _ ->
                UpdateManager.downloadAndInstall(this, r)
                select(R.id.nav_settings) // progress shows on the App update card
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_DOWNLOADS = 1
        const val TAB_LIBRARY = 2
    }
}

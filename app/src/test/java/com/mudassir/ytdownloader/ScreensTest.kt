package com.mudassir.ytdownloader

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.google.android.material.chip.Chip
import com.mudassir.ytdownloader.databinding.ActivityMainBinding
import com.mudassir.ytdownloader.databinding.FragmentDownloadsBinding
import com.mudassir.ytdownloader.databinding.FragmentGetBinding
import com.mudassir.ytdownloader.databinding.FragmentLibraryBinding
import com.mudassir.ytdownloader.databinding.ItemDownloadBinding
import com.mudassir.ytdownloader.databinding.ItemPlaylistBinding
import com.mudassir.ytdownloader.databinding.ItemVideoBinding
import com.mudassir.ytdownloader.databinding.ItemVideoLargeBinding
import com.mudassir.ytdownloader.databinding.ViewGetHeaderBinding
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ScreensTest(private val night: Boolean) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes() = listOf(true, false)
    }

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT, softButtons = false),
        theme = "Theme.YTDownloader",
        maxPercentDifference = 100.0
    )

    private val ctx get() = paparazzi.context
    private val inf get() = LayoutInflater.from(paparazzi.context)

    private val palettes = listOf(
        intArrayOf(0xFF1E3C72.toInt(), 0xFF2A5298.toInt()),
        intArrayOf(0xFFB24592.toInt(), 0xFFF15F79.toInt()),
        intArrayOf(0xFF134E5E.toInt(), 0xFF71B280.toInt()),
        intArrayOf(0xFFF7971E.toInt(), 0xFFFFD200.toInt()),
        intArrayOf(0xFF232526.toInt(), 0xFF414345.toInt()),
    )

    private fun fakeThumb(i: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, palettes[i % palettes.size])

    private fun shell(tab: Int, content: (ViewGroup) -> View): View {
        val main = ActivityMainBinding.inflate(inf)
        main.fragmentContainer.visibility = View.GONE
        main.root.addView(content(main.root), 0, FrameLayout.LayoutParams(-1, -1))
        main.bottomNav.selectedItemId = tab
        main.root.setPadding(0, 66, 0, 0) // status bar
        (main.navCard.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = 60
        return main.root
    }

    private fun scrolling(parent: FrameLayout, vararg views: View): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        views.forEach { col.addView(it) }
        return ScrollView(ctx).apply { addView(col) }.also {
            parent.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private val titles = listOf(
        "Lofi hip hop radio – beats to relax/study to",
        "Building a Next.js app with TypeScript in 2026 (full course)",
        "Lahore street food tour – best nihari & paye",
        "PSX market wrap: KMI-30 hits new high",
    )

    private fun settings(setup: com.mudassir.ytdownloader.databinding.ViewSettingsHeaderBinding.() -> Unit): View =
        shell(R.id.nav_settings) { parent ->
            com.mudassir.ytdownloader.databinding.ViewSettingsHeaderBinding.inflate(inf, parent, false).apply {
                versionText.text = "Version 1.2.22 · build 22"
                setup()
            }.root.let { header -> ScrollView(ctx).apply { addView(header) } }
        }

    @Test
    fun settings_available() {
        paparazzi.snapshot(settings {
            stateBadge.text = "New"; stateBadge.isVisible = true
            stateBadge.setBackgroundResource(R.drawable.bg_badge_red); stateBadge.setTextColor(Color.WHITE)
            statusText.text = "Build 23 is ready to download."
            notesText.text = "What's new\n\nSettings: App update card with download & install, progress and release notes"
            notesText.isVisible = true
            mainButton.text = "Download & install"; mainButton.setIconResource(R.drawable.ic_download)
        })
    }

    @Test
    fun settings_downloading() {
        paparazzi.snapshot(settings {
            stateBadge.text = "New"; stateBadge.isVisible = true
            stateBadge.setBackgroundResource(R.drawable.bg_badge_red); stateBadge.setTextColor(Color.WHITE)
            statusText.text = "Downloading build 23… 62%"
            progress.isVisible = true; progress.isIndeterminate = false; progress.progress = 62
            mainButton.text = "Downloading…"; mainButton.isEnabled = false
            secondButton.text = "Cancel"; secondButton.isVisible = true
        })
    }

    @Test
    fun settings_uptodate() {
        paparazzi.snapshot(settings {
            stateBadge.text = "Up to date"; stateBadge.isVisible = true
            statusText.text = "You have the latest version. Checked 2 minutes ago."
            mainButton.text = "Check again"
        })
    }
}

package com.mudassir.vidstash.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mudassir.vidstash.R
import com.mudassir.vidstash.Updater

/** About VidStash: source code, problem reports, privacy, disclaimer and open-source licences. */
object About {

    const val SOURCE_URL = "https://github.com/" + Updater.REPO

    private class Lib(val name: String, val author: String, val license: String, val asset: String?, val url: String)

    /** Everything bundled in the app, and its licence. */
    private val LIBS = listOf(
        Lib("NewPipe Extractor", "Team NewPipe", "GPL-3.0", "GPL-3.0.txt", "https://github.com/TeamNewPipe/NewPipeExtractor"),
        Lib("jump3r (LAME MP3 encoder)", "Ken Händel, Sciss", "LGPL-2.1", "LGPL-2.1.txt", "https://github.com/Sciss/jump3r"),
        Lib("Mozilla Rhino", "Mozilla", "MPL-2.0", "MPL-2.0.txt", "https://github.com/mozilla/rhino"),
        Lib("jsoup", "Jonathan Hedley", "MIT", null, "https://jsoup.org"),
        Lib("nanojson", "Matt Mastracci", "Apache-2.0", "Apache-2.0.txt", "https://github.com/mmastrac/nanojson"),
        Lib("AndroidX, Material Components, Media3", "Google / The Android Open Source Project", "Apache-2.0", "Apache-2.0.txt", "https://developer.android.com/jetpack/androidx"),
        Lib("OkHttp, Okio", "Square", "Apache-2.0", "Apache-2.0.txt", "https://square.github.io/okhttp/"),
        Lib("Coil", "Coil Contributors", "Apache-2.0", "Apache-2.0.txt", "https://coil-kt.github.io/coil/"),
        Lib("Kotlin, kotlinx.coroutines", "JetBrains", "Apache-2.0", "Apache-2.0.txt", "https://kotlinlang.org"),
        Lib("SponsorBlock data", "Ajay Ramachandran and contributors", "CC BY-NC-SA 4.0", null, "https://sponsor.ajay.app"),
    )

    private const val MIT = """MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

    fun showAbout(ctx: Context) {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        text(
            ctx, ctx.getString(R.string.about_title, info.versionName), ctx.getString(R.string.about_text)
        )
    }

    fun showPrivacy(ctx: Context) = text(
        ctx, ctx.getString(R.string.privacy_title), ctx.getString(R.string.privacy_text)
    )

    fun openSource(ctx: Context) = open(ctx, SOURCE_URL)

    /** Opens a new GitHub issue with the phone and app version filled in. */
    fun reportProblem(ctx: Context, crash: String? = null) {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val body = buildString {
            appendLine("**What happened?**")
            appendLine()
            appendLine()
            appendLine("**What did you expect?**")
            appendLine()
            appendLine()
            appendLine("---")
            appendLine("VidStash ${info.versionName} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (crash != null) {
                appendLine()
                appendLine("```")
                appendLine(crash.take(6000))
                appendLine("```")
            }
        }
        open(ctx, "$SOURCE_URL/issues/new?body=" + Uri.encode(body))
    }

    fun showLicenses(ctx: Context) {
        val names = LIBS.map { "${it.name}\n${it.license} · ${it.author}" }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.licenses_title)
            .setItems(names) { _, i ->
                val lib = LIBS[i]
                val body = when {
                    lib.asset != null -> runCatching {
                        ctx.assets.open("licenses/${lib.asset}").bufferedReader().readText()
                    }.getOrDefault(ctx.getString(R.string.licenses_see, lib.url))
                    lib.license == "MIT" -> MIT
                    else -> ctx.getString(R.string.licenses_under, lib.license, lib.url)
                }
                text(ctx, lib.name, "${lib.url}\n\n$body", small = true)
            }
            .setNeutralButton(R.string.licenses_app) { _, _ ->
                val gpl = runCatching { ctx.assets.open("licenses/GPL-3.0.txt").bufferedReader().readText() }.getOrDefault("")
                text(ctx, "GNU GPL v3", gpl, small = true)
            }
            .setPositiveButton(R.string.common_close, null)
            .show()
    }

    private fun text(ctx: Context, title: String, message: String, small: Boolean = false) {
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val tv = TextView(ctx).apply {
            text = message
            setTextIsSelectable(true)
            textSize = if (small) 12f else 15f
            setLineSpacing(0f, 1.15f)
            setPadding(pad, pad / 2, pad, pad)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(tv) })
            .setPositiveButton(R.string.common_ok, null)
            .show()
    }

    private fun open(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, ctx.getString(R.string.no_browser), Toast.LENGTH_SHORT).show()
        }
    }
}

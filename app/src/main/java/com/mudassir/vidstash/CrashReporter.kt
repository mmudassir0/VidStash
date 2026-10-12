package com.mudassir.vidstash

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Catches any crash and shows the error on screen (in a separate ":crash" process),
 * so the cause can be copied without a PC or adb.
 */
object CrashReporter {

    const val PROCESS_SUFFIX = ":crash"

    fun isCrashProcess(app: Application): Boolean = processName(app).endsWith(PROCESS_SUFFIX)

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildReport(app, thread, error)
                runCatching { File(app.filesDir, "last_crash.txt").writeText(report) }
                app.startActivity(
                    Intent(app, CrashActivity::class.java)
                        .putExtra(CrashActivity.EXTRA_REPORT, report.take(100_000))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } catch (t: Throwable) {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    private fun buildReport(ctx: Context, thread: Thread, error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()
        return buildString {
            appendLine("VidStash $version")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(sw.toString())
        }
    }

    private fun processName(app: Application): String {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
        return runCatching {
            File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
        }.getOrDefault("")
    }
}

/** Shows the last crash with Copy / Restart buttons. Uses no app code that could crash again. */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT)
            ?: runCatching { File(filesDir, "last_crash.txt").readText() }.getOrDefault("No crash details found.")

        val pad = (16 * resources.displayMetrics.density).toInt()
        val title = TextView(this).apply {
            text = getString(R.string.crash_title)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(pad, pad * 2, pad, pad / 2)
        }
        val hint = TextView(this).apply {
            text = getString(R.string.crash_hint)
            setPadding(pad, 0, pad, pad / 2)
        }
        val details = TextView(this).apply {
            text = report
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, pad)
        }
        val scroll = ScrollView(this).apply {
            addView(HorizontalScrollView(this@CrashActivity).apply { addView(details) })
        }
        val copy = Button(this).apply {
            text = getString(R.string.crash_copy)
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("VidStash crash", report))
                Toast.makeText(this@CrashActivity, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            }
        }
        val restart = Button(this).apply {
            text = getString(R.string.crash_restart)
            setOnClickListener {
                startActivity(
                    Intent(this@CrashActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
                Process.killProcess(Process.myPid())
            }
        }
        val reportButton = Button(this).apply {
            text = getString(R.string.crash_report)
            setOnClickListener { com.mudassir.vidstash.ui.About.reportProblem(this@CrashActivity, report) }
        }
        val close = Button(this).apply {
            text = getString(R.string.crash_close)
            setOnClickListener { finish() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 0, pad, pad)
            val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            addView(reportButton, lp()); addView(copy, lp()); addView(restart, lp()); addView(close, lp())
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(title)
            addView(hint)
            addView(buttons)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    companion object {
        const val EXTRA_REPORT = "report"
    }
}

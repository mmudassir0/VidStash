package com.mudassir.vidstash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Checks this app's GitHub Releases for a newer build and installs it.
 * CI tags each release "v1.2.N" (older ones "build-N") and gives the APK versionCode 100 + N.
 */
object Updater {

    const val REPO = "mmudassir0/VidStash"

    data class Release(val build: Int, val apkUrl: String, val notes: String, val pageUrl: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun installedBuild(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return (PackageInfoCompat.getLongVersionCode(info) - 100).toInt()
    }

    /** The newest release, or null if it can't be read. */
    fun latest(): Release? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        return client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val o = JSONObject(res.body!!.string())
            val build = Regex("(\\d+)$").find(o.optString("tag_name"))?.value?.toIntOrNull() ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
            Release(build, apk.getString("browser_download_url"), o.optString("body").trim(), o.optString("html_url"))
        }
    }

    /** Newer release than the installed one, or null. */
    fun available(ctx: Context): Release? = latest()?.takeIf { it.build > installedBuild(ctx) }

    suspend fun download(ctx: Context, r: Release, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "VidStash-${r.build}.apk")
        client.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val body = res.body!!
            val total = body.contentLength()
            var done = 0L
            apk.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(((done * 100) / total).toInt())
                    }
                }
            }
        }
        return apk
    }

    /** Android 8+ needs the user to allow installs from this app once. */
    fun canInstall(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(ctx: Context) =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

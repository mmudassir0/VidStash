package com.mudassir.vidstash

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App updates from GitHub Releases, as one shared state that the Settings card,
 * the launch check and the bottom-bar badge all follow. Downloads keep going when
 * you leave the Settings screen.
 */
object UpdateManager {

    sealed class State {
        object Idle : State()
        object Checking : State()
        data class UpToDate(val checkedAt: Long) : State()
        data class Available(val release: Updater.Release) : State()
        data class Downloading(val release: Updater.Release, val percent: Int) : State()
        /** Downloaded; waiting for the user to allow installs or to tap Install. */
        data class Ready(val release: Updater.Release, val apk: File) : State()
        data class Failed(val message: String, val release: Updater.Release?) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** Sent to Android's "allow installs" screen; install when the user comes back. */
    var waitingForPermission = false
        private set

    val busy get() = _state.value is State.Checking || _state.value is State.Downloading

    /** Looks for a newer build. [onDone] gets the release if there is one. */
    fun check(ctx: Context, onDone: (Updater.Release?) -> Unit = {}) {
        if (busy) return
        val app = ctx.applicationContext
        _state.value = State.Checking
        job = scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { Updater.latest() } }
            Settings(app).lastUpdateCheck = System.currentTimeMillis()
            val release = result.getOrNull()
            _state.value = when {
                result.isFailure || release == null -> State.Failed(app.getString(R.string.upd_no_github), null)
                release.build > Updater.installedBuild(app) -> State.Available(release)
                else -> State.UpToDate(System.currentTimeMillis())
            }
            onDone(release?.takeIf { it.build > Updater.installedBuild(app) })
        }
    }

    /** Downloads the update, then opens Android's installer. */
    fun downloadAndInstall(ctx: Context, release: Updater.Release) {
        if (busy) return
        val app = ctx.applicationContext
        // Already downloaded (e.g. came back after allowing installs).
        (_state.value as? State.Ready)?.takeIf { it.release.build == release.build && it.apk.exists() }?.let {
            install(app)
            return
        }
        _state.value = State.Downloading(release, -1)
        job = scope.launch {
            val apk = runCatching {
                withContext(Dispatchers.IO) {
                    Updater.download(app, release) { pct ->
                        scope.launch { _state.value = State.Downloading(release, pct) }
                    }
                }
            }
            apk.onSuccess {
                _state.value = State.Ready(release, it)
                install(app)
            }.onFailure {
                _state.value = State.Failed(app.getString(R.string.upd_download_failed, it.message ?: app.getString(R.string.common_unknown_error)), release)
            }
        }
    }

    /** Opens the installer, or Android's "allow installs from this app" screen first. */
    fun install(ctx: Context) {
        val ready = _state.value as? State.Ready ?: return
        if (!Updater.canInstall(ctx)) {
            waitingForPermission = true
            ctx.startActivity(Updater.allowInstallsIntent(ctx).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        waitingForPermission = false
        Updater.install(ctx, ready.apk)
    }

    fun cancel() {
        job?.cancel()
        val s = _state.value
        _state.value = if (s is State.Downloading) State.Available(s.release) else State.Idle
    }

    /** Removes old downloaded APKs (e.g. after updating). */
    fun cleanUp(ctx: Context) {
        val dir = File(ctx.cacheDir, "updates")
        val installed = Updater.installedBuild(ctx)
        dir.listFiles()?.forEach { f ->
            val build = Regex("(\\d+)\\.apk$").find(f.name)?.groupValues?.get(1)?.toIntOrNull()
            if (build == null || build <= installed) f.delete()
        }
    }
}

package com.mudassir.vidstash

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.widget.PopupMenu
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import coil.load
import com.google.common.util.concurrent.ListenableFuture
import com.mudassir.vidstash.databinding.ActivityPlayerBinding
import kotlinx.coroutines.launch

/**
 * Full-screen player for downloaded files. Plays a whole list (next file starts by itself),
 * continues where you left off, and has speed, sleep timer, rotate, double-tap to skip
 * and picture-in-picture.
 */
class PlayerActivity : AppCompatActivity() {

    private data class Entry(val uri: String, val title: String, val mime: String?, val thumb: String?) {
        val isAudio get() = mime?.startsWith("audio") == true
    }

    private lateinit var b: ActivityPlayerBinding
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var entries: List<Entry> = emptyList()
    private var startIndex = 0

    /** True until the list from the latest intent has been handed to the player. */
    private var pendingLoad = true
    private var inPip = false
    private val handler = Handler(Looper.getMainLooper())

    private val pipSupported by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateForCurrent()
            updatePip()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePip()
        override fun onVideoSizeChanged(videoSize: VideoSize) = updatePip()
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = updateSpeed()
    }

    /** Chapters of the file playing now (empty if it has none). */
    private var chapters: List<Chapter> = emptyList()

    private val chapterTicker = object : Runnable {
        override fun run() {
            updateChapterText()
            handler.postDelayed(this, 1000)
        }
    }

    private val sleepRefresher = object : Runnable {
        override fun run() {
            updateSleep()
            handler.postDelayed(this, 15_000)
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val c = controller ?: return
            when (intent.getIntExtra(EXTRA_PIP_CMD, 0)) {
                PIP_PLAY_PAUSE -> if (c.isPlaying) c.pause() else c.play()
                PIP_BACK -> c.seekTo((c.currentPosition - SKIP_MS).coerceAtLeast(0))
                PIP_FORWARD -> c.seekTo(c.currentPosition + SKIP_MS)
            }
        }
    }

    // ---------- double tap to skip ----------

    private var tapStartedWithControlsHidden = false
    private var lastDownAt = 0L

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val c = controller ?: return false
                if (!tapStartedWithControlsHidden || inPip) return false
                val forward = e.x > b.root.width / 2f
                c.seekTo(if (forward) c.currentPosition + SKIP_MS else (c.currentPosition - SKIP_MS).coerceAtLeast(0))
                showHint(if (forward) "+10 s  ⏩" else "⏪  −10 s")
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            // Remember the state at the first tap of a possible double tap.
            if (now - lastDownAt > 400) tapStartedWithControlsHidden = !b.playerView.isControllerFullyVisible
            lastDownAt = now
        }
        val onTopBar = b.topBar.isVisible && ev.y < b.topBar.bottom
        if (!onTopBar) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun showHint(text: String) {
        b.seekHint.text = text
        b.seekHint.isVisible = true
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, 700)
    }

    private val hideHint = Runnable { b.seekHint.isVisible = false }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        // Keep the top bar clear of a camera cut-out.
        ViewCompat.setOnApplyWindowInsetsListener(b.topBar) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(cut.left + dp(4), cut.top + dp(4), cut.right + dp(4), dp(4))
            insets
        }

        readIntent(intent)
        if (entries.isEmpty()) {
            finish()
            return
        }

        b.playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (!inPip) b.topBar.visibility = visibility
        })
        b.backButton.setOnClickListener { finish() }
        b.speedButton.setOnClickListener { showSpeedMenu() }
        b.sleepButton.setOnClickListener { showSleepMenu() }
        b.chaptersButton.setOnClickListener { showChaptersMenu() }
        b.downloadButton.setOnClickListener {
            val e = currentEntry() ?: return@setOnClickListener
            if (!StreamCache.isStream(e.uri)) return@setOnClickListener
            Queue.add(
                this, listOf(
                    DownloadItem(
                        url = StreamCache.urlOf(e.uri), title = e.title, thumbnail = e.thumb,
                        maxHeight = Settings(this).defaultQuality
                    )
                )
            )
            Toast.makeText(this, "Added to downloads", Toast.LENGTH_SHORT).show()
        }
        b.sleepText.setOnClickListener { showSleepMenu() }
        b.rotateButton.setOnClickListener { rotate() }
        b.pipButton.isVisible = pipSupported
        b.pipButton.setOnClickListener { enterPip() }

        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SleepTimer.state.collect { updateSleep() }
            }
        }
        updateForCurrent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        if (entries.isEmpty()) return
        pendingLoad = true
        controller?.let { load(it) }
    }

    override fun onStart() {
        super.onStart()
        handler.post(sleepRefresher)
        handler.post(chapterTicker)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            if (future !== f) return@addListener
            controller = c
            c.addListener(listener)
            b.playerView.player = c
            load(c)
            updateForCurrent()
            updateSpeed()
            updatePip()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(sleepRefresher)
        handler.removeCallbacks(chapterTicker)
        // Video pauses when you leave; audio keeps playing in the background.
        val current = currentEntry()
        controller?.let { c ->
            if (current?.isAudio != true) c.pause()
            c.removeListener(listener)
        }
        b.playerView.player = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        // Closing the picture-in-picture window ends the player.
        if (inPip) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(pipReceiver) }
        super.onDestroy()
    }

    private fun readIntent(intent: Intent) {
        val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
        val mimes = intent.getStringArrayListExtra(EXTRA_MIMES).orEmpty()
        val thumbs = intent.getStringArrayListExtra(EXTRA_THUMBS).orEmpty()
        entries = uris.mapIndexed { i, u ->
            Entry(
                uri = u,
                title = titles.getOrNull(i).orEmpty(),
                mime = mimes.getOrNull(i)?.ifEmpty { null },
                thumb = thumbs.getOrNull(i)?.ifEmpty { null }
            )
        }
        startIndex = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, (entries.size - 1).coerceAtLeast(0))
    }

    /** Hands the list to the player, unless it is already playing this list. */
    private fun load(c: MediaController) {
        if (pendingLoad) {
            pendingLoad = false
            val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
            val start = entries[startIndex]
            val pos = Settings(this).position(start.uri)
            when {
                ids == entries.map { it.uri } && c.currentMediaItemIndex == startIndex -> {}
                ids == entries.map { it.uri } -> c.seekTo(startIndex, pos)
                else -> {
                    c.setMediaItems(entries.map { it.toMediaItem() }, startIndex, pos)
                    c.prepare()
                }
            }
        } else if (c.mediaItemCount == 0) {
            c.setMediaItems(entries.map { it.toMediaItem() }, startIndex, 0)
            c.prepare()
        }
        c.play()
    }

    private fun Entry.toMediaItem() = MediaItem.Builder()
        .setMediaId(uri)
        .setUri(Uri.parse(uri))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtworkUri(thumb?.let(Uri::parse))
                .build()
        )
        .build()

    private fun currentEntry(): Entry? {
        val id = controller?.currentMediaItem?.mediaId ?: return entries.getOrNull(startIndex)
        return entries.firstOrNull { it.uri == id }
    }

    private fun updateForCurrent() {
        val e = currentEntry()
        b.titleText.text = e?.title ?: controller?.mediaMetadata?.title ?: ""
        chapters = e?.let { chaptersFor(it.uri) }.orEmpty()
        b.chaptersButton.isVisible = chapters.size >= 2
        b.downloadButton.isVisible = e != null && StreamCache.isStream(e.uri)
        // Chapter starts as small marks on the seek bar.
        val marks = chapters.map { it.startSec * 1000 }.filter { it > 0 }
        b.playerView.setExtraAdGroupMarkers(marks.toLongArray(), BooleanArray(marks.size))
        updateChapterText()
        val audio = e?.isAudio == true
        b.artwork.isVisible = audio
        if (audio) b.artwork.load(e?.thumb)
        b.playerView.controllerShowTimeoutMs = if (audio) 0 else 3000 // keep controls up for music
        b.rotateButton.isVisible = !audio
        b.pipButton.isVisible = pipSupported && !audio
    }

    // ---------- chapters ----------

    private fun chaptersFor(uri: String): List<Chapter> =
        Store.items.value.firstOrNull { it.fileUri == uri }?.chapters?.takeIf { it.isNotEmpty() }
            ?: StreamCache.chapters(uri)

    private fun currentChapterIndex(): Int {
        val pos = (controller?.currentPosition ?: return -1) / 1000
        return chapters.indexOfLast { it.startSec <= pos }
    }

    private fun updateChapterText() {
        val i = if (chapters.size >= 2) currentChapterIndex() else -1
        val text = chapters.getOrNull(i)?.let { "${i + 1}/${chapters.size} · ${it.title}" }
        b.chapterText.text = text
        b.chapterText.isVisible = text != null
    }

    private fun showChaptersMenu() {
        val current = currentChapterIndex()
        PopupMenu(this, b.chaptersButton).apply {
            chapters.forEachIndexed { i, c ->
                menu.add(0, i, i, (if (i == current) "▶  " else "") + "${formatDuration(c.startSec)}   ${c.title}")
            }
            setOnMenuItemClickListener {
                controller?.seekTo(chapters[it.itemId].startSec * 1000)
                updateChapterText()
                true
            }
        }.show()
    }

    // ---------- speed ----------

    private fun updateSpeed() {
        val s = controller?.playbackParameters?.speed ?: Settings(this).playbackSpeed
        b.speedButton.text = speedLabel(s)
    }

    private fun showSpeedMenu() {
        PopupMenu(this, b.speedButton).apply {
            SPEEDS.forEachIndexed { i, s -> menu.add(0, i, i, speedLabel(s) + if (s == 1f) "  (normal)" else "") }
            setOnMenuItemClickListener {
                controller?.setPlaybackSpeed(SPEEDS[it.itemId])
                Settings(this@PlayerActivity).playbackSpeed = SPEEDS[it.itemId]
                b.speedButton.text = speedLabel(SPEEDS[it.itemId])
                true
            }
        }.show()
    }

    private fun speedLabel(s: Float): String =
        (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "×"

    // ---------- sleep timer ----------

    private fun showSleepMenu() {
        PopupMenu(this, b.sleepButton).apply {
            val active = SleepTimer.state.value != SleepTimer.State.Off
            if (active) menu.add(0, 0, 0, "Turn off sleep timer")
            SLEEP_MINUTES.forEachIndexed { i, m -> menu.add(0, i + 1, i + 1, "Stop in $m minutes") }
            menu.add(0, 100, 100, "Stop at end of this video")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    0 -> SleepTimer.cancel()
                    100 -> SleepTimer.endOfItem()
                    else -> SleepTimer.set(SLEEP_MINUTES[it.itemId - 1])
                }
                val msg = when (it.itemId) {
                    0 -> "Sleep timer off"
                    100 -> "Will stop at the end of this video"
                    else -> "Will stop in ${SLEEP_MINUTES[it.itemId - 1]} minutes"
                }
                Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_SHORT).show()
                true
            }
        }.show()
    }

    private fun updateSleep() {
        val text = when (SleepTimer.state.value) {
            SleepTimer.State.Off -> null
            SleepTimer.State.EndOfItem -> "end"
            is SleepTimer.State.At -> "${SleepTimer.minutesLeft()}m"
        }
        b.sleepText.text = text
        b.sleepText.isVisible = text != null
    }

    // ---------- rotate ----------

    private fun rotate() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ---------- picture in picture ----------

    private fun canPip() = pipSupported && currentEntry()?.isAudio != true

    private fun enterPip() {
        if (!canPip() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { enterPictureInPictureMode(pipParams()) }
            .onFailure { Toast.makeText(this, "Picture-in-picture is turned off for this app in phone settings", Toast.LENGTH_LONG).show() }
    }

    private fun updatePip() {
        if (!pipSupported || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        val vs = controller?.videoSize
        val ratio = if (vs != null && vs.width > 0 && vs.height > 0) {
            val r = vs.width.toFloat() / vs.height
            when {
                r > 2.39f -> Rational(239, 100)
                r < 0.42f -> Rational(100, 239)
                else -> Rational(vs.width, vs.height)
            }
        } else Rational(16, 9)
        val playing = controller?.isPlaying == true
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(
                listOf(
                    pipAction(PIP_BACK, android.R.drawable.ic_media_rew, "Back 10 seconds"),
                    if (playing) pipAction(PIP_PLAY_PAUSE, android.R.drawable.ic_media_pause, "Pause")
                    else pipAction(PIP_PLAY_PAUSE, android.R.drawable.ic_media_play, "Play"),
                    pipAction(PIP_FORWARD, android.R.drawable.ic_media_ff, "Forward 10 seconds")
                )
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Going home while a video plays shrinks it into a small window.
            builder.setAutoEnterEnabled(playing && canPip())
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipAction(cmd: Int, icon: Int, title: String): RemoteAction {
        val pi = PendingIntent.getBroadcast(
            this, cmd,
            Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PIP_CMD, cmd),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pi)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 8–11 have no auto-enter; do it by hand when a video is playing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            controller?.isPlaying == true
        ) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        b.playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            b.playerView.hideController()
            b.topBar.isVisible = false
            b.seekHint.isVisible = false
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_MIMES = "mimes"
        private const val EXTRA_THUMBS = "thumbs"
        private const val EXTRA_START = "start"
        private const val ACTION_PIP = "com.mudassir.vidstash.PIP"
        private const val EXTRA_PIP_CMD = "cmd"
        private const val PIP_PLAY_PAUSE = 1
        private const val PIP_BACK = 2
        private const val PIP_FORWARD = 3
        private const val SKIP_MS = 10_000L

        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        private val SLEEP_MINUTES = intArrayOf(5, 10, 15, 20, 30, 45, 60, 90)

        /** Plays [items] starting at [start]; the rest play after it (if autoplay is on). */
        fun intent(ctx: Context, items: List<DownloadItem>, start: Int): Intent {
            val playable = items.filter { it.fileUri != null }
            val first = items.getOrNull(start)
            var list = playable
            var index = playable.indexOfFirst { it.id == first?.id }.coerceAtLeast(0)
            if (!Settings(ctx).autoplayNext && playable.isNotEmpty()) {
                list = listOf(playable[index])
                index = 0
            }
            return Intent(ctx, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(list.map { it.fileUri!! }))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(list.map { it.title }))
                .putStringArrayListExtra(EXTRA_MIMES, ArrayList(list.map { it.mime.orEmpty() }))
                .putStringArrayListExtra(EXTRA_THUMBS, ArrayList(list.map { it.thumbnail.orEmpty() }))
                .putExtra(EXTRA_START, index)
        }

        fun intent(ctx: Context, item: DownloadItem) = intent(ctx, listOf(item), 0)

        /** Watch YouTube videos online in the built-in player (no download). */
        fun streamIntent(ctx: Context, videos: List<Triple<String, String, String?>>, start: Int = 0): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(videos.map { StreamCache.idFor(it.first) }))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(videos.map { it.second }))
                .putStringArrayListExtra(EXTRA_MIMES, ArrayList(videos.map { "video/stream" }))
                .putStringArrayListExtra(EXTRA_THUMBS, ArrayList(videos.map { it.third.orEmpty() }))
                .putExtra(EXTRA_START, start)

        fun stream(ctx: Context, url: String, title: String, thumb: String?) =
            ctx.startActivity(streamIntent(ctx, listOf(Triple(url, title, thumb))))
    }
}

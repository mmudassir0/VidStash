package com.mudassir.ytdownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Plays downloaded files. Being a media session service, audio keeps playing with the
 * screen off or the app closed, with controls in the notification and on the lock screen.
 * It remembers where you stopped in every file, plays the next file in the list,
 * and carries out the sleep timer.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private var ticks = 0

    /** Last item and position seen, so the position can be saved when the item changes. */
    private var lastId: String? = null
    private var lastPos = 0L
    private var lastDur = 0L

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.setPlaybackSpeed(settings.playbackSpeed)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(ticker)
                if (isPlaying) {
                    tick()
                    handler.postDelayed(ticker, 1000)
                } else savePosition()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player.currentMediaItem?.mediaId?.let { settings.setPosition(it, 0) }
                }
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // Save where the previous file was left (finished files start over next time).
                lastId?.let { id ->
                    if (id != item?.mediaId) {
                        val finished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            (lastDur > 0 && lastDur - lastPos < 10_000)
                        settings.setPosition(id, if (finished) 0 else lastPos)
                    }
                }
                lastId = item?.mediaId
                lastPos = 0
                lastDur = 0
                // Jumping to another file continues where you left it.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && item != null) {
                    val saved = settings.position(item.mediaId)
                    if (saved > 0) player.seekTo(saved)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    // Paused by the "end of video" sleep timer.
                    SleepTimer.cancel()
                    player.pauseAtEndOfMediaItems = false
                }
            }

            override fun onPlaybackParametersChanged(params: androidx.media3.common.PlaybackParameters) {
                settings.playbackSpeed = params.speed
            }

            override fun onPlayerError(error: PlaybackException) {
                // A file that was deleted or can't be read: skip to the next one.
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })
        session = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            // Online videos ("yt:" items) are looked up here, off the main thread.
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                if (mediaItems.none { StreamCache.isStream(it.mediaId) }) return Futures.immediateFuture(mediaItems)
                val future = SettableFuture.create<MutableList<MediaItem>>()
                resolver.execute {
                    try {
                        future.set(mediaItems.map { resolve(it) }.toMutableList())
                    } catch (e: Exception) {
                        handler.post {
                            Toast.makeText(this@PlaybackService, "Couldn't play online: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                        }
                        future.setException(e)
                    }
                }
                return future
            }
        }).build()
    }

    private val resolver = Executors.newSingleThreadExecutor()

    /** Turns a "yt:<link>" item into playable stream links (video + audio). */
    private fun resolve(item: MediaItem): MediaItem {
        if (!StreamCache.isStream(item.mediaId)) return item
        val d = YouTube.getVideo(StreamCache.urlOf(item.mediaId), allowWebm = false)
        StreamCache.putChapters(item.mediaId, d.chapters)
        // Default quality, but at most 1080p for smooth streaming.
        val wanted = settings.defaultQuality.let { if (it == 0) 0 else it.coerceAtMost(1080) }
        val opt = YouTube.pickBest(d.options, wanted) ?: throw IllegalStateException("No playable format")
        val (videoUrl, audioUrl) = when (opt.kind) {
            Kind.MERGE -> opt.videoUrl to opt.audioUrl
            Kind.SINGLE_VIDEO -> opt.videoUrl to null
            Kind.AUDIO -> opt.audioUrl to null
        }
        val meta = item.mediaMetadata.buildUpon()
            .setTitle(item.mediaMetadata.title ?: d.title)
            .setArtist(d.uploader)
            .apply { if (item.mediaMetadata.artworkUri == null) setArtworkUri(d.thumbnail?.let(Uri::parse)) }
            .build()
        return item.buildUpon()
            .setUri(videoUrl)
            .setMediaMetadata(meta)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setExtras(Bundle().apply { if (audioUrl != null) putString(EXTRA_AUDIO_URL, audioUrl) })
                    .build()
            )
            .build()
    }

    /** Files and normal links as usual; online videos as video + audio played together. */
    private fun mediaSourceFactory(): MediaSource.Factory {
        val data = DefaultDataSource.Factory(this, YtDataSource.Factory())
        val default = DefaultMediaSourceFactory(data)
        val progressive = ProgressiveMediaSource.Factory(data)
        return object : MediaSource.Factory {
            override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
                default.setDrmSessionManagerProvider(provider)
                return this
            }

            override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
                default.setLoadErrorHandlingPolicy(policy)
                return this
            }

            override fun getSupportedTypes(): IntArray = default.supportedTypes

            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                val audio = mediaItem.requestMetadata.extras?.getString(EXTRA_AUDIO_URL)
                    ?: return default.createMediaSource(mediaItem)
                return MergingMediaSource(
                    progressive.createMediaSource(mediaItem),
                    progressive.createMediaSource(MediaItem.fromUri(audio))
                )
            }
        }
    }

    private fun tick() {
        lastId = player.currentMediaItem?.mediaId
        lastPos = player.currentPosition
        lastDur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0

        when (val t = SleepTimer.state.value) {
            is SleepTimer.State.At -> if (System.currentTimeMillis() >= t.endAtMs) {
                player.pause()
                SleepTimer.cancel()
            }
            SleepTimer.State.EndOfItem -> player.pauseAtEndOfMediaItems = true
            SleepTimer.State.Off -> player.pauseAtEndOfMediaItems = false
        }
        if (++ticks % 5 == 0) savePosition()
    }

    private fun savePosition() {
        val id = player.currentMediaItem?.mediaId ?: return
        if (player.playbackState == Player.STATE_ENDED) return
        val pos = player.currentPosition
        val dur = player.duration
        // Near the end counts as finished, so next time it starts from the beginning.
        settings.setPosition(id, if (dur > 0 && dur - pos < 10_000) 0 else pos)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        resolver.shutdownNow()
        handler.removeCallbacks(ticker)
        savePosition()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_AUDIO_URL = "audio_url"
    }
}

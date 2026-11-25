package com.example.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.MainActivity
import com.example.R
import com.example.data.LocalMediaLibrary
import com.example.safety.SafetyGateManager
import com.example.sink.VideoSinkRouter
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Foreground Media3 MediaLibraryService keeping hardware decoding, Android Auto
 * media browsing, steering-wheel / Bluetooth AVRCP media button sync, audio ducking
 * for navigation prompts, and car surface rendering alive even when locked.
 */
@OptIn(UnstableApi::class)
class VideoDeckPlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1500,
                /* maxBufferMs = */ 15000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()

        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET && player.audioSessionId > 0) {
            AudioEqualizerEngine.attachToAudioSession(player.audioSessionId)
        }

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioEqualizerEngine.attachToAudioSession(audioSessionId)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY &&
                    player.audioSessionId != C.AUDIO_SESSION_ID_UNSET &&
                    player.audioSessionId > 0
                ) {
                    AudioEqualizerEngine.attachToAudioSession(player.audioSessionId)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && SafetyGateManager.shouldPausePlayback()) {
                    player.pause()
                }
            }
        })

        VideoSinkRouter.registerPlayer(player)
        PlayerController.bindPlayer(this, player)
        exoPlayer = player

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val localLibrary = LocalMediaLibrary(this)
        val callback = object : MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            }

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val rootItem = MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(getString(R.string.app_name))
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                if (parentId != ROOT_ID) {
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
                val entries = PlayerController.librarySnapshot.value
                val start = (page * pageSize).coerceAtLeast(0)
                val end = (start + pageSize).coerceAtMost(entries.size)
                val slice = if (start < end) entries.subList(start, end) else emptyList()
                val items = slice.map { entry ->
                    MediaItem.Builder()
                        .setMediaId(entry.id)
                        .setUri(localLibrary.resolveOfflineOrSourceUri(entry.uriString, entry.id))
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(entry.displayName)
                                .setSubtitle(entry.resolutionLabel)
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                                .build()
                        )
                        .build()
                }
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val entry = PlayerController.librarySnapshot.value.firstOrNull { it.id == mediaId }
                    ?: return Futures.immediateFuture(
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    )
                val item = MediaItem.Builder()
                    .setMediaId(entry.id)
                    .setUri(localLibrary.resolveOfflineOrSourceUri(entry.uriString, entry.id))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(entry.displayName)
                            .setSubtitle(entry.resolutionLabel)
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(item, null))
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                val library = PlayerController.librarySnapshot.value
                val resolved = mediaItems.map { req ->
                    val match = library.firstOrNull { it.id == req.mediaId }
                    if (match != null) {
                        req.buildUpon()
                            .setUri(localLibrary.resolveOfflineOrSourceUri(match.uriString, match.id))
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(match.displayName)
                                    .setSubtitle(match.resolutionLabel)
                                    .build()
                            )
                            .build()
                    } else if (req.localConfiguration != null) {
                        req
                    } else {
                        req.buildUpon().setUri(Uri.EMPTY).build()
                    }
                }.toMutableList()
                return Futures.immediateFuture(resolved)
            }

            override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent
            ): Boolean {
                val keyEvent: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                    when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_HEADSETHOOK -> {
                            if (SafetyGateManager.shouldPausePlayback()) {
                                player.pause()
                                return true
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            player.seekForward()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            player.seekBack()
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(session, controllerInfo, intent)
            }

            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val saved = PlayerController.getLastResumptionSnapshot()
                return if (saved != null) {
                    Futures.immediateFuture(saved)
                } else {
                    Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            emptyList<MediaItem>(),
                            0,
                            0L
                        )
                    )
                }
            }
        }

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(sessionActivityIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        exoPlayer?.let { VideoSinkRouter.unregisterPlayer(it) }
        AudioEqualizerEngine.releaseEffects()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        exoPlayer = null
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_playback),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "videodeck_playback_channel"
        private const val ROOT_ID = "drivecast_root"
    }
}

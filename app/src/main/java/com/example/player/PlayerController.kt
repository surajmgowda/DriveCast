package com.example.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.example.car.CabinUtilityManager
import com.example.data.AspectMode
import com.example.data.DriveCastPreferences
import com.example.data.LocalMediaLibrary
import com.example.data.QualityProfile
import com.example.data.SyncRepository
import com.example.data.SyncedVideoEntry
import com.example.data.WidgetResumeSnapshot
import com.example.safety.SafetyGateManager
import com.example.sink.VideoSinkRouter
import com.example.widget.DriveCastResumeWidgetProvider
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TrackOptionUi(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val codec: String,
    val selected: Boolean
)

data class PlayerUiState(
    val activeVideoId: String? = null,
    val activeUriString: String? = null,
    val title: String = "No Video Selected",
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val aspectMode: AspectMode = AspectMode.FIT,
    val audioDelayMs: Int = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoDecoderName: String = "Hardware MediaCodec",
    val audioCodecLabel: String = "AAC / PCM",
    val droppedFrames: Int = 0,
    val audioTracks: List<TrackOptionUi> = emptyList(),
    val subtitleTracks: List<TrackOptionUi> = emptyList(),
    val errorMessage: String? = null,
    val playlistSize: Int = 0,
    val currentIndex: Int = 0,
    val queue: List<SyncedVideoEntry> = emptyList(),
    val externalSubtitleName: String? = null,
    val externalSubtitleCount: Int = 0,
    val subtitleOffsetMs: Int = 0,
    val abLoopStartMs: Long? = null,
    val abLoopEndMs: Long? = null
)

/**
 * Shared playback coordinator connecting UI, CarAppService, Home Widget, and MediaSessionService.
 */
@OptIn(UnstableApi::class)
object PlayerController {
    private const val TAG = "PlayerController"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var positionTickerJob: Job? = null

    private data class PendingPlayRequest(
        val queue: List<SyncedVideoEntry>,
        val startIndex: Int,
        val resolveUri: (SyncedVideoEntry) -> Uri,
        val autoResume: Boolean
    )

    private var appContext: Context? = null
    private var boundExoPlayer: ExoPlayer? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var pendingPlayRequest: PendingPlayRequest? = null
    private var pendingAutoPlayNext: Boolean? = null
    private var pendingQualityProfile: QualityProfile? = null
    private var safetyListener: ((Boolean, String?) -> Unit)? = null

    private var currentQueue: MutableList<SyncedVideoEntry> = mutableListOf()
    private var externalSubtitleCues: List<ExternalSubtitleCue> = emptyList()
    private var onProgressCheckpoint: ((SyncedVideoEntry, Long, Int, Int, Float, AspectMode, Int) -> Unit)? = null

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _cuesFlow = MutableStateFlow<List<Cue>>(emptyList())
    val cuesFlow: StateFlow<List<Cue>> = _cuesFlow.asStateFlow()

    private val _librarySnapshot = MutableStateFlow<List<SyncedVideoEntry>>(emptyList())
    val librarySnapshot: StateFlow<List<SyncedVideoEntry>> = _librarySnapshot.asStateFlow()

    fun initAppContext(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        ensureLibraryHydrated(appCtx)
    }

    fun ensureServiceConnected(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        runCatching { appCtx.startService(Intent(appCtx, VideoDeckPlaybackService::class.java)) }
        if (mediaController != null || controllerFuture != null) return
        val token = SessionToken(
            appCtx,
            ComponentName(appCtx, VideoDeckPlaybackService::class.java)
        )
        val future = MediaController.Builder(appCtx, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                mediaController = future.get()
            } catch (e: Exception) {
                controllerFuture = null
                Log.w(TAG, "MediaController connection error: ${e.message}")
            }
        }, MoreExecutors.directExecutor())
    }

    fun setProgressCheckpointListener(
        listener: (SyncedVideoEntry, Long, Int, Int, Float, AspectMode, Int) -> Unit
    ) {
        onProgressCheckpoint = listener
    }

    fun ensureLibraryHydrated(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (_librarySnapshot.value.isEmpty()) {
            val cached = DriveCastPreferences(appCtx).readVideoLibrarySnapshot()
            _librarySnapshot.value = cached.ifEmpty { SyncRepository.DEFAULT_SAMPLE_VIDEOS }
        }
    }

    fun updateLibrarySnapshot(videos: List<SyncedVideoEntry>) {
        _librarySnapshot.value = videos
        if (videos.isNotEmpty()) {
            appContext?.let { DriveCastPreferences(it).saveVideoLibrarySnapshot(videos) }
        }
    }

    fun setAutoPlayNextEnabled(enabled: Boolean) {
        pendingAutoPlayNext = enabled
        boundExoPlayer?.pauseAtEndOfMediaItems = !enabled
    }

    fun bindPlayer(context: Context, player: ExoPlayer) {
        appContext = context.applicationContext
        boundExoPlayer = player
        safetyListener?.let { SafetyGateManager.removeBlockListener(it) }
        val listener: (Boolean, String?) -> Unit = { _, _ ->
            if (SafetyGateManager.shouldPausePlayback() && player.isPlaying) {
                player.pause()
            }
        }
        safetyListener = listener
        SafetyGateManager.initialize(context, listener)

        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET && player.audioSessionId > 0) {
            AudioEqualizerEngine.attachToAudioSession(player.audioSessionId)
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) startTicker() else stopTickerAndCheckpoint()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateLabel = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                VideoPlayerDiagnosticLogger.log(
                    source = "ExoPlayer",
                    message = "Playback state -> $stateLabel",
                    level = PlayerDiagLevel.INFO
                )
                if (playbackState == Player.STATE_READY) {
                    if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET && player.audioSessionId > 0) {
                        AudioEqualizerEngine.attachToAudioSession(player.audioSessionId)
                    }
                    VideoSinkRouter.ensureActiveSurfaceAttached()
                }
                if (playbackState == Player.STATE_ENDED) {
                    CabinUtilityManager.onVideoPlaybackEnded()
                }
                _uiState.update {
                    it.copy(
                        playbackState = playbackState,
                        durationMs = player.duration.coerceAtLeast(0L)
                    )
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                if (externalSubtitleCues.isEmpty()) {
                    _cuesFlow.value = cueGroup.cues
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = player.currentMediaItemIndex
                val entry = currentQueue.getOrNull(idx)
                externalSubtitleCues = emptyList()
                _cuesFlow.value = emptyList()
                _uiState.update {
                    it.copy(
                        activeVideoId = entry?.id,
                        activeUriString = entry?.uriString,
                        title = mediaItem?.mediaMetadata?.title?.toString()
                            ?: entry?.displayName ?: "Video",
                        currentIndex = idx,
                        durationMs = player.duration.coerceAtLeast(0L),
                        externalSubtitleName = null,
                        externalSubtitleCount = 0,
                        abLoopStartMs = null,
                        abLoopEndMs = null,
                        errorMessage = null
                    )
                }
                updateWidgetSnapshot()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    VideoPlayerDiagnosticLogger.log(
                        source = "ExoPlayer",
                        message = "Video frame size -> ${videoSize.width}x${videoSize.height}",
                        level = PlayerDiagLevel.EVENT
                    )
                }
                _uiState.update {
                    it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                extractTracks(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error", error)
                VideoPlayerDiagnosticLogger.log(
                    source = "MediaCodecVideoRenderer",
                    message = "PlaybackException [Code=${error.errorCodeName}]: ${error.localizedMessage ?: error.message}",
                    level = PlayerDiagLevel.ERROR
                )
                _uiState.update {
                    it.copy(
                        errorMessage = error.localizedMessage
                            ?: "Unsupported format or unreadable file."
                    )
                }
            }
        })

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                _uiState.update { it.copy(videoDecoderName = decoderName) }
                val isSoftware = decoderName.lowercase().contains(".sw.") ||
                    decoderName.lowercase().contains("swcodec") ||
                    decoderName.lowercase().contains("google") ||
                    decoderName.lowercase().contains("software")
                if (isSoftware) {
                    Log.w(TAG, "WARNING: Hardware acceleration failed on vehicle hardware. Fell back to software decoder: $decoderName")
                    VideoPlayerDiagnosticLogger.log(
                        source = "MediaCodecVideoRenderer",
                        message = "WARNING: Hardware acceleration failed on vehicle hardware. Fell back to software decoder: $decoderName",
                        level = PlayerDiagLevel.WARN
                    )
                } else {
                    VideoPlayerDiagnosticLogger.log(
                        source = "MediaCodecVideoRenderer",
                        message = "Successfully initialized forced hardware decoder '$decoderName' in ${initializationDurationMs}ms",
                        level = PlayerDiagLevel.EVENT
                    )
                }
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                val codec = format.codecs ?: format.sampleMimeType ?: "PCM"
                val hz = if (format.sampleRate > 0) " • ${format.sampleRate / 1000}kHz" else ""
                val ch = if (format.channelCount > 0) " • ${format.channelCount}ch" else ""
                val label = "$codec$hz$ch"
                _uiState.update { it.copy(audioCodecLabel = label) }
                AudioEqualizerEngine.updateActiveAudioCodec(label)
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                _uiState.update { it.copy(droppedFrames = it.droppedFrames + droppedFrames) }
            }
        })

        pendingAutoPlayNext?.let { player.pauseAtEndOfMediaItems = !it }
        pendingQualityProfile?.let { applyQualityProfile(it) }
        val pending = pendingPlayRequest
        pendingPlayRequest = null
        if (pending != null) {
            playVideoQueue(pending.queue, pending.startIndex, pending.resolveUri, pending.autoResume)
        }
    }

    fun unbindPlayer(player: ExoPlayer) {
        if (boundExoPlayer === player) {
            positionTickerJob?.cancel()
            positionTickerJob = null
            boundExoPlayer = null
        }
        safetyListener?.let { SafetyGateManager.removeBlockListener(it) }
        safetyListener = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }

    fun getExoPlayer(): ExoPlayer? = boundExoPlayer

    private fun buildMediaItem(entry: SyncedVideoEntry, uri: Uri): MediaItem {
        return MediaItem.Builder()
            .setMediaId(entry.id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.displayName)
                    .setSubtitle(entry.resolutionLabel)
                    .build()
            )
            .build()
    }

    fun playVideoQueue(
        queue: List<SyncedVideoEntry>,
        startIndex: Int,
        resolveUri: (SyncedVideoEntry) -> Uri,
        autoResume: Boolean
    ) {
        if (queue.isEmpty()) return
        currentQueue = queue.toMutableList()
        val safeIdx = startIndex.coerceIn(0, currentQueue.lastIndex)
        val target = currentQueue[safeIdx]

        externalSubtitleCues = emptyList()
        _cuesFlow.value = emptyList()
        _uiState.update {
            it.copy(
                activeVideoId = target.id,
                activeUriString = target.uriString,
                title = target.displayName,
                aspectMode = AspectMode.fromRaw(target.aspectMode),
                playbackSpeed = target.playbackSpeed.toFloat(),
                audioDelayMs = target.audioDelayMs,
                playlistSize = currentQueue.size,
                currentIndex = safeIdx,
                queue = currentQueue.toList(),
                externalSubtitleName = null,
                externalSubtitleCount = 0,
                abLoopStartMs = null,
                abLoopEndMs = null,
                droppedFrames = 0,
                errorMessage = null
            )
        }

        val player = boundExoPlayer
        if (player == null) {
            pendingPlayRequest = PendingPlayRequest(queue, startIndex, resolveUri, autoResume)
            appContext?.let { ensureServiceConnected(it) }
            return
        }

        val mediaItems = currentQueue.map { entry -> buildMediaItem(entry, resolveUri(entry)) }

        val startPos = if (autoResume && target.lastPositionMs > 2000L &&
            (target.durationMs <= 0L || target.lastPositionMs < target.durationMs - 3000L)
        ) {
            target.lastPositionMs
        } else {
            0L
        }

        VideoPlayerDiagnosticLogger.log(
            source = "PlayerController",
            message = "playVideoQueue(${target.displayName}, uri=${resolveUri(target)})",
            level = PlayerDiagLevel.EVENT
        )
        player.setMediaItems(mediaItems, safeIdx, startPos)
        player.playbackParameters = PlaybackParameters(target.playbackSpeed.toFloat().coerceIn(0.25f, 4.0f))
        player.prepare()
        VideoSinkRouter.ensureActiveSurfaceAttached()
        if (!SafetyGateManager.shouldPausePlayback()) {
            player.play()
        }
        updateWidgetSnapshot()
    }

    fun playFromCarLibrary(entry: SyncedVideoEntry) {
        val ctx = appContext ?: return
        ensureLibraryHydrated(ctx)
        val lib = LocalMediaLibrary(ctx)
        val queue = _librarySnapshot.value.ifEmpty { listOf(entry) }
        val startIdx = queue.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
        playVideoQueue(
            queue = queue,
            startIndex = startIdx,
            resolveUri = { item -> lib.resolveOfflineOrSourceUri(item.uriString, item.id) },
            autoResume = true
        )
    }

    fun addToQueueNext(entry: SyncedVideoEntry, resolveUri: (SyncedVideoEntry) -> Uri): Boolean {
        val player = boundExoPlayer
        if (player == null || currentQueue.isEmpty() || player.mediaItemCount == 0) {
            playVideoQueue(listOf(entry), 0, resolveUri, autoResume = true)
            return true
        }
        val insertIdx = (player.currentMediaItemIndex + 1).coerceIn(0, currentQueue.size)
        currentQueue.add(insertIdx, entry)
        player.addMediaItem(insertIdx, buildMediaItem(entry, resolveUri(entry)))
        _uiState.update {
            it.copy(
                playlistSize = currentQueue.size,
                queue = currentQueue.toList()
            )
        }
        return true
    }

    fun addToQueueEnd(entry: SyncedVideoEntry, resolveUri: (SyncedVideoEntry) -> Uri): Boolean {
        val player = boundExoPlayer
        if (player == null || currentQueue.isEmpty() || player.mediaItemCount == 0) {
            playVideoQueue(listOf(entry), 0, resolveUri, autoResume = true)
            return true
        }
        currentQueue.add(entry)
        player.addMediaItem(buildMediaItem(entry, resolveUri(entry)))
        _uiState.update {
            it.copy(
                playlistSize = currentQueue.size,
                queue = currentQueue.toList()
            )
        }
        return true
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val player = boundExoPlayer ?: return
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices || fromIndex == toIndex) return
        val item = currentQueue.removeAt(fromIndex)
        currentQueue.add(toIndex, item)
        player.moveMediaItem(fromIndex, toIndex)
        _uiState.update {
            it.copy(
                currentIndex = player.currentMediaItemIndex,
                playlistSize = currentQueue.size,
                queue = currentQueue.toList()
            )
        }
    }

    fun removeQueueItem(index: Int) {
        val player = boundExoPlayer ?: return
        if (index !in currentQueue.indices || currentQueue.size <= 1) return
        currentQueue.removeAt(index)
        player.removeMediaItem(index)
        _uiState.update {
            it.copy(
                currentIndex = player.currentMediaItemIndex,
                playlistSize = currentQueue.size,
                queue = currentQueue.toList()
            )
        }
    }

    fun jumpToQueueIndex(index: Int) {
        val player = boundExoPlayer ?: return
        if (index !in currentQueue.indices) return
        player.seekTo(index, 0L)
        if (!SafetyGateManager.shouldPausePlayback()) {
            player.play()
        }
    }

    fun applyQualityProfile(profile: QualityProfile) {
        pendingQualityProfile = profile
        val player = boundExoPlayer ?: return
        val params = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, profile.maxHeight)
            .setMaxVideoFrameRate(profile.targetFps)
            .setMaxVideoBitrate(profile.targetBitrateMbps * 1_000_000)
            .build()
        player.trackSelectionParameters = params
    }

    fun pausePlayback() {
        boundExoPlayer?.pause()
    }

    fun togglePlayPause() {
        val player = boundExoPlayer ?: return
        if (SafetyGateManager.shouldPausePlayback()) {
            player.pause()
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val player = boundExoPlayer ?: return
        val clamped = positionMs.coerceIn(0L, player.duration.coerceAtLeast(0L))
        player.seekTo(clamped)
        _uiState.update { it.copy(positionMs = clamped) }
        refreshExternalSubtitleCues(clamped)
    }

    fun seekBy(deltaMs: Long) {
        val player = boundExoPlayer ?: return
        seekTo(player.currentPosition + deltaMs)
    }

    fun seekNext() {
        val player = boundExoPlayer ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        }
    }

    fun seekPrevious() {
        val player = boundExoPlayer ?: return
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0L)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 2.5f)
        boundExoPlayer?.playbackParameters = PlaybackParameters(clamped)
        _uiState.update { it.copy(playbackSpeed = clamped) }
        emitCheckpoint()
    }

    fun cycleAspectMode() {
        val all = AspectMode.entries
        val currentIdx = all.indexOf(_uiState.value.aspectMode).coerceAtLeast(0)
        val next = all[(currentIdx + 1) % all.size]
        _uiState.update { it.copy(aspectMode = next) }
        emitCheckpoint()
    }

    fun setAspectMode(mode: AspectMode) {
        _uiState.update { it.copy(aspectMode = mode) }
        emitCheckpoint()
    }

    fun setAudioDelayMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(-500, 500)
        _uiState.update { it.copy(audioDelayMs = clamped) }
        emitCheckpoint()
    }

    fun loadExternalSubtitle(context: Context, uri: Uri): Result<String> {
        return try {
            val (name, parsed) = ExternalSubtitleParser.loadFromUri(context, uri)
            if (parsed.isEmpty()) {
                return Result.failure(IllegalArgumentException("No valid .srt or .vtt cues found in file"))
            }
            externalSubtitleCues = parsed
            boundExoPlayer?.let { player ->
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            _uiState.update {
                it.copy(
                    externalSubtitleName = name,
                    externalSubtitleCount = parsed.size
                )
            }
            val pos = boundExoPlayer?.currentPosition ?: _uiState.value.positionMs
            refreshExternalSubtitleCues(pos)
            Result.success("$name (${parsed.size} lines)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun clearExternalSubtitle() {
        externalSubtitleCues = emptyList()
        _cuesFlow.value = emptyList()
        _uiState.update {
            it.copy(
                externalSubtitleName = null,
                externalSubtitleCount = 0
            )
        }
    }

    fun setSubtitleOffsetMs(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(-5000, 5000)
        _uiState.update { it.copy(subtitleOffsetMs = clamped) }
        val pos = boundExoPlayer?.currentPosition ?: _uiState.value.positionMs
        refreshExternalSubtitleCues(pos)
    }

    fun setAbLoopStart() {
        val pos = (boundExoPlayer?.currentPosition ?: _uiState.value.positionMs).coerceAtLeast(0L)
        _uiState.update { state ->
            val keepEnd = state.abLoopEndMs?.takeIf { it > pos + 500L }
            state.copy(abLoopStartMs = pos, abLoopEndMs = keepEnd)
        }
    }

    fun setAbLoopEnd() {
        val pos = (boundExoPlayer?.currentPosition ?: _uiState.value.positionMs).coerceAtLeast(0L)
        _uiState.update { state ->
            val start = state.abLoopStartMs ?: 0L
            if (pos <= start + 500L) {
                state
            } else {
                state.copy(abLoopStartMs = start, abLoopEndMs = pos)
            }
        }
    }

    fun clearAbLoop() {
        _uiState.update { it.copy(abLoopStartMs = null, abLoopEndMs = null) }
    }

    private fun refreshExternalSubtitleCues(positionMs: Long) {
        if (externalSubtitleCues.isEmpty()) return
        val texts = ExternalSubtitleParser.findActiveCueTexts(
            cues = externalSubtitleCues,
            playbackPositionMs = positionMs,
            subtitleOffsetMs = _uiState.value.subtitleOffsetMs
        )
        _cuesFlow.value = texts.map { line -> Cue.Builder().setText(line).build() }
    }

    fun selectAudioTrack(option: TrackOptionUi) {
        val player = boundExoPlayer ?: return
        val groups = player.currentTracks.groups
        val group = groups.getOrNull(option.groupIndex) ?: return
        val params = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
        player.trackSelectionParameters = params
    }

    fun selectSubtitleTrack(option: TrackOptionUi?) {
        val player = boundExoPlayer ?: return
        val builder = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            if (externalSubtitleCues.isEmpty()) {
                _cuesFlow.value = emptyList()
            }
        } else {
            externalSubtitleCues = emptyList()
            _uiState.update { it.copy(externalSubtitleName = null, externalSubtitleCount = 0) }
            val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        player.trackSelectionParameters = builder.build()
    }

    fun getLastResumptionSnapshot(): MediaSession.MediaItemsWithStartPosition? {
        val player = boundExoPlayer ?: return null
        if (player.mediaItemCount == 0) return null
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        return MediaSession.MediaItemsWithStartPosition(
            items,
            player.currentMediaItemIndex,
            player.currentPosition.coerceAtLeast(0L)
        )
    }

    private fun extractTracks(tracks: Tracks) {
        val audioList = mutableListOf<TrackOptionUi>()
        val subList = mutableListOf<TrackOptionUi>()
        tracks.groups.forEachIndexed { gIdx, group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (tIdx in 0 until group.length) {
                        val fmt = group.getTrackFormat(tIdx)
                        val lang = fmt.language?.uppercase() ?: "UND"
                        val label = fmt.label ?: "$lang Audio ${tIdx + 1}"
                        audioList += TrackOptionUi(
                            groupIndex = gIdx,
                            trackIndex = tIdx,
                            label = label,
                            codec = fmt.codecs ?: fmt.sampleMimeType ?: "Audio",
                            selected = group.isTrackSelected(tIdx)
                        )
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (tIdx in 0 until group.length) {
                        val fmt = group.getTrackFormat(tIdx)
                        val lang = fmt.language?.uppercase() ?: "SUB"
                        val label = fmt.label ?: "$lang Subtitles ${tIdx + 1}"
                        subList += TrackOptionUi(
                            groupIndex = gIdx,
                            trackIndex = tIdx,
                            label = label,
                            codec = fmt.sampleMimeType ?: "Text",
                            selected = group.isTrackSelected(tIdx)
                        )
                    }
                }
            }
        }
        _uiState.update { it.copy(audioTracks = audioList, subtitleTracks = subList) }
    }

    private fun startTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = scope.launch {
            var elapsedSinceCheckpointMs = 0L
            while (isActive) {
                val player = boundExoPlayer
                val currentState = _uiState.value
                val highFreq = externalSubtitleCues.isNotEmpty() ||
                    (currentState.abLoopStartMs != null && currentState.abLoopEndMs != null)
                val stepMs = if (highFreq) 200L else 500L

                if (player != null) {
                    var pos = player.currentPosition.coerceAtLeast(0L)
                    val abStart = currentState.abLoopStartMs
                    val abEnd = currentState.abLoopEndMs
                    if (abStart != null && abEnd != null && abEnd > abStart && pos >= abEnd) {
                        player.seekTo(abStart)
                        pos = abStart
                    }
                    refreshExternalSubtitleCues(pos)
                    _uiState.update {
                        it.copy(
                            positionMs = pos,
                            durationMs = player.duration.coerceAtLeast(0L)
                        )
                    }
                    elapsedSinceCheckpointMs += stepMs
                    if (elapsedSinceCheckpointMs >= 5000L) {
                        elapsedSinceCheckpointMs = 0L
                        emitCheckpoint()
                    }
                }
                delay(stepMs)
            }
        }
    }

    private fun stopTickerAndCheckpoint() {
        positionTickerJob?.cancel()
        positionTickerJob = null
        emitCheckpoint()
    }

    fun emitCheckpoint() {
        val state = _uiState.value
        val activeEntry = currentQueue.getOrNull(state.currentIndex) ?: return
        val posMs = boundExoPlayer?.currentPosition?.coerceAtLeast(0L) ?: state.positionMs
        val selectedAudio = state.audioTracks.indexOfFirst { it.selected }
        val selectedSub = state.subtitleTracks.indexOfFirst { it.selected }
        onProgressCheckpoint?.invoke(
            activeEntry,
            posMs,
            selectedAudio,
            selectedSub,
            state.playbackSpeed,
            state.aspectMode,
            state.audioDelayMs
        )
        updateWidgetSnapshot(posMs)
    }

    private fun updateWidgetSnapshot(positionOverrideMs: Long? = null) {
        val ctx = appContext ?: return
        val state = _uiState.value
        val activeId = state.activeVideoId ?: return
        val pos = positionOverrideMs ?: boundExoPlayer?.currentPosition?.coerceAtLeast(0L) ?: state.positionMs
        DriveCastPreferences(ctx).saveWidgetSnapshot(
            WidgetResumeSnapshot(
                videoId = activeId,
                title = state.title,
                positionMs = pos,
                durationMs = state.durationMs,
                activeEqName = AudioEqualizerEngine.state.value.activePresetName
            )
        )
        DriveCastResumeWidgetProvider.refreshAllWidgets(ctx)
    }
}

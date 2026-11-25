package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.media.AudioManager
import android.provider.Settings
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.example.R
import com.example.car.CabinUtilityState
import com.example.data.AspectMode
import com.example.data.CabinPreferencesState
import com.example.data.SubtitleStyleConfig
import com.example.data.SyncedEqPreset
import com.example.player.EqualizerState
import com.example.player.PlayerController
import com.example.player.PlayerUiState
import com.example.safety.SafetyGateManager
import com.example.safety.SafetyGateState
import com.example.sink.SinkType
import com.example.sink.VideoSinkRouter
import com.example.sink.VideoSinkTelemetry
import com.example.viewmodel.UiState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlayerCockpitScreen(
    playerState: PlayerUiState,
    cues: List<Cue>,
    eqState: EqualizerState,
    presetsState: UiState<List<SyncedEqPreset>>,
    cabinPrefs: CabinPreferencesState,
    cabinUtility: CabinUtilityState,
    activeAudioRoute: String,
    safetyState: SafetyGateState,
    sinkTelemetry: VideoSinkTelemetry,
    isInPipMode: Boolean,
    onSelectPreset: (SyncedEqPreset, List<SyncedEqPreset>) -> Unit,
    onSaveCustomPreset: (String, List<SyncedEqPreset>) -> Unit,
    onResetFlat: () -> Unit,
    onUpdateSubtitleStyle: (SubtitleStyleConfig) -> Unit,
    onSetAutoPlayNext: (Boolean) -> Unit,
    onSetPhoneRemoteMode: (Boolean) -> Unit,
    onStartSleepTimer: (Int) -> Unit,
    onSetSleepEndOfVideo: (Boolean) -> Unit,
    onCancelSleepTimer: () -> Unit,
    onDismissChargingAlert: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenDeck: () -> Unit
) {
    var showTracksDialog by remember { mutableStateOf(false) }
    var showLiveEqSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    if (isInPipMode) {
        VideoSurfaceViewport(
            playerState = playerState,
            cues = cues,
            subtitleStyle = cabinPrefs.subtitleStyle,
            cabinUtility = cabinUtility,
            safetyState = safetyState,
            sinkTelemetry = sinkTelemetry,
            remoteModeActive = false,
            isInPipMode = true,
            onToggleRemoteMode = {},
            onDismissChargingAlert = {},
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    val isExternalSink = sinkTelemetry.activeSinkType == SinkType.MIRRORED_DISPLAY ||
        sinkTelemetry.activeSinkType == SinkType.CAR_SURFACE
    val remoteModeActive = isExternalSink && cabinPrefs.phoneRemoteModePreferred

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideCockpit = maxWidth >= 680.dp

        if (isWideCockpit) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.35f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    VideoSurfaceViewport(
                        playerState = playerState,
                        cues = cues,
                        subtitleStyle = cabinPrefs.subtitleStyle,
                        cabinUtility = cabinUtility,
                        safetyState = safetyState,
                        sinkTelemetry = sinkTelemetry,
                        remoteModeActive = remoteModeActive,
                        isInPipMode = false,
                        onToggleRemoteMode = { onSetPhoneRemoteMode(!cabinPrefs.phoneRemoteModePreferred) },
                        onDismissChargingAlert = onDismissChargingAlert,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PlayerTransportPanel(
                        playerState = playerState,
                        eqState = eqState,
                        cabinUtility = cabinUtility,
                        isExternalSink = isExternalSink,
                        remoteModeActive = remoteModeActive,
                        onOpenTracks = { showTracksDialog = true },
                        onOpenLiveEq = { showLiveEqSheet = true },
                        onOpenQueue = { showQueueSheet = true },
                        onEnterPip = onEnterPip,
                        onToggleRemoteMode = { onSetPhoneRemoteMode(!cabinPrefs.phoneRemoteModePreferred) },
                        onOpenDeck = onOpenDeck
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                VideoSurfaceViewport(
                    playerState = playerState,
                    cues = cues,
                    subtitleStyle = cabinPrefs.subtitleStyle,
                    cabinUtility = cabinUtility,
                    safetyState = safetyState,
                    sinkTelemetry = sinkTelemetry,
                    remoteModeActive = remoteModeActive,
                    isInPipMode = false,
                    onToggleRemoteMode = { onSetPhoneRemoteMode(!cabinPrefs.phoneRemoteModePreferred) },
                    onDismissChargingAlert = onDismissChargingAlert,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )
                PlayerTransportPanel(
                    playerState = playerState,
                    eqState = eqState,
                    cabinUtility = cabinUtility,
                    isExternalSink = isExternalSink,
                    remoteModeActive = remoteModeActive,
                    onOpenTracks = { showTracksDialog = true },
                    onOpenLiveEq = { showLiveEqSheet = true },
                    onOpenQueue = { showQueueSheet = true },
                    onEnterPip = onEnterPip,
                    onToggleRemoteMode = { onSetPhoneRemoteMode(!cabinPrefs.phoneRemoteModePreferred) },
                    onOpenDeck = onOpenDeck
                )
            }
        }
    }

    if (showTracksDialog) {
        TrackSelectionAndSubtitleDialog(
            playerState = playerState,
            subtitleStyle = cabinPrefs.subtitleStyle,
            onUpdateSubtitleStyle = onUpdateSubtitleStyle,
            onDismiss = { showTracksDialog = false }
        )
    }

    if (showLiveEqSheet) {
        RealTimeEqualizerSheet(
            eqState = eqState,
            presetsState = presetsState,
            activeAudioRoute = activeAudioRoute,
            onSelectPreset = onSelectPreset,
            onSaveCustomPreset = onSaveCustomPreset,
            onResetFlat = onResetFlat,
            onDismiss = { showLiveEqSheet = false }
        )
    }

    if (showQueueSheet) {
        UpNextQueueSheet(
            playerState = playerState,
            cabinUtility = cabinUtility,
            autoPlayNext = cabinPrefs.autoPlayNext,
            onSetAutoPlayNext = onSetAutoPlayNext,
            onStartSleepTimer = onStartSleepTimer,
            onSetSleepEndOfVideo = onSetSleepEndOfVideo,
            onCancelSleepTimer = onCancelSleepTimer,
            onDismiss = { showQueueSheet = false }
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurfaceViewport(
    playerState: PlayerUiState,
    cues: List<Cue>,
    subtitleStyle: SubtitleStyleConfig,
    cabinUtility: CabinUtilityState,
    safetyState: SafetyGateState,
    sinkTelemetry: VideoSinkTelemetry,
    remoteModeActive: Boolean,
    isInPipMode: Boolean,
    onToggleRemoteMode: () -> Unit,
    onDismissChargingAlert: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }

    var gestureHudText by remember { mutableStateOf<String?>(null) }
    var gestureHudType by remember { mutableStateOf(0) } // 0=none, 1=seekFwd, 2=seekBack, 3=brightness, 4=volume
    var gestureHudProgress by remember { mutableStateOf<Float?>(null) }
    var gestureHudSerial by remember { mutableStateOf(0) }

    val latestPlayerState by rememberUpdatedState(playerState)
    val latestRemoteMode by rememberUpdatedState(remoteModeActive)

    LaunchedEffect(gestureHudSerial) {
        if (gestureHudText != null) {
            delay(1100L)
            gestureHudText = null
            gestureHudProgress = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            VideoSinkRouter.phoneSink.bindSurfaceView(null)
        }
    }

    val videoAspect = remember(playerState.videoWidth, playerState.videoHeight, playerState.aspectMode) {
        if (playerState.videoWidth > 0 && playerState.videoHeight > 0) {
            playerState.videoWidth.toFloat() / playerState.videoHeight.toFloat()
        } else {
            16f / 9f
        }
    }

    Box(
        modifier = modifier
            .testTag("video_gesture_surface")
            .clip(if (isInPipMode) RoundedCornerShape(0.dp) else MaterialTheme.shapes.large)
            .clipToBounds()
            .background(Color.Black)
            .then(
                if (!isInPipMode) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
                } else {
                    Modifier
                }
            )
            .then(
                if (!isInPipMode && !safetyState.isBlocked) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { offset ->
                                    if (offset.x < size.width * 0.42f) {
                                        PlayerController.seekBy(-10_000L)
                                        gestureHudType = 2
                                        gestureHudProgress = null
                                        gestureHudText = "-10s"
                                        gestureHudSerial++
                                    } else if (offset.x > size.width * 0.58f) {
                                        PlayerController.seekBy(10_000L)
                                        gestureHudType = 1
                                        gestureHudProgress = null
                                        gestureHudText = "+10s"
                                        gestureHudSerial++
                                    } else {
                                        PlayerController.togglePlayPause()
                                    }
                                },
                                onTap = {
                                    if (latestRemoteMode) {
                                        val wasPlaying = latestPlayerState.isPlaying
                                        PlayerController.togglePlayPause()
                                        gestureHudType = 0
                                        gestureHudProgress = null
                                        gestureHudText = if (wasPlaying) "Paused" else "Playing"
                                        gestureHudSerial++
                                    }
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val startX = down.position.x
                                var totalDx = 0f
                                var totalDy = 0f
                                var mode = 0 // 0=undecided, 1=horizontal seek, 2=vertical left brightness, 3=vertical right volume

                                val systemBrightness = try {
                                    Settings.System.getInt(
                                        context.contentResolver,
                                        Settings.System.SCREEN_BRIGHTNESS
                                    ) / 255f
                                } catch (_: Exception) {
                                    0.6f
                                }.coerceIn(0.05f, 1.0f)

                                var brightnessVal = activity?.window?.attributes?.screenBrightness?.let {
                                    if (it < 0f) systemBrightness else it.coerceIn(0.05f, 1.0f)
                                } ?: systemBrightness

                                val maxVol = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
                                var volumeFraction = ((audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) / maxVol.toFloat())
                                    .coerceIn(0f, 1f)

                                val anchorPosMs = latestPlayerState.positionMs
                                val totalDurMs = latestPlayerState.durationMs.coerceAtLeast(1L)
                                var previewSeekMs = anchorPosMs

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) {
                                        if (mode == 1) {
                                            PlayerController.seekTo(previewSeekMs)
                                        }
                                        break
                                    }
                                    val delta = change.positionChange()
                                    totalDx += delta.x
                                    totalDy += delta.y

                                    if (mode == 0 && (abs(totalDx) > 20f || abs(totalDy) > 20f)) {
                                        mode = when {
                                            abs(totalDx) > abs(totalDy) -> 1
                                            startX < size.width * 0.5f -> 2
                                            else -> 3
                                        }
                                    }

                                    when (mode) {
                                        1 -> {
                                            val deltaMs = ((totalDx / size.width.coerceAtLeast(1)) * 90_000f).toLong()
                                            previewSeekMs = (anchorPosMs + deltaMs).coerceIn(0L, totalDurMs)
                                            val sign = if (deltaMs >= 0) "+" else "-"
                                            gestureHudType = if (deltaMs >= 0) 1 else 2
                                            gestureHudProgress = (previewSeekMs.toFloat() / totalDurMs.toFloat()).coerceIn(0f, 1f)
                                            gestureHudText = "$sign${abs(deltaMs) / 1000}s • ${formatDuration(previewSeekMs)}"
                                            gestureHudSerial++
                                            change.consume()
                                        }
                                        2 -> {
                                            brightnessVal = (brightnessVal - (delta.y / size.height.coerceAtLeast(1))).coerceIn(0.05f, 1.0f)
                                            activity?.window?.let { win ->
                                                val lp = win.attributes
                                                lp.screenBrightness = brightnessVal
                                                win.attributes = lp
                                            }
                                            gestureHudType = 3
                                            gestureHudProgress = brightnessVal
                                            gestureHudText = "Brightness ${(brightnessVal * 100).roundToInt()}%"
                                            gestureHudSerial++
                                            change.consume()
                                        }
                                        3 -> {
                                            volumeFraction = (volumeFraction - (delta.y / size.height.coerceAtLeast(1))).coerceIn(0f, 1f)
                                            val targetVol = (volumeFraction * maxVol).roundToInt().coerceIn(0, maxVol)
                                            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                            gestureHudType = 4
                                            gestureHudProgress = volumeFraction
                                            gestureHudText = "Volume ${(volumeFraction * 100).roundToInt()}%"
                                            gestureHudSerial++
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        val surfaceModifier = when (playerState.aspectMode) {
            AspectMode.FIT -> Modifier
                .fillMaxSize()
                .aspectRatio(videoAspect, matchHeightConstraintsFirst = false)
            AspectMode.FILL -> Modifier.fillMaxSize()
            AspectMode.ZOOM -> Modifier
                .fillMaxSize()
                .aspectRatio(videoAspect * 1.15f)
        }

        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    VideoSinkRouter.phoneSink.bindSurfaceView(this)
                }
            },
            update = { view ->
                VideoSinkRouter.phoneSink.bindSurfaceView(view)
            },
            modifier = surfaceModifier
        )

        // Real-time styled Media3 SubtitleView overlay
        AndroidView(
            factory = { ctx ->
                SubtitleView(ctx).apply {
                    setApplyEmbeddedStyles(false)
                    setApplyEmbeddedFontSizes(false)
                }
            },
            update = { subView ->
                val bgAlpha = (subtitleStyle.bgOpacity * 255f).roundToInt().coerceIn(0, 255)
                val captionStyle = CaptionStyleCompat(
                    subtitleStyle.colorPreset.argbColor,
                    AndroidColor.argb(bgAlpha, 0, 0, 0),
                    AndroidColor.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                    AndroidColor.BLACK,
                    null
                )
                subView.setStyle(captionStyle)
                subView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleStyle.fontScale)
                subView.setCues(cues)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 8.dp)
        )

        // Low-glare Phone Remote Mode overlay when projecting to USB-C or Android Auto
        if (remoteModeActive && !safetyState.isBlocked && !isInPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xF005070B))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.TouchApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = "Phone Touchpad Remote Active",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Projecting fullscreen to ${sinkTelemetry.activeSinkType.label}\nTap to Play/Pause • Swipe horizontally to seek",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = onToggleRemoteMode) {
                        Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Show Phone Preview")
                    }
                }
            }
        } else if (sinkTelemetry.activeSinkType != SinkType.PHONE_SURFACE && !safetyState.isBlocked && !isInPipMode) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = "Projecting -> ${sinkTelemetry.activeSinkType.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // Top-right EV charging break / battery guard pill
        if (!isInPipMode && (cabinUtility.chargingRemainingSec > 0 || cabinUtility.chargingExpired || cabinUtility.batteryGuardTriggered)) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (cabinUtility.chargingRemainingSec > 0 || cabinUtility.chargingExpired) {
                    Surface(
                        color = if (cabinUtility.chargingExpired) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                        },
                        shape = RoundedCornerShape(12.dp),
                        onClick = { if (cabinUtility.chargingExpired) onDismissChargingAlert() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(15.dp))
                            Text(
                                text = if (cabinUtility.chargingExpired) {
                                    "Charge Break Done!"
                                } else {
                                    formatCountdownSeconds(cabinUtility.chargingRemainingSec)
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                if (cabinUtility.batteryGuardTriggered) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
                            Text(
                                text = "Battery ${cabinUtility.batteryPercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        // Gesture feedback HUD pill
        AnimatedVisibility(
            visible = gestureHudText != null && !isInPipMode,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    .testTag("gesture_hud_pill")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val hudIcon = when (gestureHudType) {
                            1 -> Icons.Filled.FastForward
                            2 -> Icons.Filled.FastRewind
                            3 -> Icons.Filled.Brightness6
                            4 -> Icons.Filled.VolumeUp
                            else -> null
                        }
                        if (hudIcon != null) {
                            Icon(
                                imageVector = hudIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = gestureHudText.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    gestureHudProgress?.let { progress ->
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .width(140.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }

        if (safetyState.isBlocked && !isInPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (safetyState.audioDriveModeEnabled) Icons.Filled.Headphones else Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = if (safetyState.audioDriveModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = if (safetyState.audioDriveModeEnabled) {
                            "AUDIO-ONLY DRIVE MODE"
                        } else {
                            safetyState.blockReason ?: stringResource(R.string.safety_blocked_moving)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (safetyState.audioDriveModeEnabled) {
                            "Video is blanked above 3 km/h while cabin audio continues safely."
                        } else {
                            stringResource(R.string.safety_notice_body)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    if (safetyState.needsFreshParkedConfirmation) {
                        Button(
                            onClick = { SafetyGateManager.confirmVehicleParked() },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("confirm_parked_button")
                        ) {
                            Text(stringResource(R.string.action_confirm_parked))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerTransportPanel(
    playerState: PlayerUiState,
    eqState: EqualizerState,
    cabinUtility: CabinUtilityState,
    isExternalSink: Boolean,
    remoteModeActive: Boolean,
    onOpenTracks: () -> Unit,
    onOpenLiveEq: () -> Unit,
    onOpenQueue: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleRemoteMode: () -> Unit,
    onOpenDeck: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playerState.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${playerState.videoDecoderName} • ${playerState.audioCodecLabel} • Drop ${playerState.droppedFrames}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (playerState.activeUriString == null) {
                    TextButton(onClick = onOpenDeck) {
                        Text("Pick Video")
                    }
                }
            }

            if (playerState.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = playerState.errorMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            val duration = playerState.durationMs.coerceAtLeast(1L)
            Column {
                Slider(
                    value = playerState.positionMs.coerceIn(0L, duration).toFloat(),
                    onValueChange = { PlayerController.seekTo(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDuration(playerState.positionMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (cabinUtility.sleepRemainingSec > 0) {
                            "Sleep ${formatCountdownSeconds(cabinUtility.sleepRemainingSec)} • ${formatDuration(playerState.durationMs)}"
                        } else {
                            formatDuration(playerState.durationMs)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = { PlayerController.seekPrevious() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("player_prev_button")
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.action_previous))
                }

                FilledIconButton(
                    onClick = { PlayerController.seekBy(-10_000L) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("player_rewind_button")
                ) {
                    Icon(Icons.Filled.Replay10, contentDescription = stringResource(R.string.action_seek_back))
                }

                FilledIconButton(
                    onClick = { PlayerController.togglePlayPause() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .size(64.dp)
                        .testTag("player_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playerState.isPlaying) {
                            stringResource(R.string.action_pause)
                        } else {
                            stringResource(R.string.action_play)
                        },
                        modifier = Modifier.size(34.dp)
                    )
                }

                FilledIconButton(
                    onClick = { PlayerController.seekBy(10_000L) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("player_forward_button")
                ) {
                    Icon(Icons.Filled.Forward10, contentDescription = stringResource(R.string.action_seek_forward))
                }

                FilledIconButton(
                    onClick = { PlayerController.seekNext() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .size(52.dp)
                        .testTag("player_next_button")
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.action_next))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { PlayerController.cycleAspectMode() },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("aspect_ratio_button")
                ) {
                    Icon(Icons.Filled.AspectRatio, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(playerState.aspectMode.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                FilledTonalButton(
                    onClick = onOpenTracks,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("tracks_button")
                ) {
                    Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Subs", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                FilledTonalButton(
                    onClick = onOpenLiveEq,
                    modifier = Modifier
                        .weight(1.1f)
                        .height(48.dp)
                        .testTag("live_eq_button")
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (eqState.isEnabled) "Live EQ" else "EQ Off",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpenQueue,
                    modifier = Modifier
                        .weight(1.2f)
                        .height(48.dp)
                        .testTag("queue_sleep_button")
                ) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Queue (${playerState.queue.size})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                FilledTonalButton(
                    onClick = onEnterPip,
                    modifier = Modifier
                        .weight(0.9f)
                        .height(48.dp)
                        .testTag("pip_button")
                ) {
                    Icon(Icons.Filled.PictureInPictureAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("PiP", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                if (isExternalSink) {
                    FilledTonalButton(
                        onClick = onToggleRemoteMode,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("remote_mode_button")
                    ) {
                        Icon(Icons.Filled.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (remoteModeActive) "Preview" else "Remote", maxLines = 1)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(playerState.playbackSpeed - speed) < 0.05f,
                        onClick = { PlayerController.setPlaybackSpeed(speed) },
                        label = { Text("${speed}x") }
                    )
                }
            }

            Column {
                Text(
                    text = "Car Audio Sync Offset: ${playerState.audioDelayMs} ms",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = playerState.audioDelayMs.toFloat(),
                    onValueChange = { PlayerController.setAudioDelayMs(it.toInt()) },
                    valueRange = -300f..300f
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

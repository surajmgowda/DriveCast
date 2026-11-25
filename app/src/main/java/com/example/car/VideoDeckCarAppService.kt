package com.example.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.R
import com.example.player.PlayerController
import com.example.safety.SafetyGateManager
import com.example.sink.VideoSinkRouter
import com.example.ui.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Android Auto CarAppService bridging DriveCast's hardware-accelerated ExoPlayer
 * pipeline onto the MG Windsor Pro EV infotainment surface with touchscreen ActionStrip
 * controls, in-car library browser, Audio-Only Drive Mode, and fail-closed safety gating.
 */
class VideoDeckCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = VideoDeckCarSession()
}

class VideoDeckCarSession : Session(), DefaultLifecycleObserver {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speedListener: OnCarDataAvailableListener<Speed>? = null
    private var carScreen: CarPlayerScreen? = null

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val surface = surfaceContainer.surface ?: return
            VideoSinkRouter.notifyCarSurfaceAvailable(
                surface = surface,
                width = surfaceContainer.width,
                height = surfaceContainer.height
            )
            SafetyGateManager.updateCarSessionActive(true)
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            VideoSinkRouter.carSink.onVisibleAreaChanged(visibleArea)
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            VideoSinkRouter.carSink.onVisibleAreaChanged(stableArea)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            surfaceContainer.surface?.let { VideoSinkRouter.notifyCarSurfaceDestroyed(it) }
            SafetyGateManager.updateCarSessionActive(false)
        }

        override fun onClick(x: Float, y: Float) {
            PlayerController.togglePlayPause()
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreateScreen(intent: Intent): Screen {
        PlayerController.ensureServiceConnected(carContext)
        SafetyGateManager.initialize(carContext) { blocked, reason ->
            VideoSinkRouter.onSafetyGateChanged(blocked, reason)
            carScreen?.invalidate()
        }

        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        subscribeCarSpeed()

        val screen = CarPlayerScreen(carContext)
        carScreen = screen
        return screen
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unsubscribeCarSpeed()
        SafetyGateManager.updateCarSessionActive(false)
        sessionScope.cancel()
        super.onDestroy(owner)
    }

    private fun subscribeCarSpeed() {
        try {
            val hw = carContext.getCarService(CarHardwareManager::class.java)
            val listener = OnCarDataAvailableListener<Speed> { speedData ->
                if (speedData.displaySpeedMetersPerSecond.status == CarValue.STATUS_SUCCESS) {
                    SafetyGateManager.updateCarHardwareSpeed(
                        speedData.displaySpeedMetersPerSecond.value
                    )
                } else if (speedData.rawSpeedMetersPerSecond.status == CarValue.STATUS_SUCCESS) {
                    SafetyGateManager.updateCarHardwareSpeed(
                        speedData.rawSpeedMetersPerSecond.value
                    )
                }
            }
            speedListener = listener
            hw.carInfo.addSpeedListener(carContext.mainExecutor, listener)
        } catch (e: Exception) {
            Log.w(TAG, "CarHardwareManager Speed unavailable on host: ${e.message}")
        }
    }

    private fun unsubscribeCarSpeed() {
        try {
            val listener = speedListener ?: return
            carContext.getCarService(CarHardwareManager::class.java)
                .carInfo
                .removeSpeedListener(listener)
        } catch (_: Exception) {
        }
        speedListener = null
    }

    companion object {
        private const val TAG = "VideoDeckCarSession"
    }
}

class CarPlayerScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        observerJob = screenScope.launch {
            PlayerController.uiState.collectLatest {
                invalidate()
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        observerJob?.cancel()
        observerJob = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        screenScope.cancel()
    }

    override fun onGetTemplate(): Template {
        val playerState = PlayerController.uiState.value
        val safetyState = SafetyGateManager.state.value

        val playTitle = when {
            safetyState.isBlocked && safetyState.audioDriveModeEnabled ->
                if (playerState.isPlaying) "Pause Audio" else "Play Audio"
            playerState.isPlaying -> "Pause"
            else -> "Play"
        }

        val playPauseAction = Action.Builder()
            .setTitle(playTitle)
            .setBackgroundColor(
                when {
                    safetyState.isBlocked && !safetyState.audioDriveModeEnabled -> CarColor.RED
                    safetyState.isBlocked && safetyState.audioDriveModeEnabled -> CarColor.BLUE
                    else -> CarColor.GREEN
                }
            )
            .setOnClickListener {
                if (safetyState.needsFreshParkedConfirmation) {
                    SafetyGateManager.confirmVehicleParked()
                }
                PlayerController.togglePlayPause()
                invalidate()
            }
            .build()

        val seekBackAction = Action.Builder()
            .setTitle("-10s")
            .setOnClickListener {
                PlayerController.seekBy(-10_000L)
            }
            .build()

        val seekForwardAction = Action.Builder()
            .setTitle("+10s")
            .setOnClickListener {
                PlayerController.seekBy(10_000L)
            }
            .build()

        val aspectAction = Action.Builder()
            .setTitle(playerState.aspectMode.name)
            .setOnClickListener {
                PlayerController.cycleAspectMode()
                invalidate()
            }
            .build()

        val actionStrip = ActionStrip.Builder()
            .addAction(seekBackAction)
            .addAction(playPauseAction)
            .addAction(seekForwardAction)
            .addAction(aspectAction)
            .build()

        val mapActionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.mipmap.ic_launcher)
                        ).build()
                    )
                    .setOnClickListener {
                        screenManager.push(CarInfoTemplateScreen(carContext))
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Library")
                    .setOnClickListener {
                        screenManager.push(CarLibraryBrowserScreen(carContext))
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Prev")
                    .setOnClickListener { PlayerController.seekPrevious() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Next")
                    .setOnClickListener { PlayerController.seekNext() }
                    .build()
            )
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .setMapActionStrip(mapActionStrip)
            .setBackgroundColor(CarColor.SECONDARY)
            .build()
    }
}

class CarLibraryBrowserScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val videos = PlayerController.librarySnapshot.value
        val activeId = PlayerController.uiState.value.activeVideoId
        val builder = ItemList.Builder()

        if (videos.isEmpty()) {
            builder.addItem(
                Row.Builder()
                    .setTitle("No videos in DriveCast Deck")
                    .addText("Add local MP4/MKV files from your phone to browse here.")
                    .build()
            )
        } else {
            videos.take(12).forEach { entry ->
                val isPlaying = entry.id == activeId
                val statusPrefix = if (isPlaying) "[Active] " else ""
                builder.addItem(
                    Row.Builder()
                        .setTitle("$statusPrefix${entry.displayName}")
                        .addText("${entry.resolutionLabel} • ${formatDuration(entry.durationMs)}")
                        .setOnClickListener {
                            PlayerController.playFromCarLibrary(entry)
                            screenManager.pop()
                        }
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle("DriveCast Library")
            .setHeaderAction(Action.BACK)
            .setSingleList(builder.build())
            .build()
    }
}

class CarInfoTemplateScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val playerState = PlayerController.uiState.value
        val safetyState = SafetyGateManager.state.value
        val cabinState = CabinUtilityManager.state.value

        val chargingLabel = if (cabinState.chargingRemainingSec > 0) {
            val mins = (cabinState.chargingRemainingSec + 59) / 60
            "EV Break Timer: ${mins}m remaining"
        } else {
            "EV Break Timer: Inactive"
        }

        val itemList = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Now Playing: ${playerState.title}")
                    .addText("${playerState.videoWidth}x${playerState.videoHeight} • ${playerState.videoDecoderName}")
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Parked Safety Gate: ${if (safetyState.isBlocked) "LOCKED" else "VERIFIED"}")
                    .addText(
                        safetyState.blockReason
                            ?: "Speed ${"%.1f".format(safetyState.speedKmh)} km/h • Thermal: ${safetyState.thermalHealth.label}"
                    )
                    .setOnClickListener {
                        SafetyGateManager.confirmVehicleParked()
                        screenManager.pop()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Audio-Only Drive Mode: ${if (safetyState.audioDriveModeEnabled) "ON" else "OFF"}")
                    .addText("Keep audio playing when speed > 3 km/h while keeping video blanked.")
                    .setOnClickListener {
                        SafetyGateManager.setAudioDriveMode(!safetyState.audioDriveModeEnabled)
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Audio & Cabin Timers")
                    .addText("${playerState.audioCodecLabel} • $chargingLabel")
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("DriveCast Car Console")
            .setHeaderAction(Action.BACK)
            .setSingleList(itemList)
            .build()
    }
}

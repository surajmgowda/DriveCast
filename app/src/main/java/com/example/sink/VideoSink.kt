package com.example.sink

import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.Player
import com.example.data.QualityProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SinkType(val label: String) {
    PHONE_SURFACE("Phone Hardware Surface"),
    CAR_SURFACE("Android Auto Car Surface"),
    MIRRORED_DISPLAY("Wired USB-C / External Display"),
    OFFICIAL_API("Official Parked API Sink")
}

data class VideoSinkTelemetry(
    val activeSinkType: SinkType = SinkType.PHONE_SURFACE,
    val surfaceWidth: Int = 0,
    val surfaceHeight: Int = 0,
    val isCarConnected: Boolean = false,
    val isUsbDisplayConnected: Boolean = false,
    val isBlanked: Boolean = false,
    val blankReason: String? = null,
    val qualityProfile: QualityProfile = QualityProfile.AUTO_ADAPTIVE,
    val hardwareAccelerated: Boolean = true,
    val estimatedLatencyMs: Int = 18
)

/**
 * Swappable VideoSink protocol abstraction separating Media3 ExoPlayer from the
 * underlying physical display surface (Phone SurfaceView, Android Auto Car Surface,
 * Wired USB-C Presentation Display, or future Official Parked API).
 */
interface VideoSink {
    val sinkId: String
    val sinkType: SinkType
    val width: Int
    val height: Int
    val isAvailable: Boolean
    fun attachPlayer(player: Player)
    fun detachPlayer(player: Player, clearSurfaceOnPlayer: Boolean = true)
    fun setBlanked(blanked: Boolean, reason: String? = null)
    fun release()
}

class PhoneSurfaceSink : VideoSink {
    override val sinkId: String = "phone_surface_sink"
    override val sinkType: SinkType = SinkType.PHONE_SURFACE
    override var width: Int = 0
        private set
    override var height: Int = 0
        private set

    private var surfaceView: SurfaceView? = null
    private var attachedPlayer: Player? = null
    private var lastBoundPlayerToView: Player? = null
    private var blanked: Boolean = false

    override val isAvailable: Boolean
        get() = surfaceView?.holder?.surface?.isValid == true

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (!blanked) {
                val player = attachedPlayer
                val view = surfaceView
                if (player != null && view != null) {
                    player.setVideoSurfaceView(view)
                    lastBoundPlayerToView = player
                }
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            this@PhoneSurfaceSink.width = width
            this@PhoneSurfaceSink.height = height
            if (!blanked) {
                val player = attachedPlayer
                val view = surfaceView
                if (player != null && view != null && lastBoundPlayerToView !== player) {
                    player.setVideoSurfaceView(view)
                    lastBoundPlayerToView = player
                }
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            attachedPlayer?.clearVideoSurfaceView(surfaceView)
            lastBoundPlayerToView = null
        }
    }

    fun bindSurfaceView(view: SurfaceView?) {
        if (surfaceView === view) {
            val player = attachedPlayer
            if (view != null && player != null && !blanked && lastBoundPlayerToView !== player) {
                player.setVideoSurfaceView(view)
                lastBoundPlayerToView = player
            }
            return
        }
        surfaceView?.holder?.removeCallback(holderCallback)
        attachedPlayer?.clearVideoSurfaceView(surfaceView)
        lastBoundPlayerToView = null
        surfaceView = view
        if (view != null) {
            width = view.width
            height = view.height
            view.holder.addCallback(holderCallback)
            if (!blanked) {
                val player = attachedPlayer
                if (player != null) {
                    player.setVideoSurfaceView(view)
                    lastBoundPlayerToView = player
                }
            }
        }
    }

    override fun attachPlayer(player: Player) {
        attachedPlayer = player
        val view = surfaceView
        if (!blanked && view != null) {
            player.setVideoSurfaceView(view)
            lastBoundPlayerToView = player
        }
    }

    override fun detachPlayer(player: Player, clearSurfaceOnPlayer: Boolean) {
        if (clearSurfaceOnPlayer) {
            surfaceView?.let { player.clearVideoSurfaceView(it) }
        }
        if (attachedPlayer === player) {
            attachedPlayer = null
        }
        if (lastBoundPlayerToView === player) {
            lastBoundPlayerToView = null
        }
    }

    override fun setBlanked(blanked: Boolean, reason: String?) {
        this.blanked = blanked
        val player = attachedPlayer ?: return
        val view = surfaceView ?: return
        if (blanked) {
            player.clearVideoSurfaceView(view)
            lastBoundPlayerToView = null
        } else if (view.holder.surface.isValid) {
            player.setVideoSurfaceView(view)
            lastBoundPlayerToView = player
        }
    }

    override fun release() {
        surfaceView?.holder?.removeCallback(holderCallback)
        surfaceView?.let { attachedPlayer?.clearVideoSurfaceView(it) }
        surfaceView = null
        attachedPlayer = null
        lastBoundPlayerToView = null
    }
}

class CarSurfaceSink : VideoSink {
    override val sinkId: String = "android_auto_car_surface_sink"
    override val sinkType: SinkType = SinkType.CAR_SURFACE
    override var width: Int = 0
        private set
    override var height: Int = 0
        private set

    private var carSurface: Surface? = null
    private var visibleRect: Rect? = null
    private var attachedPlayer: Player? = null
    private var blanked: Boolean = false
    private var blankReason: String? = null

    override val isAvailable: Boolean
        get() = carSurface?.isValid == true

    /**
     * Note: We intentionally avoid calling surface.lockCanvas() on the Android Auto Surface
     * because connecting the CPU Canvas producer to the Surface's BufferQueue can block
     * MediaCodecVideoRenderer (NATIVE_WINDOW_API_MEDIA) from attaching to the same Surface.
     * Safety gate banners and Audio-Only Drive Mode prompts are rendered via CarPlayerScreen's
     * NavigationTemplate ActionStrip & Alerts instead.
     */
    fun onCarSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        this.carSurface = surface
        this.width = width
        this.height = height
        if (!blanked && surface.isValid) {
            attachedPlayer?.setVideoSurface(surface)
        }
    }

    fun onVisibleAreaChanged(rect: Rect) {
        this.visibleRect = rect
    }

    fun onCarSurfaceDestroyed(surface: Surface) {
        attachedPlayer?.clearVideoSurface(surface)
        if (carSurface === surface) {
            carSurface = null
        }
    }

    override fun attachPlayer(player: Player) {
        attachedPlayer = player
        val surface = carSurface ?: return
        if (!blanked && surface.isValid) {
            player.setVideoSurface(surface)
        }
    }

    override fun detachPlayer(player: Player, clearSurfaceOnPlayer: Boolean) {
        if (clearSurfaceOnPlayer) {
            carSurface?.let { player.clearVideoSurface(it) }
        }
        if (attachedPlayer === player) {
            attachedPlayer = null
        }
    }

    override fun setBlanked(blanked: Boolean, reason: String?) {
        this.blanked = blanked
        this.blankReason = reason
        val surface = carSurface ?: return
        if (!surface.isValid) return
        if (blanked) {
            attachedPlayer?.clearVideoSurface(surface)
        } else {
            attachedPlayer?.setVideoSurface(surface)
        }
    }

    override fun release() {
        carSurface?.let { attachedPlayer?.clearVideoSurface(it) }
        carSurface = null
        attachedPlayer = null
    }
}

class MirroredDisplaySink : VideoSink {
    override val sinkId: String = "usb_c_mirrored_display_sink"
    override val sinkType: SinkType = SinkType.MIRRORED_DISPLAY
    override var width: Int = 0
        private set
    override var height: Int = 0
        private set

    private var externalSurface: Surface? = null
    private var attachedPlayer: Player? = null
    private var blanked: Boolean = false

    override val isAvailable: Boolean
        get() = externalSurface?.isValid == true

    fun bindExternalSurface(surface: Surface?, w: Int, h: Int) {
        if (externalSurface === surface) return
        externalSurface?.let { attachedPlayer?.clearVideoSurface(it) }
        externalSurface = surface
        width = w
        height = h
        if (surface != null && surface.isValid && !blanked) {
            attachedPlayer?.setVideoSurface(surface)
        }
    }

    override fun attachPlayer(player: Player) {
        attachedPlayer = player
        val surface = externalSurface
        if (surface != null && surface.isValid && !blanked) {
            player.setVideoSurface(surface)
        }
    }

    override fun detachPlayer(player: Player, clearSurfaceOnPlayer: Boolean) {
        if (clearSurfaceOnPlayer) {
            externalSurface?.let { player.clearVideoSurface(it) }
        }
        if (attachedPlayer === player) {
            attachedPlayer = null
        }
    }

    override fun setBlanked(blanked: Boolean, reason: String?) {
        this.blanked = blanked
        val surface = externalSurface ?: return
        if (blanked) {
            attachedPlayer?.clearVideoSurface(surface)
        } else if (surface.isValid) {
            attachedPlayer?.setVideoSurface(surface)
        }
    }

    override fun release() {
        externalSurface?.let { attachedPlayer?.clearVideoSurface(it) }
        externalSurface = null
        attachedPlayer = null
    }
}

/**
 * Coordinates swappable VideoSink routing between Phone, Android Auto Car Surface,
 * and Wired USB-C External Display without tearing down the Media3 ExoPlayer pipeline.
 */
object VideoSinkRouter {
    val phoneSink = PhoneSurfaceSink()
    val carSink = CarSurfaceSink()
    val mirroredSink = MirroredDisplaySink()

    private var activePlayer: Player? = null
    private var activeSink: VideoSink = phoneSink

    private val _telemetry = MutableStateFlow(VideoSinkTelemetry())
    val telemetry: StateFlow<VideoSinkTelemetry> = _telemetry.asStateFlow()

    fun registerPlayer(player: Player) {
        activePlayer = player
        routeToBestSink()
    }

    fun unregisterPlayer(player: Player) {
        if (activePlayer === player) {
            activeSink.detachPlayer(player, clearSurfaceOnPlayer = true)
            activePlayer = null
        }
    }

    fun updateQualityProfile(profile: QualityProfile) {
        _telemetry.update { it.copy(qualityProfile = profile) }
    }

    fun onSafetyGateChanged(blanked: Boolean, reason: String?) {
        carSink.setBlanked(blanked, reason)
        mirroredSink.setBlanked(blanked, reason)
        phoneSink.setBlanked(blanked, reason)
        _telemetry.update { it.copy(isBlanked = blanked, blankReason = reason) }
    }

    fun notifyCarSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        carSink.onCarSurfaceAvailable(surface, width, height)
        _telemetry.update {
            it.copy(
                isCarConnected = true,
                surfaceWidth = width,
                surfaceHeight = height,
                estimatedLatencyMs = 24
            )
        }
        routeToBestSink()
    }

    fun notifyCarSurfaceDestroyed(surface: Surface) {
        carSink.onCarSurfaceDestroyed(surface)
        _telemetry.update { it.copy(isCarConnected = false, estimatedLatencyMs = 16) }
        routeToBestSink()
    }

    fun notifyUsbDisplayAvailable(surface: Surface?, width: Int, height: Int) {
        mirroredSink.bindExternalSurface(surface, width, height)
        _telemetry.update {
            it.copy(
                isUsbDisplayConnected = surface != null,
                surfaceWidth = if (surface != null) width else it.surfaceWidth,
                surfaceHeight = if (surface != null) height else it.surfaceHeight,
                estimatedLatencyMs = if (surface != null) 8 else 16
            )
        }
        routeToBestSink()
    }

    fun ensureActiveSurfaceAttached() {
        val player = activePlayer ?: return
        activeSink.attachPlayer(player)
    }

    fun routeToBestSink(forcedType: SinkType? = null) {
        val targetSink: VideoSink = when (forcedType) {
            SinkType.CAR_SURFACE -> if (carSink.isAvailable) carSink else phoneSink
            SinkType.MIRRORED_DISPLAY -> if (mirroredSink.isAvailable) mirroredSink else phoneSink
            SinkType.PHONE_SURFACE -> phoneSink
            else -> when {
                carSink.isAvailable -> carSink
                mirroredSink.isAvailable -> mirroredSink
                else -> phoneSink
            }
        }

        val player = activePlayer
        val switchedSink = activeSink !== targetSink
        if (switchedSink && player != null) {
            // If target sink has a valid surface, swap directly via attachPlayer() without clearing to null first
            activeSink.detachPlayer(player, clearSurfaceOnPlayer = !targetSink.isAvailable)
        }
        activeSink = targetSink
        if (player != null) {
            activeSink.attachPlayer(player)
        }
        _telemetry.update {
            it.copy(
                activeSinkType = activeSink.sinkType,
                surfaceWidth = activeSink.width.takeIf { w -> w > 0 } ?: it.surfaceWidth,
                surfaceHeight = activeSink.height.takeIf { h -> h > 0 } ?: it.surfaceHeight,
                estimatedLatencyMs = when (activeSink.sinkType) {
                    SinkType.MIRRORED_DISPLAY -> 8
                    SinkType.PHONE_SURFACE -> 14
                    SinkType.CAR_SURFACE -> 24
                    SinkType.OFFICIAL_API -> 18
                }
            )
        }
    }
}

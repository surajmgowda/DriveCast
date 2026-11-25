package com.example.sink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    fun detachPlayer(player: Player)
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
    private var blanked: Boolean = false

    override val isAvailable: Boolean
        get() = surfaceView?.holder?.surface?.isValid == true

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (!blanked) {
                attachedPlayer?.setVideoSurfaceView(surfaceView)
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            this@PhoneSurfaceSink.width = width
            this@PhoneSurfaceSink.height = height
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            attachedPlayer?.clearVideoSurfaceView(surfaceView)
        }
    }

    fun bindSurfaceView(view: SurfaceView?) {
        if (surfaceView === view) return
        surfaceView?.holder?.removeCallback(holderCallback)
        attachedPlayer?.clearVideoSurfaceView(surfaceView)
        surfaceView = view
        if (view != null) {
            width = view.width
            height = view.height
            view.holder.addCallback(holderCallback)
            if (!blanked && view.holder.surface.isValid) {
                attachedPlayer?.setVideoSurfaceView(view)
            }
        }
    }

    override fun attachPlayer(player: Player) {
        attachedPlayer = player
        if (!blanked) {
            surfaceView?.let { player.setVideoSurfaceView(it) }
        }
    }

    override fun detachPlayer(player: Player) {
        surfaceView?.let { player.clearVideoSurfaceView(it) }
        if (attachedPlayer === player) {
            attachedPlayer = null
        }
    }

    override fun setBlanked(blanked: Boolean, reason: String?) {
        this.blanked = blanked
        val player = attachedPlayer ?: return
        val view = surfaceView ?: return
        if (blanked) {
            player.clearVideoSurfaceView(view)
        } else if (view.holder.surface.isValid) {
            player.setVideoSurfaceView(view)
        }
    }

    override fun release() {
        surfaceView?.holder?.removeCallback(holderCallback)
        surfaceView?.let { attachedPlayer?.clearVideoSurfaceView(it) }
        surfaceView = null
        attachedPlayer = null
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

    fun onCarSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        this.carSurface = surface
        this.width = width
        this.height = height
        if (blanked) {
            drawSafetyOverlay(surface, width, height, blankReason ?: "VIDEO PAUSED: VEHICLE MOVING")
        } else {
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

    override fun detachPlayer(player: Player) {
        carSurface?.let { player.clearVideoSurface(it) }
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
            drawSafetyOverlay(surface, width, height, reason ?: "PARKED-ONLY SAFETY LOCK ACTIVE")
        } else {
            attachedPlayer?.setVideoSurface(surface)
        }
    }

    override fun release() {
        carSurface?.let { attachedPlayer?.clearVideoSurface(it) }
        carSurface = null
        attachedPlayer = null
    }

    private fun drawSafetyOverlay(surface: Surface, w: Int, h: Int, message: String) {
        if (w <= 0 || h <= 0 || !surface.isValid) return
        try {
            val canvas: Canvas = surface.lockCanvas(null)
            try {
                canvas.drawColor(Color.rgb(9, 13, 20))
                val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(245, 158, 11)
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                }
                val pad = 48f
                canvas.drawRoundRect(pad, pad, w - pad, h - pad, 24f, 24f, accentPaint)

                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = (h * 0.065f).coerceIn(28f, 56f)
                    textAlign = Paint.Align.CENTER
                    isFakeBoldText = true
                }
                val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(148, 163, 184)
                    textSize = (h * 0.04f).coerceIn(18f, 34f)
                    textAlign = Paint.Align.CENTER
                }
                val cx = w / 2f
                val cy = h / 2f
                val audioMode = com.example.safety.SafetyGateManager.state.value.audioDriveModeEnabled
                val headline = if (audioMode) "AUDIO-ONLY DRIVE MODE" else message
                val subline = if (audioMode) {
                    "Video blanked while moving — audio continues safely."
                } else {
                    "DriveCast resumes automatically once vehicle is stationary."
                }
                canvas.drawText(headline, cx, cy - 16f, titlePaint)
                canvas.drawText(
                    subline,
                    cx,
                    cy + 36f,
                    subPaint
                )
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (_: Exception) {
            // Surface may be transitioning or already owned by MediaCodec
        }
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

    override fun detachPlayer(player: Player) {
        externalSurface?.let { player.clearVideoSurface(it) }
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
            activeSink.detachPlayer(player)
            activePlayer = null
        }
    }

    fun updateQualityProfile(profile: QualityProfile) {
        _telemetry.update { it.copy(qualityProfile = profile) }
    }

    fun onSafetyGateChanged(blanked: Boolean, reason: String?) {
        carSink.setBlanked(blanked, reason)
        mirroredSink.setBlanked(blanked, reason)
        if (_telemetry.value.isCarConnected || _telemetry.value.isUsbDisplayConnected) {
            phoneSink.setBlanked(blanked, reason)
        } else {
            phoneSink.setBlanked(blanked, reason)
        }
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
        if (activeSink !== targetSink && player != null) {
            activeSink.detachPlayer(player)
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

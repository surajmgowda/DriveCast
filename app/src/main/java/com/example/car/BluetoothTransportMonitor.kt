package com.example.car

import android.Manifest
import android.annotation.SuppressLint
import android.app.Presentation
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.example.sink.VideoSinkRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TransportLinkState(
    val bluetoothPermissionGranted: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val connectedCarAudioDevice: String? = null,
    val bondedDevices: List<String> = emptyList(),
    val isEncryptedBluetoothLink: Boolean = false,
    val activeAudioRoute: String = "Phone Speaker",
    val usbPresentationDisplayName: String? = null,
    val usbPresentationResolution: String? = null
)

/**
 * Monitors real Bluetooth head-unit A2DP/HFP encryption & bonding state,
 * active AudioManager output routing, and wired USB-C DisplayPort Presentation displays.
 */
class BluetoothTransportMonitor(private val context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    private var activePresentation: ExternalDisplayPresentation? = null

    private val _state = MutableStateFlow(TransportLinkState())
    val state: StateFlow<TransportLinkState> = _state.asStateFlow()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshExternalDisplays()
        override fun onDisplayRemoved(displayId: Int) = refreshExternalDisplays()
        override fun onDisplayChanged(displayId: Int) = refreshExternalDisplays()
    }

    fun startMonitoring() {
        displayManager?.registerDisplayListener(displayListener, null)
        refreshAll()
    }

    fun stopMonitoring() {
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        activePresentation?.dismiss()
        activePresentation = null
    }

    @SuppressLint("MissingPermission")
    fun refreshAll() {
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val adapter: BluetoothAdapter? = bluetoothManager?.adapter
        val btEnabled = adapter?.isEnabled == true
        var bondedNames: List<String> = emptyList()
        var encryptedLink = false

        if (btGranted && adapter != null && btEnabled) {
            try {
                val bonded: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()
                bondedNames = bonded.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                encryptedLink = bonded.any { it.bondState == BluetoothDevice.BOND_BONDED }
            } catch (e: SecurityException) {
                Log.w(TAG, "Bluetooth bonded query denied: ${e.message}")
            }
        }

        val routeInfo = inspectAudioOutputRoute()
        _state.update {
            it.copy(
                bluetoothPermissionGranted = btGranted,
                bluetoothEnabled = btEnabled,
                bondedDevices = bondedNames,
                isEncryptedBluetoothLink = encryptedLink && routeInfo.isBluetooth,
                connectedCarAudioDevice = routeInfo.btDeviceName ?: bondedNames.firstOrNull { name ->
                    name.contains("MG", ignoreCase = true) ||
                        name.contains("Windsor", ignoreCase = true) ||
                        name.contains("Car", ignoreCase = true) ||
                        name.contains("Auto", ignoreCase = true)
                },
                activeAudioRoute = routeInfo.routeLabel
            )
        }

        if (btGranted && btEnabled && adapter != null) {
            try {
                adapter.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                        val devices = try {
                            proxy?.connectedDevices.orEmpty()
                        } catch (_: SecurityException) {
                            emptyList()
                        }
                        val firstConnected = devices.firstOrNull()?.let { dev ->
                            try { dev.name } catch (_: SecurityException) { null }
                        }
                        if (firstConnected != null) {
                            _state.update {
                                it.copy(
                                    connectedCarAudioDevice = firstConnected,
                                    isEncryptedBluetoothLink = true
                                )
                            }
                        }
                        adapter.closeProfileProxy(profile, proxy)
                    }

                    override fun onServiceDisconnected(profile: Int) {}
                }, BluetoothProfile.A2DP)
            } catch (_: Exception) {
            }
        }

        refreshExternalDisplays()
    }

    private fun refreshExternalDisplays() {
        val presentationDisplays = displayManager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?: emptyArray()
        val external = presentationDisplays.firstOrNull()
        if (external != null) {
            val mode = external.mode
            val resLabel = "${mode.physicalWidth}x${mode.physicalHeight} @ ${mode.refreshRate.toInt()}Hz"
            _state.update {
                it.copy(
                    usbPresentationDisplayName = external.name,
                    usbPresentationResolution = resLabel
                )
            }
            if (activePresentation?.display?.displayId != external.displayId) {
                activePresentation?.dismiss()
                activePresentation = ExternalDisplayPresentation(appContext, external).also {
                    try {
                        it.show()
                    } catch (e: Exception) {
                        Log.w(TAG, "Unable to attach USB-C Presentation: ${e.message}")
                    }
                }
            }
        } else {
            activePresentation?.dismiss()
            activePresentation = null
            VideoSinkRouter.notifyUsbDisplayAvailable(null, 0, 0)
            _state.update {
                it.copy(
                    usbPresentationDisplayName = null,
                    usbPresentationResolution = null
                )
            }
        }
    }

    private data class AudioRouteSnapshot(val routeLabel: String, val isBluetooth: Boolean, val btDeviceName: String?)

    private fun inspectAudioOutputRoute(): AudioRouteSnapshot {
        val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
        val btA2dp = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        if (btA2dp != null) {
            val name = btA2dp.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth Audio"
            return AudioRouteSnapshot("BT A2DP • $name", true, name)
        }
        val usb = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (usb != null) {
            return AudioRouteSnapshot("USB-C Digital Audio", false, null)
        }
        val hdmi = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_HDMI }
        if (hdmi != null) {
            return AudioRouteSnapshot("HDMI / Car Display Audio", false, null)
        }
        return AudioRouteSnapshot("Phone Stereo Speaker", false, null)
    }

    private class ExternalDisplayPresentation(
        outerContext: Context,
        display: Display
    ) : Presentation(outerContext, display) {

        private var safetyOverlayView: android.widget.TextView? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val root = android.widget.FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
            }
            val surfaceView = SurfaceView(context).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.view.Gravity.CENTER
                )
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val mode = display.mode
                        VideoSinkRouter.notifyUsbDisplayAvailable(
                            holder.surface,
                            mode.physicalWidth,
                            mode.physicalHeight
                        )
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                        VideoSinkRouter.notifyUsbDisplayAvailable(holder.surface, w, h)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        VideoSinkRouter.notifyUsbDisplayAvailable(null, 0, 0)
                    }
                })
            }
            val overlay = android.widget.TextView(context).apply {
                setBackgroundColor(android.graphics.Color.rgb(9, 13, 20))
                setTextColor(android.graphics.Color.WHITE)
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                text = "PARKED-ONLY SAFETY LOCK ACTIVE\nDriveCast resumes when stationary."
                visibility = if (com.example.safety.SafetyGateManager.state.value.isBlocked) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            }
            safetyOverlayView = overlay
            root.addView(surfaceView)
            root.addView(
                overlay,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            setContentView(root)
        }

        fun updateSafetyBlanked(blocked: Boolean, reason: String?) {
            safetyOverlayView?.apply {
                text = "${reason ?: "PARKED-ONLY SAFETY LOCK ACTIVE"}\nDriveCast resumes video when stationary."
                visibility = if (blocked) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    fun syncPresentationSafety(blocked: Boolean, reason: String?) {
        activePresentation?.updateSafetyBlanked(blocked, reason)
    }

    companion object {
        private const val TAG = "BluetoothTransport"
    }
}

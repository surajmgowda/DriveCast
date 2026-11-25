package com.example.safety

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ThermalHealth(val label: String) {
    NOMINAL("Nominal"),
    MODERATE("Warm"),
    SEVERE("Throttling Warning"),
    CRITICAL("Critical Heat")
}

data class SafetyGateState(
    val isParkedVerified: Boolean = true,
    val isBlocked: Boolean = false,
    val speedKmh: Float = 0f,
    val carSpeedKmh: Float? = null,
    val gpsSpeedKmh: Float? = null,
    val isCarSessionActive: Boolean = false,
    val needsFreshParkedConfirmation: Boolean = false,
    val blockReason: String? = null,
    val thermalHealth: ThermalHealth = ThermalHealth.NOMINAL,
    val locationPermissionGranted: Boolean = false,
    val audioDriveModeEnabled: Boolean = false
)

/**
 * Parked-only safety gate (cannot be disabled).
 * Combines GPS speed + CarHardwareManager Speed + Thermal status.
 * Fails closed: if speed > 3.0 km/h or unknown during an active car session without
 * fresh parked confirmation, car video surfaces are always blanked, and playback is paused
 * unless Audio-Only Drive Mode is enabled.
 */
object SafetyGateManager {
    private const val TAG = "SafetyGateManager"
    private const val SPEED_LIMIT_KMH = 3.0f

    private val _state = MutableStateFlow(SafetyGateState())
    val state: StateFlow<SafetyGateState> = _state.asStateFlow()

    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var powerManager: PowerManager? = null
    private var appContext: Context? = null
    private var onBlockTriggered: ((Boolean, String?) -> Unit)? = null

    fun setAudioDriveMode(enabled: Boolean) {
        _state.update { it.copy(audioDriveModeEnabled = enabled) }
    }

    fun shouldPausePlayback(): Boolean {
        val current = _state.value
        return current.isBlocked && !current.audioDriveModeEnabled
    }

    fun initialize(context: Context, onBlockChanged: (Boolean, String?) -> Unit) {
        appContext = context.applicationContext
        onBlockTriggered = onBlockChanged
        powerManager = appContext?.getSystemService(PowerManager::class.java)
        refreshThermalStatus()
        startLocationUpdatesIfPermitted()
    }

    fun startLocationUpdatesIfPermitted() {
        val ctx = appContext ?: return
        val hasFine = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val granted = hasFine || hasCoarse
        _state.update { it.copy(locationPermissionGranted = granted) }

        if (!granted) {
            evaluateGate()
            return
        }

        if (fusedClient == null) {
            fusedClient = LocationServices.getFusedLocationProviderClient(ctx)
        }
        if (locationCallback != null) return

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc: Location = result.lastLocation ?: return
                val kmh = if (loc.hasSpeed()) (loc.speed * 3.6f).coerceAtLeast(0f) else 0f
                _state.update {
                    it.copy(
                        gpsSpeedKmh = kmh,
                        needsFreshParkedConfirmation = false
                    )
                }
                refreshThermalStatus()
                evaluateGate()
            }
        }
        locationCallback = callback

        try {
            @SuppressLint("MissingPermission")
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()
            @SuppressLint("MissingPermission")
            val client = fusedClient
            client?.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start speed gate location updates: ${e.message}")
        }
    }

    fun updateCarSessionActive(active: Boolean) {
        _state.update {
            val requireConfirm = active && it.carSpeedKmh == null && it.gpsSpeedKmh == null
            it.copy(
                isCarSessionActive = active,
                needsFreshParkedConfirmation = requireConfirm
            )
        }
        evaluateGate()
    }

    fun updateCarHardwareSpeed(metersPerSecond: Float?) {
        val kmh = metersPerSecond?.let { (it * 3.6f).coerceAtLeast(0f) }
        _state.update {
            it.copy(
                carSpeedKmh = kmh,
                needsFreshParkedConfirmation = if (kmh != null) false else it.needsFreshParkedConfirmation
            )
        }
        evaluateGate()
    }

    fun confirmVehicleParked() {
        _state.update {
            it.copy(
                needsFreshParkedConfirmation = false,
                isParkedVerified = true,
                isBlocked = false,
                blockReason = null
            )
        }
        onBlockTriggered?.invoke(false, null)
    }

    fun refreshThermalStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val status = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            val health = when {
                status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalHealth.CRITICAL
                status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalHealth.SEVERE
                status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalHealth.MODERATE
                else -> ThermalHealth.NOMINAL
            }
            _state.update { it.copy(thermalHealth = health) }
        }
    }

    private fun evaluateGate() {
        val current = _state.value
        val effectiveSpeed = current.carSpeedKmh ?: current.gpsSpeedKmh ?: 0f

        val moving = effectiveSpeed > SPEED_LIMIT_KMH
        val unknownInCar = current.isCarSessionActive &&
            current.carSpeedKmh == null &&
            current.gpsSpeedKmh == null &&
            current.needsFreshParkedConfirmation

        val blocked = moving || unknownInCar
        val reason = when {
            moving -> "VEHICLE MOVING (${"%.1f".format(effectiveSpeed)} km/h)"
            unknownInCar -> "SPEED TELEMETRY UNKNOWN — CONFIRM PARKED"
            else -> null
        }

        _state.update {
            it.copy(
                speedKmh = effectiveSpeed,
                isParkedVerified = !blocked,
                isBlocked = blocked,
                blockReason = reason
            )
        }
        onBlockTriggered?.invoke(blocked, reason)
    }

    fun stop() {
        locationCallback?.let { fusedClient?.removeLocationUpdates(it) }
        locationCallback = null
    }
}

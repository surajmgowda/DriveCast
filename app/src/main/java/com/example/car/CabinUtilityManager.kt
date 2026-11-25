package com.example.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
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

data class CabinUtilityState(
    val chargingRemainingSec: Int = 0,
    val chargingTotalSec: Int = 0,
    val chargingExpired: Boolean = false,
    val sleepRemainingSec: Int = 0,
    val sleepAtEndOfVideo: Boolean = false,
    val batteryPercent: Int = 100,
    val isPhoneCharging: Boolean = false,
    val batteryGuardThreshold: Int = 15,
    val batteryGuardTriggered: Boolean = false
)

/**
 * Manages EV Charging Break Timer, Playback Sleep Timer, and Phone Battery Guard.
 */
object CabinUtilityManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var batteryReceiverRegistered = false
    private var onPausePlayback: (() -> Unit)? = null
    private var onNotifyUser: ((String) -> Unit)? = null

    private val _state = MutableStateFlow(CabinUtilityState())
    val state: StateFlow<CabinUtilityState> = _state.asStateFlow()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            updateFromBatteryIntent(intent)
        }
    }

    fun initialize(
        context: Context,
        onPauseRequested: () -> Unit,
        onNotify: (String) -> Unit
    ) {
        onPausePlayback = onPauseRequested
        onNotifyUser = onNotify
        val appContext = context.applicationContext
        if (!batteryReceiverRegistered) {
            batteryReceiverRegistered = true
            val sticky = appContext.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            sticky?.let { updateFromBatteryIntent(it) }
        }
    }

    fun startChargingTimer(minutes: Int) {
        val totalSec = (minutes.coerceIn(1, 240)) * 60
        _state.update {
            it.copy(
                chargingTotalSec = totalSec,
                chargingRemainingSec = totalSec,
                chargingExpired = false
            )
        }
        ensureTickerRunning()
        onNotifyUser?.invoke("EV charging break timer set for $minutes min")
    }

    fun cancelChargingTimer() {
        _state.update {
            it.copy(chargingRemainingSec = 0, chargingTotalSec = 0, chargingExpired = false)
        }
    }

    fun dismissChargingAlert() {
        _state.update { it.copy(chargingExpired = false) }
    }

    fun startSleepTimer(minutes: Int) {
        val totalSec = (minutes.coerceIn(1, 240)) * 60
        _state.update {
            it.copy(
                sleepRemainingSec = totalSec,
                sleepAtEndOfVideo = false
            )
        }
        ensureTickerRunning()
        onNotifyUser?.invoke("Sleep timer set for $minutes min")
    }

    fun setSleepAtEndOfVideo(enabled: Boolean) {
        _state.update {
            it.copy(
                sleepAtEndOfVideo = enabled,
                sleepRemainingSec = if (enabled) 0 else it.sleepRemainingSec
            )
        }
        if (enabled) {
            onNotifyUser?.invoke("Sleep timer armed for end of current video")
        }
    }

    fun cancelSleepTimer() {
        _state.update { it.copy(sleepRemainingSec = 0, sleepAtEndOfVideo = false) }
    }

    fun onVideoPlaybackEnded(): Boolean {
        if (_state.value.sleepAtEndOfVideo) {
            _state.update { it.copy(sleepAtEndOfVideo = false) }
            onPausePlayback?.invoke()
            onNotifyUser?.invoke("Sleep timer paused playback at end of video")
            return true
        }
        return false
    }

    fun setBatteryGuardThreshold(percent: Int) {
        val clamped = percent.coerceIn(0, 40)
        _state.update { it.copy(batteryGuardThreshold = clamped) }
        evaluateBatteryGuard()
    }

    fun updateBatterySnapshotForTest(percent: Int, isCharging: Boolean) {
        _state.update {
            it.copy(
                batteryPercent = percent.coerceIn(0, 100),
                isPhoneCharging = isCharging
            )
        }
        evaluateBatteryGuard()
    }

    private fun updateFromBatteryIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else _state.value.batteryPercent

        _state.update {
            it.copy(
                batteryPercent = pct,
                isPhoneCharging = charging
            )
        }
        evaluateBatteryGuard()
    }

    private fun evaluateBatteryGuard() {
        val cur = _state.value
        val shouldTrigger = cur.batteryGuardThreshold > 0 &&
            !cur.isPhoneCharging &&
            cur.batteryPercent in 1..cur.batteryGuardThreshold
        if (shouldTrigger && !cur.batteryGuardTriggered) {
            _state.update { it.copy(batteryGuardTriggered = true) }
            onPausePlayback?.invoke()
            onNotifyUser?.invoke("Battery Guard paused playback at ${cur.batteryPercent}%")
        } else if (!shouldTrigger && cur.batteryGuardTriggered) {
            _state.update { it.copy(batteryGuardTriggered = false) }
        }
    }

    private fun ensureTickerRunning() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                val cur = _state.value
                var nextCharge = cur.chargingRemainingSec
                var chargeExpiredNow = false
                if (nextCharge > 0) {
                    nextCharge -= 1
                    if (nextCharge == 0) {
                        chargeExpiredNow = true
                    }
                }

                var nextSleep = cur.sleepRemainingSec
                var sleepExpiredNow = false
                if (nextSleep > 0) {
                    nextSleep -= 1
                    if (nextSleep == 0) {
                        sleepExpiredNow = true
                    }
                }

                _state.update {
                    it.copy(
                        chargingRemainingSec = nextCharge,
                        chargingExpired = it.chargingExpired || chargeExpiredNow,
                        sleepRemainingSec = nextSleep
                    )
                }

                if (chargeExpiredNow) {
                    playChime()
                    onNotifyUser?.invoke("EV charging break timer finished — ready to drive")
                }
                if (sleepExpiredNow) {
                    onPausePlayback?.invoke()
                    onNotifyUser?.invoke("Sleep timer finished — playback paused")
                }

                if (nextCharge == 0 && nextSleep == 0) {
                    break
                }
            }
        }
    }

    private fun playChime() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
        } catch (_: Exception) {
        }
    }
}

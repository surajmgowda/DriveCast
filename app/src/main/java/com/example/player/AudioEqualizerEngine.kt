package com.example.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class EqBandUi(
    val index: Int,
    val centerFreqHz: Int,
    val levelMb: Int,
    val minMb: Int,
    val maxMb: Int
)

data class EqualizerState(
    val isAttached: Boolean = false,
    val sessionId: Int = 0,
    val isEnabled: Boolean = true,
    val activePresetName: String = "Windsor Cabin",
    val bands: List<EqBandUi> = defaultPlaceholderBands(),
    val minLevelMb: Int = -1500,
    val maxLevelMb: Int = 1500,
    val bassBoostStrength: Int = 420,
    val loudnessGainMb: Int = 250,
    val virtualizerStrength: Int = 200,
    val activeAudioCodec: String = "Standby"
)

private fun defaultPlaceholderBands(): List<EqBandUi> = listOf(
    EqBandUi(0, 60, 300, -1500, 1500),
    EqBandUi(1, 230, 150, -1500, 1500),
    EqBandUi(2, 910, -50, -1500, 1500),
    EqBandUi(3, 3600, 200, -1500, 1500),
    EqBandUi(4, 14000, 350, -1500, 1500)
)

/**
 * Hardware-bound Android AudioEffect Equalizer + BassBoost + LoudnessEnhancer + Virtualizer
 * attached directly to ExoPlayer's audioSessionId for minimal latency DSP tuning.
 */
object AudioEqualizerEngine {
    private const val TAG = "AudioEqualizerEngine"

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var virtualizer: Virtualizer? = null
    private var currentSessionId: Int = 0

    private val _state = MutableStateFlow(EqualizerState())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    fun attachToAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (audioSessionId == currentSessionId && equalizer != null) return

        releaseEffects()
        currentSessionId = audioSessionId

        try {
            val eq = Equalizer(0, audioSessionId).apply {
                enabled = _state.value.isEnabled
            }
            val minMb = eq.bandLevelRange[0].toInt()
            val maxMb = eq.bandLevelRange[1].toInt()
            val numBands = eq.numberOfBands.toInt()
            val currentBands = _state.value.bands

            val bands = (0 until numBands).map { idx ->
                val targetLevel = currentBands.getOrNull(idx)?.levelMb?.coerceIn(minMb, maxMb)
                    ?: eq.getBandLevel(idx.toShort()).toInt()
                try {
                    eq.setBandLevel(idx.toShort(), targetLevel.toShort())
                } catch (_: Exception) {
                }
                EqBandUi(
                    index = idx,
                    centerFreqHz = eq.getCenterFreq(idx.toShort()) / 1000,
                    levelMb = targetLevel,
                    minMb = minMb,
                    maxMb = maxMb
                )
            }
            equalizer = eq

            val bass = try {
                BassBoost(0, audioSessionId).apply {
                    enabled = _state.value.isEnabled
                    if (strengthSupported) {
                        setStrength(_state.value.bassBoostStrength.coerceIn(0, 1000).toShort())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "BassBoost unavailable: ${e.message}")
                null
            }
            bassBoost = bass

            val loudness = try {
                LoudnessEnhancer(audioSessionId).apply {
                    enabled = _state.value.isEnabled
                    setTargetGain(_state.value.loudnessGainMb.coerceIn(0, 1500))
                }
            } catch (e: Exception) {
                Log.w(TAG, "LoudnessEnhancer unavailable: ${e.message}")
                null
            }
            loudnessEnhancer = loudness

            val virt = try {
                Virtualizer(0, audioSessionId).apply {
                    enabled = _state.value.isEnabled
                    if (strengthSupported) {
                        setStrength(_state.value.virtualizerStrength.coerceIn(0, 1000).toShort())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Virtualizer unavailable: ${e.message}")
                null
            }
            virtualizer = virt

            _state.update {
                it.copy(
                    isAttached = true,
                    sessionId = audioSessionId,
                    bands = bands,
                    minLevelMb = minMb,
                    maxLevelMb = maxMb
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioEffect attachment failed for session $audioSessionId: ${e.message}")
            _state.update { it.copy(isAttached = false, sessionId = 0) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        try {
            equalizer?.enabled = enabled
            bassBoost?.enabled = enabled
            loudnessEnhancer?.enabled = enabled
            virtualizer?.enabled = enabled
        } catch (_: Exception) {
        }
        _state.update { it.copy(isEnabled = enabled) }
    }

    fun setBandLevel(bandIndex: Int, levelMb: Int) {
        val minMb = _state.value.minLevelMb
        val maxMb = _state.value.maxLevelMb
        val clamped = levelMb.coerceIn(minMb, maxMb)
        try {
            equalizer?.setBandLevel(bandIndex.toShort(), clamped.toShort())
        } catch (_: Exception) {
        }
        _state.update { current ->
            val updatedBands = current.bands.map { band ->
                if (band.index == bandIndex) band.copy(levelMb = clamped) else band
            }
            current.copy(bands = updatedBands, activePresetName = "Custom")
        }
    }

    fun setBassBoost(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        try {
            bassBoost?.let {
                if (it.strengthSupported) it.setStrength(clamped.toShort())
            }
        } catch (_: Exception) {
        }
        _state.update { it.copy(bassBoostStrength = clamped, activePresetName = "Custom") }
    }

    fun setLoudnessGain(gainMb: Int) {
        val clamped = gainMb.coerceIn(0, 1500)
        try {
            loudnessEnhancer?.setTargetGain(clamped)
        } catch (_: Exception) {
        }
        _state.update { it.copy(loudnessGainMb = clamped, activePresetName = "Custom") }
    }

    fun setVirtualizer(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        try {
            virtualizer?.let {
                if (it.strengthSupported) it.setStrength(clamped.toShort())
            }
        } catch (_: Exception) {
        }
        _state.update { it.copy(virtualizerStrength = clamped, activePresetName = "Custom") }
    }

    fun applyPreset(
        name: String,
        bandLevelsMb: List<Long>,
        bassStrength: Int,
        loudnessGainMb: Int,
        virtualizerStrength: Int = _state.value.virtualizerStrength
    ) {
        val minMb = _state.value.minLevelMb
        val maxMb = _state.value.maxLevelMb
        val updatedBands = _state.value.bands.mapIndexed { idx, band ->
            val raw = bandLevelsMb.getOrNull(idx)?.toInt() ?: 0
            val clamped = raw.coerceIn(minMb, maxMb)
            try {
                equalizer?.setBandLevel(idx.toShort(), clamped.toShort())
            } catch (_: Exception) {
            }
            band.copy(levelMb = clamped)
        }
        val safeBass = bassStrength.coerceIn(0, 1000)
        val safeLoud = loudnessGainMb.coerceIn(0, 1500)
        val safeVirt = virtualizerStrength.coerceIn(0, 1000)
        try {
            bassBoost?.let { if (it.strengthSupported) it.setStrength(safeBass.toShort()) }
            loudnessEnhancer?.setTargetGain(safeLoud)
            virtualizer?.let { if (it.strengthSupported) it.setStrength(safeVirt.toShort()) }
        } catch (_: Exception) {
        }
        _state.update {
            it.copy(
                activePresetName = name,
                bands = updatedBands,
                bassBoostStrength = safeBass,
                loudnessGainMb = safeLoud,
                virtualizerStrength = safeVirt
            )
        }
    }

    fun resetToFlat() {
        val zeroBands = _state.value.bands.map { 0L }
        applyPreset(
            name = "Flat Reference",
            bandLevelsMb = zeroBands,
            bassStrength = 0,
            loudnessGainMb = 0,
            virtualizerStrength = 0
        )
    }

    fun updateActiveAudioCodec(codecInfo: String) {
        _state.update { it.copy(activeAudioCodec = codecInfo) }
    }

    fun releaseEffects() {
        try {
            equalizer?.release()
            bassBoost?.release()
            loudnessEnhancer?.release()
            virtualizer?.release()
        } catch (_: Exception) {
        }
        equalizer = null
        bassBoost = null
        loudnessEnhancer = null
        virtualizer = null
        currentSessionId = 0
        _state.update { it.copy(isAttached = false, sessionId = 0) }
    }

    fun formatFrequencyLabel(hz: Int): String {
        return if (hz >= 1000) {
            val khz = hz / 1000.0
            if (khz % 1.0 == 0.0) {
                "${khz.toInt()} kHz"
            } else {
                String.format(Locale.US, "%.1f kHz", khz)
            }
        } else {
            "$hz Hz"
        }
    }
}

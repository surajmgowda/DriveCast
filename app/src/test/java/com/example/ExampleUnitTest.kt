package com.example

import com.example.car.CabinUtilityManager
import com.example.car.CarDiagnosticLogger
import com.example.car.DiagLevel
import com.example.data.AspectMode
import com.example.data.OrientationLockMode
import com.example.data.QualityProfile
import com.example.data.SubtitleColorPreset
import com.example.data.SyncRepository
import com.example.player.AudioEqualizerEngine
import com.example.player.ExternalSubtitleParser
import com.example.safety.SafetyGateManager
import com.example.sink.SinkType
import com.example.sink.VideoSinkRouter
import com.example.ui.formatCountdownSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun stableDocId_producesValidFirestoreIds() {
        val id = SyncRepository.stableDocId("content://com.android.externalstorage/Movies/test.mkv")
        assertTrue(id.matches(Regex("^[a-zA-Z0-9_\\-]{1,128}$")))
    }

    @Test
    fun safetyGate_failsClosedWhenMoving_andAllowsAudioDriveMode() {
        SafetyGateManager.setAudioDriveMode(false)
        SafetyGateManager.updateCarHardwareSpeed(5.0f) // 18 km/h
        assertTrue(SafetyGateManager.state.value.isBlocked)
        assertTrue(SafetyGateManager.shouldPausePlayback())

        // With Audio Drive Mode enabled, video remains blocked/blanked, but audio is permitted
        SafetyGateManager.setAudioDriveMode(true)
        assertTrue(SafetyGateManager.state.value.isBlocked)
        assertFalse(SafetyGateManager.shouldPausePlayback())

        SafetyGateManager.setAudioDriveMode(false)
        SafetyGateManager.updateCarHardwareSpeed(0.0f)
        assertFalse(SafetyGateManager.state.value.isBlocked)
        assertFalse(SafetyGateManager.shouldPausePlayback())
    }

    @Test
    fun sinkRouter_defaultsToPhoneSurface() {
        VideoSinkRouter.routeToBestSink()
        assertEquals(SinkType.PHONE_SURFACE, VideoSinkRouter.telemetry.value.activeSinkType)
    }

    @Test
    fun enumParsers_fallbackSafelyOnUnknownValues() {
        assertEquals(AspectMode.FIT, AspectMode.fromRaw("UNKNOWN_MODE"))
        assertEquals(QualityProfile.AUTO_ADAPTIVE, QualityProfile.fromRaw("LEGACY_PROFILE"))
        assertEquals(SubtitleColorPreset.WHITE, SubtitleColorPreset.fromRaw("NEON_PINK"))
    }

    @Test
    fun equalizerEngine_appliesPresetsClampsAndResets() {
        AudioEqualizerEngine.applyPreset(
            name = "Cinema Surround",
            bandLevelsMb = listOf(400L, 200L, 0L, 150L, 300L),
            bassStrength = 600,
            loudnessGainMb = 350,
            virtualizerStrength = 450
        )
        val afterPreset = AudioEqualizerEngine.state.value
        assertEquals("Cinema Surround", afterPreset.activePresetName)
        assertEquals(400, afterPreset.bands[0].levelMb)
        assertEquals(600, afterPreset.bassBoostStrength)
        assertEquals(350, afterPreset.loudnessGainMb)
        assertEquals(450, afterPreset.virtualizerStrength)

        AudioEqualizerEngine.setBandLevel(0, 9999)
        AudioEqualizerEngine.setVirtualizer(-250)
        val afterClamp = AudioEqualizerEngine.state.value
        assertEquals(afterClamp.maxLevelMb, afterClamp.bands[0].levelMb)
        assertEquals(0, afterClamp.virtualizerStrength)
        assertEquals("Custom", afterClamp.activePresetName)

        AudioEqualizerEngine.resetToFlat()
        val afterReset = AudioEqualizerEngine.state.value
        assertEquals("Flat Reference", afterReset.activePresetName)
        assertTrue(afterReset.bands.all { it.levelMb == 0 })
        assertEquals(0, afterReset.bassBoostStrength)
        assertEquals(0, afterReset.loudnessGainMb)
        assertEquals(0, afterReset.virtualizerStrength)
    }

    @Test
    fun equalizerEngine_formatsFrequenciesCleanly() {
        assertEquals("60 Hz", AudioEqualizerEngine.formatFrequencyLabel(60))
        assertEquals("3.6 kHz", AudioEqualizerEngine.formatFrequencyLabel(3600))
        assertEquals("14 kHz", AudioEqualizerEngine.formatFrequencyLabel(14000))
    }

    @Test
    fun cabinUtility_batteryGuardTriggersOnlyWhenDischargingBelowThreshold() {
        CabinUtilityManager.setBatteryGuardThreshold(20)
        CabinUtilityManager.updateBatterySnapshotForTest(percent = 18, isCharging = true)
        assertFalse(CabinUtilityManager.state.value.batteryGuardTriggered)

        CabinUtilityManager.updateBatterySnapshotForTest(percent = 18, isCharging = false)
        assertTrue(CabinUtilityManager.state.value.batteryGuardTriggered)

        CabinUtilityManager.updateBatterySnapshotForTest(percent = 25, isCharging = false)
        assertFalse(CabinUtilityManager.state.value.batteryGuardTriggered)
    }

    @Test
    fun countdownFormatting_formatsMinutesAndHours() {
        assertEquals("15:00", formatCountdownSeconds(900))
        assertEquals("1:05:09", formatCountdownSeconds(3909))
    }

    @Test
    fun externalSubtitleParser_parsesSrtAndVttWithSyncOffset() {
        val srt = """
            1
            00:00:05,000 --> 00:00:08,500
            <i>Plugged into MG fast charger</i>

            2
            00:00:10,000 --> 00:00:12,000
            Ready to watch
        """.trimIndent()
        val srtCues = ExternalSubtitleParser.parse(srt)
        assertEquals(2, srtCues.size)
        assertEquals("Plugged into MG fast charger", srtCues[0].text)
        assertEquals(listOf("Plugged into MG fast charger"), ExternalSubtitleParser.findActiveCueTexts(srtCues, 6000L, 0))
        assertTrue(ExternalSubtitleParser.findActiveCueTexts(srtCues, 6000L, 2000).isEmpty())
        assertEquals(listOf("Plugged into MG fast charger"), ExternalSubtitleParser.findActiveCueTexts(srtCues, 7000L, 2000))

        val vtt = """
            WEBVTT

            00:15.250 --> 00:18.000 align:center
            Cabin audio locked
        """.trimIndent()
        val vttCues = ExternalSubtitleParser.parse(vtt)
        assertEquals(1, vttCues.size)
        assertEquals(15250L, vttCues[0].startMs)
        assertEquals("Cabin audio locked", vttCues[0].text)
    }

    @Test
    fun displayAndOrientationEnums_resolveUltrawideAndOrientationModes() {
        assertEquals(AspectMode.ULTRAWIDE_21_9, AspectMode.fromRaw("ULTRAWIDE_21_9"))
        assertEquals(AspectMode.STRETCH, AspectMode.fromRaw("STRETCH"))
        assertEquals(OrientationLockMode.LANDSCAPE, OrientationLockMode.fromRaw("LANDSCAPE"))
        assertEquals(OrientationLockMode.AUTO, OrientationLockMode.fromRaw("INVALID"))
    }

    @Test
    fun carDiagnosticLogger_recordsAndExportsEvents() {
        CarDiagnosticLogger.clear()
        CarDiagnosticLogger.updateSessionInfo(active = true, hostPackage = "com.google.android.projection.gearhead", carApiLevel = 7)
        CarDiagnosticLogger.log("CarSession", "onCreateScreen", DiagLevel.EVENT)
        CarDiagnosticLogger.log("CarSurface", "onSurfaceAvailable(1280x720)", DiagLevel.INFO)

        val state = CarDiagnosticLogger.state.value
        assertTrue(state.sessionActive)
        assertEquals(2, state.entries.size)
        val report = CarDiagnosticLogger.exportFormattedReport()
        assertTrue(report.contains("com.google.android.projection.gearhead"))
        assertTrue(report.contains("onSurfaceAvailable(1280x720)"))

        CarDiagnosticLogger.clear()
        assertTrue(CarDiagnosticLogger.state.value.entries.isEmpty())
    }
}

package com.example.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.car.CabinUtilityState
import com.example.car.TransportLinkState
import com.example.data.CabinPreferencesState
import com.example.data.PreferredTransport
import com.example.data.QualityProfile
import com.example.data.SyncedSettings
import com.example.safety.SafetyGateManager
import com.example.safety.SafetyGateState
import com.example.sink.VideoSinkTelemetry
import com.example.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

@Composable
fun CarLinkScreen(
    settingsState: UiState<SyncedSettings>,
    cabinPrefs: CabinPreferencesState,
    cabinUtility: CabinUtilityState,
    safetyState: SafetyGateState,
    sinkTelemetry: VideoSinkTelemetry,
    transportState: TransportLinkState,
    offlineUsageMb: Long,
    onRequestPermissions: () -> Unit,
    onRefreshLinks: () -> Unit,
    onUpdateSettings: (QualityProfile?, PreferredTransport?, Boolean?, Int?) -> Unit,
    onSetAudioDriveMode: (Boolean) -> Unit,
    onSetPhoneRemoteMode: (Boolean) -> Unit,
    onSetBatteryGuardThreshold: (Int) -> Unit,
    onStartChargingTimer: (Int) -> Unit,
    onCancelChargingTimer: () -> Unit,
    onSignOut: () -> Unit
) {
    val settings = (settingsState as? UiState.Success)?.data ?: SyncedSettings()
    val currentProfile = QualityProfile.fromRaw(settings.qualityProfile)
    val currentTransport = PreferredTransport.fromRaw(settings.preferredTransport)
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentUserEmail = if (isFirebaseConfigured(context)) {
        Firebase.auth.currentUser?.email ?: "Signed In"
    } else {
        "Offline Car Mode"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Hardware VideoSink & Protocol Telemetry
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.CastConnected, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Automotive Display Protocol", style = MaterialTheme.typography.titleLarge)
                        }
                        FilledTonalButton(onClick = onRefreshLinks) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh telemetry")
                        }
                    }

                    TelemetryRow("Active VideoSink", sinkTelemetry.activeSinkType.label)
                    TelemetryRow(
                        "Surface Pipeline",
                        if (sinkTelemetry.surfaceWidth > 0) {
                            "${sinkTelemetry.surfaceWidth}x${sinkTelemetry.surfaceHeight} • Zero-Copy MediaCodec"
                        } else {
                            "Awaiting Car / External Surface"
                        }
                    )
                    TelemetryRow("Android Auto Bridge", if (sinkTelemetry.isCarConnected) "Connected (CarAppService)" else "Standby (Enable AA Dev Mode)")
                    TelemetryRow(
                        "USB-C DisplayPort",
                        transportState.usbPresentationDisplayName?.let {
                            "$it (${transportState.usbPresentationResolution})"
                        } ?: "Not Connected"
                    )
                    TelemetryRow("Estimated Pipeline Latency", "${sinkTelemetry.estimatedLatencyMs} ms")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Phone Remote Mode on Projection", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Dim phone into a touchpad remote when projecting externally",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = cabinPrefs.phoneRemoteModePreferred,
                            onCheckedChange = onSetPhoneRemoteMode,
                            modifier = Modifier.testTag("phone_remote_mode_switch")
                        )
                    }

                    Text("Preferred Sink Routing", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PreferredTransport.entries.forEach { mode ->
                            FilterChip(
                                selected = currentTransport == mode,
                                onClick = { onUpdateSettings(null, mode, null, null) },
                                label = { Text(mode.label) }
                            )
                        }
                    }

                    Text("Hardware Encode / Bitrate Profile", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QualityProfile.entries.forEach { profile ->
                            FilterChip(
                                selected = currentProfile == profile,
                                onClick = { onUpdateSettings(profile, null, null, null) },
                                label = { Text(profile.label) }
                            )
                        }
                    }
                }
            }
        }

        // 1b. Android Auto Head-Unit Visibility Audit & Lifecycle Logs
        item {
            CarDiagnosticsCard()
        }

        // 2. EV Charging Break Timer & Phone Battery Guard
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Text("EV Charging Timer & Battery Guard", style = MaterialTheme.typography.titleLarge)
                        }
                        if (cabinUtility.chargingRemainingSec > 0) {
                            TextButton(onClick = onCancelChargingTimer) {
                                Text("Stop")
                            }
                        }
                    }

                    val timerText = when {
                        cabinUtility.chargingExpired -> "Charging break complete!"
                        cabinUtility.chargingRemainingSec > 0 ->
                            "${formatCountdownSeconds(cabinUtility.chargingRemainingSec)} remaining"
                        else -> "Inactive"
                    }
                    TelemetryRow("EV Charging Break Timer", timerText)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(15, 20, 30, 45, 60).forEach { mins ->
                            FilterChip(
                                selected = cabinUtility.chargingRemainingSec in ((mins - 1) * 60 + 1)..(mins * 60),
                                onClick = { onStartChargingTimer(mins) },
                                label = { Text("${mins}m") },
                                modifier = Modifier.testTag("charging_timer_${mins}m")
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BatteryChargingFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        TelemetryRow(
                            "OnePlus 15R Battery",
                            "${cabinUtility.batteryPercent}% • ${if (cabinUtility.isPhoneCharging) "Charging" else "On Battery"}"
                        )
                    }

                    Text(
                        text = "Phone Battery Guard Auto-Pause Threshold",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0, 15, 20, 30).forEach { threshold ->
                            FilterChip(
                                selected = cabinPrefs.batteryGuardThresholdPercent == threshold,
                                onClick = { onSetBatteryGuardThreshold(threshold) },
                                label = { Text(if (threshold == 0) "Off" else "$threshold%") },
                                modifier = Modifier.testTag("battery_guard_$threshold")
                            )
                        }
                    }
                }
            }
        }

        // 3. Secure Bluetooth & Audio Route
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Filled.BluetoothConnected, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Text("Secure Bluetooth & Audio Link", style = MaterialTheme.typography.titleLarge)
                    }

                    TelemetryRow("Audio Output Route", transportState.activeAudioRoute)
                    TelemetryRow(
                        "Car BT Head Unit",
                        transportState.connectedCarAudioDevice ?: "No paired vehicle active"
                    )
                    TelemetryRow(
                        "Link Encryption",
                        if (transportState.isEncryptedBluetoothLink) "Bonded Encrypted A2DP" else "Standard"
                    )

                    if (!transportState.bluetoothPermissionGranted || !safetyState.locationPermissionGranted) {
                        Button(
                            onClick = onRequestPermissions,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("grant_car_permissions_button")
                        ) {
                            Icon(Icons.Filled.Usb, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Grant Bluetooth & GPS Safety Permissions")
                        }
                    }
                }
            }
        }

        // 4. Parked Safety Gate & Audio-Only Drive Mode
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Text("Parked-Only Safety Gate", style = MaterialTheme.typography.titleLarge)
                    }

                    TelemetryRow("Speed Threshold", "3.0 km/h (Mandatory • Fail-Closed)")
                    TelemetryRow("Measured Speed", "${"%.1f".format(safetyState.speedKmh)} km/h")
                    TelemetryRow("Thermal Status", safetyState.thermalHealth.label)
                    TelemetryRow(
                        "Gate State",
                        if (safetyState.isBlocked) safetyState.blockReason ?: "LOCKED" else "VERIFIED PARKED"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Audio-Only Drive Mode", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Blank all video displays above 3 km/h while continuing audio playback",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = cabinPrefs.audioDriveModeEnabled,
                            onCheckedChange = onSetAudioDriveMode,
                            modifier = Modifier.testTag("audio_drive_mode_switch")
                        )
                    }

                    if (safetyState.needsFreshParkedConfirmation) {
                        Button(
                            onClick = { SafetyGateManager.confirmVehicleParked() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(Icons.Filled.DirectionsCar, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_confirm_parked))
                        }
                    }
                }
            }
        }

        // 5. Offline Cache & Cloud Sync Account
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Filled.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Cloud Sync & Offline Storage", style = MaterialTheme.typography.titleLarge)
                    }

                    TelemetryRow("Google Account", currentUserEmail)
                    TelemetryRow("Offline Media Used", "$offlineUsageMb MB / ${settings.offlineCacheLimitMb} MB")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-Resume Last Position", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = settings.autoResume,
                            onCheckedChange = { onUpdateSettings(null, null, it, null) }
                        )
                    }

                    Text(
                        text = "Offline Cache Budget: ${settings.offlineCacheLimitMb} MB",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = settings.offlineCacheLimitMb.toFloat(),
                        onValueChange = { onUpdateSettings(null, null, null, it.toInt()) },
                        valueRange = 512f..8192f
                    )

                    OutlinedButton(
                        onClick = onSignOut,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("sign_out_button")
                    ) {
                        Icon(Icons.Filled.Logout, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sign_out))
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

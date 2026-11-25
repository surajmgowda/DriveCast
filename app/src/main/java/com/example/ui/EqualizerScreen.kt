package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.SyncedEqPreset
import com.example.player.AudioEqualizerEngine
import com.example.player.EqualizerState
import com.example.viewmodel.UiState
import java.util.Locale

private val BUILT_IN_PRESET_IDS = setOf(
    "windsor_cabin",
    "cinema_pro",
    "vocal_clarity",
    "flat_studio"
)

@Composable
fun AudioEqScreen(
    eqState: EqualizerState,
    presetsState: UiState<List<SyncedEqPreset>>,
    activeAudioRoute: String,
    onSelectPreset: (SyncedEqPreset, List<SyncedEqPreset>) -> Unit,
    onSaveCustomPreset: (String, List<SyncedEqPreset>) -> Unit,
    onDeleteCustomPreset: (SyncedEqPreset) -> Unit,
    onResetFlat: () -> Unit
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("MG Windsor Night") }
    val presets = (presetsState as? UiState.Success)?.data.orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EqualizerSessionHeader(
                        eqState = eqState,
                        activeAudioRoute = activeAudioRoute
                    )

                    PresetSelectorRow(
                        eqState = eqState,
                        presets = presets,
                        onSelectPreset = onSelectPreset,
                        onDeleteCustomPreset = onDeleteCustomPreset
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onResetFlat,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("eq_reset_flat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.RestartAlt,
                                contentDescription = stringResource(R.string.action_reset_flat),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_reset_flat))
                        }

                        Button(
                            onClick = { showSaveDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("save_eq_preset_button")
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Save & Sync Preset", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${eqState.bands.size}-Band Real-Time DSP",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = eqState.activePresetName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    EqualizerCurveVisualizer(
                        eqState = eqState,
                        height = 148.dp
                    )

                    EqualizerBandsAndFxControls(eqState = eqState)
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Equalizer Preset") },
            text = {
                OutlinedTextField(
                    value = presetNameInput,
                    onValueChange = { presetNameInput = it },
                    label = { Text("Preset Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveCustomPreset(presetNameInput, presets)
                        showSaveDialog = false
                    }
                ) {
                    Text("Sync to Cloud")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealTimeEqualizerSheet(
    eqState: EqualizerState,
    presetsState: UiState<List<SyncedEqPreset>>,
    activeAudioRoute: String,
    onSelectPreset: (SyncedEqPreset, List<SyncedEqPreset>) -> Unit,
    onSaveCustomPreset: (String, List<SyncedEqPreset>) -> Unit,
    onResetFlat: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val presets = (presetsState as? UiState.Success)?.data.orEmpty()
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("Live Tuning") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EqualizerSessionHeader(
                eqState = eqState,
                activeAudioRoute = activeAudioRoute
            )

            EqualizerCurveVisualizer(
                eqState = eqState,
                height = 112.dp
            )

            PresetSelectorRow(
                eqState = eqState,
                presets = presets,
                onSelectPreset = onSelectPreset,
                onDeleteCustomPreset = null
            )

            EqualizerBandsAndFxControls(eqState = eqState)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onResetFlat,
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_reset_flat))
                }
                Button(
                    onClick = { showSaveDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save Preset")
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Live Preset") },
            text = {
                OutlinedTextField(
                    value = presetNameInput,
                    onValueChange = { presetNameInput = it },
                    label = { Text("Preset Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveCustomPreset(presetNameInput, presets)
                        showSaveDialog = false
                    }
                ) {
                    Text("Sync to Cloud")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun EqualizerSessionHeader(
    eqState: EqualizerState,
    activeAudioRoute: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(
                    text = "Hi-Fi Cabin Equalizer",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sessionLabel = if (eqState.isAttached && eqState.sessionId > 0) {
                    "ExoPlayer DSP #${eqState.sessionId}"
                } else {
                    "Standby DSP"
                }
                Text(
                    text = "$sessionLabel • ${eqState.activeAudioCodec} • $activeAudioRoute",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Switch(
            checked = eqState.isEnabled,
            onCheckedChange = { AudioEqualizerEngine.setEnabled(it) },
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("eq_enable_switch")
        )
    }
}

@Composable
private fun PresetSelectorRow(
    eqState: EqualizerState,
    presets: List<SyncedEqPreset>,
    onSelectPreset: (SyncedEqPreset, List<SyncedEqPreset>) -> Unit,
    onDeleteCustomPreset: ((SyncedEqPreset) -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEach { preset ->
            val isCustom = preset.id !in BUILT_IN_PRESET_IDS
            FilterChip(
                selected = eqState.activePresetName == preset.name,
                onClick = { onSelectPreset(preset, presets) },
                label = { Text(preset.name) },
                trailingIcon = if (isCustom && onDeleteCustomPreset != null) {
                    {
                        IconButton(
                            onClick = { onDeleteCustomPreset(preset) },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_delete_preset),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier.testTag("eq_preset_chip_${preset.id}")
            )
        }
    }
}

@Composable
fun EqualizerCurveVisualizer(
    eqState: EqualizerState,
    height: Dp = 140.dp
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val disabledColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline
    val surfaceBg = MaterialTheme.colorScheme.background

    val activeColor = if (eqState.isEnabled) primaryColor else disabledColor
    val bands = eqState.bands

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(surfaceBg.copy(alpha = 0.72f))
            .border(1.dp, gridColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val topPad = 14.dp.toPx()
                val bottomPad = 14.dp.toPx()
                val usableH = (h - topPad - bottomPad).coerceAtLeast(1f)
                val centerY = topPad + usableH / 2f

                // Zero dB dashed centerline + +/- grid lines
                drawLine(
                    color = gridColor.copy(alpha = 0.35f),
                    start = Offset(0f, topPad),
                    end = Offset(w, topPad),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = gridColor.copy(alpha = 0.75f),
                    start = Offset(0f, centerY),
                    end = Offset(w, centerY),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )
                drawLine(
                    color = gridColor.copy(alpha = 0.35f),
                    start = Offset(0f, topPad + usableH),
                    end = Offset(w, topPad + usableH),
                    strokeWidth = 1.dp.toPx()
                )

                if (bands.isEmpty()) return@Canvas

                val stepX = if (bands.size == 1) 0f else w / (bands.size + 1).toFloat()
                val points = bands.mapIndexed { idx, band ->
                    val range = (band.maxMb - band.minMb).coerceAtLeast(1).toFloat()
                    val norm = ((band.levelMb - band.minMb) / range).coerceIn(0f, 1f)
                    val x = stepX * (idx + 1)
                    val y = topPad + usableH * (1f - norm)
                    Offset(x, y)
                }

                // Vertical gain bars from 0dB line
                val barW = 14.dp.toPx()
                points.forEach { pt ->
                    val barTop = minOf(pt.y, centerY)
                    val barHeight = kotlin.math.abs(pt.y - centerY).coerceAtLeast(3.dp.toPx())
                    drawRoundRect(
                        color = activeColor.copy(alpha = 0.24f),
                        topLeft = Offset(pt.x - barW / 2f, barTop),
                        size = Size(barW, barHeight),
                        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                    )
                }

                // Smooth response curve path
                val curvePath = Path().apply {
                    moveTo(0f, points.first().y)
                    lineTo(points.first().x, points.first().y)
                    for (i in 0 until points.lastIndex) {
                        val p0 = points[i]
                        val p1 = points[i + 1]
                        val ctrlX = (p0.x + p1.x) / 2f
                        cubicTo(ctrlX, p0.y, ctrlX, p1.y, p1.x, p1.y)
                    }
                    lineTo(w, points.last().y)
                }

                val fillPath = Path().apply {
                    addPath(curvePath)
                    lineTo(w, topPad + usableH)
                    lineTo(0f, topPad + usableH)
                    close()
                }

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            activeColor.copy(alpha = 0.28f),
                            activeColor.copy(alpha = 0.02f)
                        ),
                        startY = topPad,
                        endY = topPad + usableH
                    )
                )

                drawPath(
                    path = curvePath,
                    color = activeColor,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Band node rings
                points.forEach { pt ->
                    drawCircle(
                        color = surfaceBg,
                        radius = 6.dp.toPx(),
                        center = pt
                    )
                    drawCircle(
                        color = secondaryColor,
                        radius = 4.5.dp.toPx(),
                        center = pt
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            bands.forEach { band ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = String.format(Locale.US, "%+.1f", band.levelMb / 100f),
                        style = MaterialTheme.typography.labelSmall,
                        color = activeColor
                    )
                    Text(
                        text = AudioEqualizerEngine.formatFrequencyLabel(band.centerFreqHz),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EqualizerBandsAndFxControls(eqState: EqualizerState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        eqState.bands.forEach { band ->
            val freqText = AudioEqualizerEngine.formatFrequencyLabel(band.centerFreqHz)
            val dbText = String.format(Locale.US, "%+.1f dB", band.levelMb / 100f)

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(freqText, style = MaterialTheme.typography.labelMedium)
                    Text(
                        dbText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = band.levelMb.toFloat(),
                    onValueChange = { AudioEqualizerEngine.setBandLevel(band.index, it.toInt()) },
                    valueRange = band.minMb.toFloat()..band.maxMb.toFloat(),
                    enabled = eqState.isEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("eq_band_slider_${band.index}")
                )
            }
        }

        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Cabin Bass Boost", style = MaterialTheme.typography.labelMedium)
                Text(
                    "${eqState.bassBoostStrength / 10}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Slider(
                value = eqState.bassBoostStrength.toFloat(),
                onValueChange = { AudioEqualizerEngine.setBassBoost(it.toInt()) },
                valueRange = 0f..1000f,
                enabled = eqState.isEnabled,
                modifier = Modifier.testTag("eq_bass_slider")
            )
        }

        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Dialog Loudness Enhancer", style = MaterialTheme.typography.labelMedium)
                Text(
                    "+${eqState.loudnessGainMb / 100} dB",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Slider(
                value = eqState.loudnessGainMb.toFloat(),
                onValueChange = { AudioEqualizerEngine.setLoudnessGain(it.toInt()) },
                valueRange = 0f..1500f,
                enabled = eqState.isEnabled,
                modifier = Modifier.testTag("eq_loudness_slider")
            )
        }

        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Cabin Surround Virtualizer", style = MaterialTheme.typography.labelMedium)
                Text(
                    "${eqState.virtualizerStrength / 10}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Slider(
                value = eqState.virtualizerStrength.toFloat(),
                onValueChange = { AudioEqualizerEngine.setVirtualizer(it.toInt()) },
                valueRange = 0f..1000f,
                enabled = eqState.isEnabled,
                modifier = Modifier.testTag("eq_virtualizer_slider")
            )
        }
    }
}

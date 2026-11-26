package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.car.CarDiagnosticLogger
import com.example.car.DiagLevel

/**
 * In-app Android Auto Visibility & CarSession Lifecycle Diagnostics Panel.
 * Surfaces head-unit discovery blockers (sideload installer source, Android Auto version,
 * manifest metadata, exported services) and live timestamped CarAppService / MediaLibraryService events.
 */
@Composable
fun CarDiagnosticsCard() {
    val context = LocalContext.current
    val diagState by CarDiagnosticLogger.state.collectAsStateWithLifecycle()
    val audit = diagState.audit

    LaunchedEffect(Unit) {
        CarDiagnosticLogger.runVisibilityAudit(context)
    }

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
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Android Auto Diagnostics",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                FilledTonalButton(
                    onClick = { CarDiagnosticLogger.runVisibilityAudit(context) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("run_car_audit_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Audit")
                }
            }

            AuditStatusRow("Android Auto App", if (audit.androidAutoInstalled) audit.androidAutoVersion else "Not Installed")
            AuditStatusRow("Installer Source", audit.installerPackage ?: "Sideloaded (Non-Play APK)")
            AuditStatusRow(
                "Manifest & Services",
                "Meta=${audit.hasCarAppMetadata} • CarSvc=${audit.carAppServiceEnabled} • MediaSvc=${audit.mediaServiceEnabled}"
            )
            AuditStatusRow(
                "Car Session",
                if (diagState.sessionActive) {
                    "Active (${diagState.connectedHostPackage ?: "Host"} • API ${diagState.carApiLevel ?: "?"})"
                } else {
                    "Awaiting CarAppService / Play Store trust"
                }
            )

            // Actionable head-unit visibility finding banner
            val blockerColor = if (audit.isPlayStoreInstalled && audit.hasCarAppMetadata && audit.carAppServiceEnabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            }
            val blockerTextColor = if (audit.isPlayStoreInstalled && audit.hasCarAppMetadata && audit.carAppServiceEnabled) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(blockerColor)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = if (audit.isPlayStoreInstalled && audit.hasCarAppMetadata) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Filled.Warning
                    },
                    contentDescription = null,
                    tint = if (audit.isPlayStoreInstalled && audit.hasCarAppMetadata) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Text(
                    text = audit.likelyBlockerSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = blockerTextColor
                )
            }

            // Live timestamped lifecycle event log box
            Text(
                text = "CarSession & MediaLibrary Lifecycle Logs (${diagState.entries.size})",
                style = MaterialTheme.typography.labelLarge
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 220.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (diagState.entries.isEmpty()) {
                    Text(
                        text = "No CarAppService events recorded yet. Connect to your MG Windsor Pro EV or Android Auto Desktop Head Unit.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    diagState.entries.asReversed().forEach { entry ->
                        val entryColor = when (entry.level) {
                            DiagLevel.ERROR -> MaterialTheme.colorScheme.error
                            DiagLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            DiagLevel.EVENT -> MaterialTheme.colorScheme.primary
                            DiagLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = "${entry.timeLabel} [${entry.source}] ${entry.message}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = entryColor
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val report = CarDiagnosticLogger.exportFormattedReport()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("DriveCast Car Diagnostics", report))
                        Toast.makeText(context, "Diagnostic report copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("copy_car_logs_button")
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Logs")
                }

                OutlinedButton(
                    onClick = { CarDiagnosticLogger.clear() },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("clear_car_logs_button")
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Clear")
                }
            }
        }
    }
}

@Composable
private fun AuditStatusRow(label: String, value: String) {
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

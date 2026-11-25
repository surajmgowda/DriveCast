package com.example.car

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.player.VideoDeckPlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DiagLevel(val tag: String) {
    INFO("INFO"),
    EVENT("EVENT"),
    WARN("WARN"),
    ERROR("ERROR")
}

data class CarDiagnosticLogEntry(
    val timestampMs: Long,
    val timeLabel: String,
    val level: DiagLevel,
    val source: String,
    val message: String
)

data class CarVisibilityAudit(
    val auditedAtLabel: String = "Not checked",
    val androidAutoInstalled: Boolean = false,
    val androidAutoVersion: String = "Not found",
    val installerPackage: String? = null,
    val isPlayStoreInstalled: Boolean = false,
    val isDebuggableBuild: Boolean = false,
    val hasCarAppMetadata: Boolean = false,
    val minCarApiLevel: Int = 0,
    val carAppServiceEnabled: Boolean = false,
    val mediaServiceEnabled: Boolean = false,
    val likelyBlockerSummary: String = "Tap Run Audit to inspect head unit discovery."
)

data class CarDiagnosticState(
    val entries: List<CarDiagnosticLogEntry> = emptyList(),
    val audit: CarVisibilityAudit = CarVisibilityAudit(),
    val sessionActive: Boolean = false,
    val connectedHostPackage: String? = null,
    val carApiLevel: Int? = null
)

/**
 * Persistent ring-buffer diagnostic logger for Android Auto CarAppService & MediaLibraryService
 * lifecycle events, host validation, surface callbacks, and head-unit visibility auditing.
 */
object CarDiagnosticLogger {
    private const val TAG = "DriveCastCarDiag"
    private const val LOG_FILE_NAME = "car_diagnostics.log"
    private const val MAX_ENTRIES = 160

    private val lock = Any()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null
    private var initialized = false

    private val _state = MutableStateFlow(CarDiagnosticState())
    val state: StateFlow<CarDiagnosticState> = _state.asStateFlow()

    fun init(context: Context?) {
        val appCtx = context?.applicationContext ?: return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            val file = File(appCtx.filesDir, LOG_FILE_NAME)
            logFile = file
            if (file.exists()) {
                val restored = runCatching {
                    file.readLines()
                        .takeLast(MAX_ENTRIES)
                        .mapNotNull(::parseLine)
                }.getOrDefault(emptyList())
                _state.update { it.copy(entries = restored) }
            }
        }
    }

    fun log(
        source: String,
        message: String,
        level: DiagLevel = DiagLevel.INFO,
        context: Context? = null
    ) {
        if (context != null && !initialized) {
            init(context)
        }
        val now = System.currentTimeMillis()
        val timeLabel = synchronized(lock) { timeFormatter.format(Date(now)) }
        val entry = CarDiagnosticLogEntry(
            timestampMs = now,
            timeLabel = timeLabel,
            level = level,
            source = source,
            message = message
        )

        runCatching {
            when (level) {
                DiagLevel.ERROR -> Log.e(TAG, "[$source] $message")
                DiagLevel.WARN -> Log.w(TAG, "[$source] $message")
                else -> Log.i(TAG, "[$source] $message")
            }
        }

        synchronized(lock) {
            _state.update { current ->
                val updated = (current.entries + entry).takeLast(MAX_ENTRIES)
                current.copy(entries = updated)
            }
            appendToFileLocked(entry)
        }
    }

    fun updateSessionInfo(
        active: Boolean,
        hostPackage: String? = _state.value.connectedHostPackage,
        carApiLevel: Int? = _state.value.carApiLevel
    ) {
        _state.update {
            it.copy(
                sessionActive = active,
                connectedHostPackage = hostPackage,
                carApiLevel = carApiLevel
            )
        }
    }

    fun runVisibilityAudit(context: Context): CarVisibilityAudit {
        init(context)
        val appCtx = context.applicationContext
        val pm = appCtx.packageManager
        val pkgName = appCtx.packageName

        val gearheadInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo("com.google.android.projection.gearhead", PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo("com.google.android.projection.gearhead", 0)
            }
        }.getOrNull()

        val aaInstalled = gearheadInfo != null
        val aaVersion = if (gearheadInfo != null) {
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                gearheadInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                gearheadInfo.versionCode.toLong()
            }
            "${gearheadInfo.versionName ?: "Unknown"} ($code)"
        } else {
            "Not installed"
        }

        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkgName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkgName)
            }
        }.getOrNull()
        val isPlayStore = installer == "com.android.vending"

        val appInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(
                    pkgName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)
            }
        }.getOrNull()

        val metaData = appInfo?.metaData
        val hasCarMeta = (metaData?.getInt("com.google.android.gms.car.application", 0) ?: 0) != 0
        val minCarApi = metaData?.getInt("androidx.car.app.minCarApiLevel", 0) ?: 0
        val isDebuggable = ((appInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        val carServiceInfo = runCatching {
            val cn = ComponentName(appCtx, VideoDeckCarAppService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getServiceInfo(cn, PackageManager.ComponentInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getServiceInfo(cn, 0)
            }
        }.getOrNull()
        val carServiceOk = carServiceInfo?.exported == true && carServiceInfo.enabled

        val mediaServiceInfo = runCatching {
            val cn = ComponentName(appCtx, VideoDeckPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getServiceInfo(cn, PackageManager.ComponentInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getServiceInfo(cn, 0)
            }
        }.getOrNull()
        val mediaServiceOk = mediaServiceInfo?.exported == true && mediaServiceInfo.enabled

        val blocker = when {
            !aaInstalled ->
                "Android Auto (com.google.android.projection.gearhead) is not installed on this phone."
            !hasCarMeta || !carServiceOk ->
                "Manifest declaration incomplete (carMeta=$hasCarMeta, carServiceExported=$carServiceOk)."
            !isPlayStore ->
                "App was sideloaded (${installer ?: "PackageInstaller"}). Enable Android Auto Developer Settings -> Unknown sources, then restart Android Auto. Note: recent Android Auto builds may still hide sideloaded CarAppService apps on car head units while allowing MediaLibraryService audio."
            else ->
                "All manifest, service, and Play installer checks passed."
        }

        val audit = CarVisibilityAudit(
            auditedAtLabel = synchronized(lock) { timeFormatter.format(Date()) },
            androidAutoInstalled = aaInstalled,
            androidAutoVersion = aaVersion,
            installerPackage = installer,
            isPlayStoreInstalled = isPlayStore,
            isDebuggableBuild = isDebuggable,
            hasCarAppMetadata = hasCarMeta,
            minCarApiLevel = minCarApi,
            carAppServiceEnabled = carServiceOk,
            mediaServiceEnabled = mediaServiceOk,
            likelyBlockerSummary = blocker
        )

        _state.update { it.copy(audit = audit) }
        log(
            source = "Audit",
            message = "AA=$aaVersion | installer=${installer ?: "sideload"} | carMeta=$hasCarMeta | minApi=$minCarApi | carSvc=$carServiceOk | mediaSvc=$mediaServiceOk | debuggable=$isDebuggable",
            level = if (!isPlayStore || !hasCarMeta || !carServiceOk) DiagLevel.WARN else DiagLevel.INFO,
            context = appCtx
        )
        return audit
    }

    fun exportFormattedReport(): String {
        val current = _state.value
        val audit = current.audit
        return buildString {
            appendLine("=== DriveCast Android Auto Diagnostic Report ===")
            appendLine("Audited At: ${audit.auditedAtLabel}")
            appendLine("Android Auto Installed: ${audit.androidAutoInstalled} (${audit.androidAutoVersion})")
            appendLine("Installer Package: ${audit.installerPackage ?: "Sideloaded / Unknown"} (PlayStore=${audit.isPlayStoreInstalled})")
            appendLine("Debuggable Build: ${audit.isDebuggableBuild}")
            appendLine("Manifest car.application meta-data: ${audit.hasCarAppMetadata} | minCarApiLevel=${audit.minCarApiLevel}")
            appendLine("VideoDeckCarAppService Exported/Enabled: ${audit.carAppServiceEnabled}")
            appendLine("VideoDeckPlaybackService Exported/Enabled: ${audit.mediaServiceEnabled}")
            appendLine("Active Car Session: ${current.sessionActive} | Host=${current.connectedHostPackage ?: "None"} | CarAPI=${current.carApiLevel ?: "-"}")
            appendLine("Diagnosis: ${audit.likelyBlockerSummary}")
            appendLine()
            appendLine("--- Lifecycle Events (${current.entries.size}) ---")
            current.entries.forEach { entry ->
                appendLine("${entry.timeLabel} [${entry.level.tag}] ${entry.source}: ${entry.message}")
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            _state.update { it.copy(entries = emptyList()) }
            runCatching { logFile?.writeText("") }
        }
    }

    private fun appendToFileLocked(entry: CarDiagnosticLogEntry) {
        val file = logFile ?: return
        runCatching {
            val line = "${entry.timestampMs}|${entry.timeLabel}|${entry.level.name}|${entry.source}|${entry.message.replace('\n', ' ')}\n"
            file.appendText(line)
            if (file.length() > 64 * 1024) {
                val trimmed = file.readLines().takeLast(MAX_ENTRIES)
                file.writeText(trimmed.joinToString("\n", postfix = "\n"))
            }
        }
    }

    private fun parseLine(line: String): CarDiagnosticLogEntry? {
        val parts = line.split('|', limit = 5)
        if (parts.size < 5) return null
        val ts = parts[0].toLongOrNull() ?: return null
        val level = runCatching { DiagLevel.valueOf(parts[2]) }.getOrDefault(DiagLevel.INFO)
        return CarDiagnosticLogEntry(
            timestampMs = ts,
            timeLabel = parts[1],
            level = level,
            source = parts[3],
            message = parts[4]
        )
    }
}

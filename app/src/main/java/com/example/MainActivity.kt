package com.example

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.car.BluetoothTransportMonitor
import com.example.data.DriveCastPreferences
import com.example.data.LocalMediaLibrary
import com.example.data.OrientationLockMode
import com.example.data.SyncRepository
import com.example.player.PlayerController
import com.example.safety.SafetyGateManager
import com.example.ui.AudioEqScreen
import com.example.ui.AuthScreen
import com.example.ui.AutoCockpitTopBar
import com.example.ui.CarLinkScreen
import com.example.ui.DeckLibraryScreen
import com.example.ui.PlayerCockpitScreen
import com.example.ui.isFirebaseConfigured
import com.example.ui.signOutUser
import com.example.ui.theme.VideoDeckTheme
import com.example.viewmodel.UiState
import com.example.viewmodel.VideoDeckViewModel
import com.example.widget.DriveCastResumeWidgetProvider
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable

@Serializable data object RouteDeck
@Serializable data object RoutePlayer
@Serializable data object RouteAudioEq
@Serializable data object RouteCarLink

class MainActivity : ComponentActivity() {
    private val isInPipModeState = mutableStateOf(false)
    private val widgetResumePendingState = mutableStateOf(false)

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(EXTRA_PIP_ACTION)) {
                ACTION_TOGGLE -> PlayerController.togglePlayPause()
                ACTION_NEXT -> PlayerController.seekNext()
            }
            updatePipParamsIfSupported()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PlayerController.initAppContext(this)

        if (intent?.getBooleanExtra(DriveCastResumeWidgetProvider.EXTRA_AUTO_RESUME_LAST, false) == true) {
            widgetResumePendingState.value = true
        }

        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter(ACTION_PIP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            VideoDeckTheme {
                AppContent(
                    isInPipMode = isInPipModeState.value,
                    widgetResumePending = widgetResumePendingState.value,
                    onConsumeWidgetResume = { widgetResumePendingState.value = false },
                    onRequestPip = { enterPipModeNow() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(DriveCastResumeWidgetProvider.EXTRA_AUTO_RESUME_LAST, false)) {
            widgetResumePendingState.value = true
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState.value = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val playerState = PlayerController.uiState.value
        val blocked = SafetyGateManager.state.value.isBlocked
        if (playerState.isPlaying && !blocked && playerState.activeUriString != null) {
            enterPipModeNow()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(pipActionReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun buildPipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val playerState = PlayerController.uiState.value
        val aspect = if (playerState.videoWidth > 0 && playerState.videoHeight > 0) {
            Rational(playerState.videoWidth, playerState.videoHeight)
        } else {
            Rational(16, 9)
        }
        val toggleIcon = Icon.createWithResource(
            this,
            if (playerState.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        val toggleIntent = PendingIntent.getBroadcast(
            this,
            101,
            Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_PIP_ACTION, ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getBroadcast(
            this,
            102,
            Intent(ACTION_PIP_CONTROL).setPackage(packageName).putExtra(EXTRA_PIP_ACTION, ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val actions = listOf(
            RemoteAction(
                toggleIcon,
                if (playerState.isPlaying) "Pause" else "Play",
                if (playerState.isPlaying) "Pause playback" else "Play video",
                toggleIntent
            ),
            RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_media_next),
                "Next",
                "Next video",
                nextIntent
            )
        )
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspect)
            .setActions(actions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val autoEnter = playerState.isPlaying && !SafetyGateManager.state.value.isBlocked
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }

    private fun updatePipParamsIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buildPipParams()?.let { setPictureInPictureParams(it) }
        }
    }

    private fun enterPipModeNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buildPipParams()?.let { enterPictureInPictureMode(it) }
        }
    }

    companion object {
        private const val ACTION_PIP_CONTROL = "com.example.pip.CONTROL"
        private const val EXTRA_PIP_ACTION = "pip_action"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_NEXT = "next"
    }
}

@Composable
fun AppContent(
    isInPipMode: Boolean,
    widgetResumePending: Boolean,
    onConsumeWidgetResume: () -> Unit,
    onRequestPip: () -> Unit
) {
    val context = LocalContext.current
    val firebaseReady = remember { isFirebaseConfigured(context) }
    var currentUser by remember {
        mutableStateOf(if (firebaseReady) Firebase.auth.currentUser else null)
    }
    var offlineModeEnabled by remember { mutableStateOf(false) }

    if (firebaseReady) {
        DisposableEffect(Unit) {
            val listener = FirebaseAuth.AuthStateListener { auth ->
                currentUser = auth.currentUser
            }
            Firebase.auth.addAuthStateListener(listener)
            onDispose { Firebase.auth.removeAuthStateListener(listener) }
        }
    }

    val user = currentUser
    if (user == null && !offlineModeEnabled) {
        AuthScreen(
            onAuthSuccess = {
                if (firebaseReady) currentUser = Firebase.auth.currentUser
            },
            onContinueOffline = {
                offlineModeEnabled = true
            }
        )
    } else {
        val activeUserId = user?.uid ?: "offline_local_user"
        key(activeUserId) {
            VideoDeckAppShell(
                currentUserId = activeUserId,
                isInPipMode = isInPipMode,
                widgetResumePending = widgetResumePending,
                onConsumeWidgetResume = onConsumeWidgetResume,
                onRequestPip = onRequestPip,
                onOfflineSignOut = { offlineModeEnabled = false }
            )
        }
    }
}

@Composable
fun VideoDeckAppShell(
    currentUserId: String,
    isInPipMode: Boolean,
    widgetResumePending: Boolean,
    onConsumeWidgetResume: () -> Unit,
    onRequestPip: () -> Unit,
    onOfflineSignOut: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    val viewModel: VideoDeckViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                VideoDeckViewModel(
                    appContext = app,
                    syncRepository = SyncRepository(app),
                    localLibrary = LocalMediaLibrary(app),
                    transportMonitor = BluetoothTransportMonitor(app),
                    preferences = DriveCastPreferences(app),
                    currentUserId = currentUserId
                )
            }
        }
    )

    val videosState by viewModel.videosState.collectAsStateWithLifecycle()
    val playlistsState by viewModel.playlistsState.collectAsStateWithLifecycle()
    val bookmarksState by viewModel.bookmarksState.collectAsStateWithLifecycle()
    val eqPresetsState by viewModel.eqPresetsState.collectAsStateWithLifecycle()
    val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()
    val cabinPrefs by viewModel.cabinPrefsState.collectAsStateWithLifecycle()
    val cabinUtility by viewModel.cabinUtilityState.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val cues by viewModel.cuesState.collectAsStateWithLifecycle()
    val eqState by viewModel.eqState.collectAsStateWithLifecycle()
    val safetyState by viewModel.safetyState.collectAsStateWithLifecycle()
    val sinkTelemetry by viewModel.sinkTelemetry.collectAsStateWithLifecycle()
    val transportState by viewModel.transportState.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanningMedia.collectAsStateWithLifecycle()
    val offlineUsageMb by viewModel.offlineUsageMb.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.toastMessages.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshHardwareLinks()
    }

    val requestAutomotivePermissions = {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionsLauncher.launch(perms)
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importVideoUri(it) }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.importFolderTree(it) }
    }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.loadExternalSubtitle(it) }
    }

    LaunchedEffect(cabinPrefs.orientationLock, isInPipMode) {
        val act = context.findHostActivity() ?: return@LaunchedEffect
        act.requestedOrientation = if (isInPipMode) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            when (cabinPrefs.orientationLock) {
                OrientationLockMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                OrientationLockMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                OrientationLockMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    // Auto-resume from Home-Screen Widget
    LaunchedEffect(widgetResumePending, videosState) {
        if (widgetResumePending && videosState is UiState.Success) {
            val list = (videosState as UiState.Success).data
            if (viewModel.resumeLastFromWidget(list)) {
                navController.navigate(RoutePlayer) { launchSingleTop = true }
            }
            onConsumeWidgetResume()
        }
    }

    val navItems = listOf(
        Triple(RouteDeck, stringResource(R.string.tab_deck), Icons.Filled.VideoLibrary to Icons.Outlined.VideoLibrary),
        Triple(RoutePlayer, stringResource(R.string.tab_player), Icons.Filled.PlayCircle to Icons.Outlined.PlayCircle),
        Triple(RouteAudioEq, stringResource(R.string.tab_audio_eq), Icons.Filled.GraphicEq to Icons.Outlined.GraphicEq),
        Triple(RouteCarLink, stringResource(R.string.tab_car_link), Icons.Filled.DirectionsCar to Icons.Outlined.DirectionsCar)
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 680.dp && !isInPipMode

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { if (!isInPipMode) SnackbarHost(snackbarHostState) },
            contentWindowInsets = if (isInPipMode) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
            bottomBar = {
                if (!useRail && !isInPipMode) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp
                        ) {
                            navItems.forEach { (route, label, icons) ->
                                val selected = currentDest?.hasRoute(route::class) == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(route) {
                                            popUpTo(RouteDeck) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) icons.first else icons.second,
                                            contentDescription = label
                                        )
                                    },
                                    label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.testTag("nav_${label.lowercase().replace(" ", "_")}")
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets(0, 0, 0, 0))
                    ) {
                        navItems.forEach { (route, label, icons) ->
                            val selected = currentDest?.hasRoute(route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(route) {
                                        popUpTo(RouteDeck) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) icons.first else icons.second,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    if (!isInPipMode) {
                        AutoCockpitTopBar(
                            safetyState = safetyState,
                            sinkTelemetry = sinkTelemetry,
                            transportState = transportState,
                            cabinUtility = cabinUtility,
                            onConfirmParked = { SafetyGateManager.confirmVehicleParked() },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        NavHost(
                            navController = navController,
                            startDestination = RouteDeck
                        ) {
                            composable<RouteDeck> {
                                DeckLibraryScreen(
                                    videosState = videosState,
                                    playlistsState = playlistsState,
                                    isScanning = isScanning,
                                    offlineUsageMb = offlineUsageMb,
                                    transportState = transportState,
                                    onPickVideoFile = {
                                        videoPickerLauncher.launch(arrayOf("video/*"))
                                    },
                                    onPickFolder = {
                                        folderPickerLauncher.launch(null)
                                    },
                                    onPlayVideo = { entry, queue ->
                                        viewModel.playVideo(entry, queue)
                                        navController.navigate(RoutePlayer) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onEnqueuePlayNext = { viewModel.enqueuePlayNext(it) },
                                    onEnqueueAddToEnd = { viewModel.enqueueAddToEnd(it) },
                                    onCreatePlaylist = { name, initId ->
                                        viewModel.createPlaylist(name, initId)
                                    },
                                    onToggleVideoInPlaylist = { pl, vidId ->
                                        viewModel.toggleVideoInPlaylist(pl, vidId)
                                    },
                                    onDeletePlaylist = { viewModel.deletePlaylist(it) },
                                    onToggleOfflineCache = { viewModel.toggleOfflineCache(it) },
                                    onDeleteVideo = { viewModel.removeVideo(it) }
                                )
                            }

                            composable<RoutePlayer> {
                                PlayerCockpitScreen(
                                    playerState = playerState,
                                    cues = cues,
                                    eqState = eqState,
                                    presetsState = eqPresetsState,
                                    bookmarksState = bookmarksState,
                                    cabinPrefs = cabinPrefs,
                                    cabinUtility = cabinUtility,
                                    activeAudioRoute = transportState.activeAudioRoute,
                                    safetyState = safetyState,
                                    sinkTelemetry = sinkTelemetry,
                                    isInPipMode = isInPipMode,
                                    onSelectPreset = { preset, all ->
                                        viewModel.selectEqPreset(preset, all)
                                    },
                                    onSaveCustomPreset = { name, all ->
                                        viewModel.saveCustomEqPreset(name, all)
                                    },
                                    onResetFlat = {
                                        viewModel.resetEqToFlat()
                                    },
                                    onUpdateSubtitleStyle = { style ->
                                        viewModel.setSubtitleStyle(style)
                                    },
                                    onPickExternalSubtitle = {
                                        subtitlePickerLauncher.launch(
                                            arrayOf("application/x-subrip", "text/vtt", "text/plain", "*/*")
                                        )
                                    },
                                    onAddBookmark = { videoId, label, posMs ->
                                        viewModel.addBookmark(videoId, label, posMs)
                                    },
                                    onDeleteBookmark = { bookmarkId ->
                                        viewModel.deleteBookmark(bookmarkId)
                                    },
                                    onSetOrientationLock = { mode ->
                                        viewModel.setOrientationLock(mode)
                                    },
                                    onSetAutoPlayNext = { enabled ->
                                        viewModel.setAutoPlayNext(enabled)
                                    },
                                    onSetPhoneRemoteMode = { enabled ->
                                        viewModel.setPhoneRemoteModePreferred(enabled)
                                    },
                                    onStartSleepTimer = { mins ->
                                        viewModel.startSleepTimer(mins)
                                    },
                                    onSetSleepEndOfVideo = { enabled ->
                                        viewModel.setSleepAtEndOfVideo(enabled)
                                    },
                                    onCancelSleepTimer = {
                                        viewModel.cancelSleepTimer()
                                    },
                                    onDismissChargingAlert = {
                                        viewModel.dismissChargingAlert()
                                    },
                                    onEnterPip = onRequestPip,
                                    onOpenDeck = {
                                        navController.navigate(RouteDeck) {
                                            popUpTo(RouteDeck) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                )
                            }

                            composable<RouteAudioEq> {
                                AudioEqScreen(
                                    eqState = eqState,
                                    presetsState = eqPresetsState,
                                    activeAudioRoute = transportState.activeAudioRoute,
                                    onSelectPreset = { preset, all ->
                                        viewModel.selectEqPreset(preset, all)
                                    },
                                    onSaveCustomPreset = { name, all ->
                                        viewModel.saveCustomEqPreset(name, all)
                                    },
                                    onDeleteCustomPreset = { preset ->
                                        viewModel.deleteCustomEqPreset(preset)
                                    },
                                    onResetFlat = {
                                        viewModel.resetEqToFlat()
                                    }
                                )
                            }

                            composable<RouteCarLink> {
                                CarLinkScreen(
                                    settingsState = settingsState,
                                    cabinPrefs = cabinPrefs,
                                    cabinUtility = cabinUtility,
                                    safetyState = safetyState,
                                    sinkTelemetry = sinkTelemetry,
                                    transportState = transportState,
                                    offlineUsageMb = offlineUsageMb,
                                    onRequestPermissions = requestAutomotivePermissions,
                                    onRefreshLinks = { viewModel.refreshHardwareLinks() },
                                    onUpdateSettings = { q, t, ar, c ->
                                        viewModel.updateSettings(q, t, ar, c)
                                    },
                                    onSetAudioDriveMode = { viewModel.setAudioDriveMode(it) },
                                    onSetPhoneRemoteMode = { viewModel.setPhoneRemoteModePreferred(it) },
                                    onSetBatteryGuardThreshold = { viewModel.setBatteryGuardThreshold(it) },
                                    onStartChargingTimer = { viewModel.startChargingTimer(it) },
                                    onCancelChargingTimer = { viewModel.cancelChargingTimer() },
                                    onSignOut = {
                                        signOutUser(context, credentialManager, scope) {
                                            onOfflineSignOut()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

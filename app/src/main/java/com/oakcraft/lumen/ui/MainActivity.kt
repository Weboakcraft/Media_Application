package com.oakcraft.lumen.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.oakcraft.lumen.LumenApp
import com.oakcraft.lumen.blocker.LumenVpnService
import com.oakcraft.lumen.player.MediaLibrary
import com.oakcraft.lumen.player.MediaLibrary.Companion.toMediaItem
import com.oakcraft.lumen.player.PlaybackService
import com.oakcraft.lumen.player.PodcastRepository
import com.oakcraft.lumen.player.PodcastRepository.Companion.streamMediaItem
import com.oakcraft.lumen.player.SleepTimer
import com.oakcraft.lumen.ui.components.MiniPlayer
import com.oakcraft.lumen.ui.screens.LibraryScreen
import com.oakcraft.lumen.ui.screens.NowPlayingSheet
import com.oakcraft.lumen.ui.screens.SettingsScreen
import com.oakcraft.lumen.ui.screens.ShieldScreen
import com.oakcraft.lumen.ui.screens.VideoScreen
import com.oakcraft.lumen.ui.theme.LumenTheme
import kotlinx.coroutines.launch
import androidx.media3.common.Player as Media3Player
import com.oakcraft.lumen.player.PodcastRepository.Companion.toMediaItem as episodeToMediaItem

private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Library("library", "Library", Icons.Rounded.LibraryMusic),
    Video("video", "Video", Icons.Rounded.Videocam),
    Shield("shield", "Shield", Icons.Rounded.Shield),
    Settings("settings", "Settings", Icons.Rounded.Settings)
}

class MainActivity : ComponentActivity() {

    /** True while the video screen is on top; decides whether leaving enters PiP. */
    private var videoScreenActive = false
    private var playbackIsActive = false

    private var hasAudioPermission by mutableStateOf(false)
    private var inPipMode by mutableStateOf(false)

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasAudioPermission = granted }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Playback and shield both still work; the notification is just hidden. */ }

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            LumenVpnService.start(this)
            lifecycleScope.launch { (application as LumenApp).settings.setShieldEnabled(true) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hasAudioPermission = ContextCompat.checkSelfPermission(this, audioPermission()) ==
            PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val app = application as LumenApp
            val dynamicColor by app.settings.dynamicColor.collectAsStateWithLifecycle(
                initialValue = true
            )

            LumenTheme(dynamicColor = dynamicColor) {
                LumenRoot(
                    hasAudioPermission = hasAudioPermission,
                    inPipMode = inPipMode,
                    onRequestAudioPermission = {
                        audioPermissionLauncher.launch(audioPermission())
                    },
                    onRequestShield = ::requestShield,
                    onDisableShield = ::disableShield,
                    onVideoActiveChange = { videoScreenActive = it },
                    onPlaybackActiveChange = { playbackIsActive = it }
                )
            }
        }
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            @Suppress("DEPRECATION")
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun requestShield() {
        val consent: Intent? = VpnService.prepare(this)
        if (consent != null) {
            vpnConsentLauncher.launch(consent)
        } else {
            LumenVpnService.start(this)
            lifecycleScope.launch { (application as LumenApp).settings.setShieldEnabled(true) }
        }
    }

    private fun disableShield() {
        LumenVpnService.stop(this)
        lifecycleScope.launch { (application as LumenApp).settings.setShieldEnabled(false) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoScreenActive && playbackIsActive && supportsPip()) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipMode = isInPictureInPictureMode
    }

    private fun supportsPip(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
}

@Composable
private fun LumenRoot(
    hasAudioPermission: Boolean,
    inPipMode: Boolean,
    onRequestAudioPermission: () -> Unit,
    onRequestShield: () -> Unit,
    onDisableShield: () -> Unit,
    onVideoActiveChange: (Boolean) -> Unit,
    onPlaybackActiveChange: (Boolean) -> Unit
) {
    val navController = rememberNavController()
    val controller = rememberMediaController()
    val playbackState by rememberPlaybackState(controller)
    val sleepRemaining by SleepTimer.remainingMs.collectAsStateWithLifecycle()

    val libraryViewModel: LibraryViewModel = viewModel()
    val shieldViewModel: ShieldViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()

    var showNowPlaying by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(playbackState.isPlaying) {
        onPlaybackActiveChange(playbackState.isPlaying)
    }

    // Surface view-model messages in one place rather than three.
    val libraryMessage by libraryViewModel.message.collectAsStateWithLifecycle()
    val shieldMessage by shieldViewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(libraryMessage) {
        libraryMessage?.let {
            snackbarHostState.showSnackbar(it)
            libraryViewModel.consumeMessage()
        }
    }
    LaunchedEffect(shieldMessage) {
        shieldMessage?.let {
            snackbarHostState.showSnackbar(it)
            shieldViewModel.consumeMessage()
        }
    }

    if (inPipMode) {
        VideoScreen(
            controller = controller,
            isInPipMode = true,
            onVideoActiveChange = {},
            onPlayVideo = {},
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                MiniPlayer(
                    state = playbackState,
                    onExpand = { showNowPlaying = true },
                    onTogglePlay = { controller?.togglePlay() },
                    onNext = { controller?.seekToNextMediaItem() }
                )
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination?.hierarchy?.any {
                                it.route == destination.route
                            } == true,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(destination.icon, contentDescription = null)
                            },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Library.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Destination.Library.route) {
                LibraryScreen(
                    viewModel = libraryViewModel,
                    hasAudioPermission = hasAudioPermission,
                    onRequestAudioPermission = onRequestAudioPermission,
                    onPlayTracks = { tracks, index -> controller?.playTracks(tracks, index) },
                    onPlayEpisodes = { episodes, index ->
                        controller?.playEpisodes(episodes, index)
                    },
                    onPlayStream = { url, label -> controller?.playStream(url, label) }
                )
            }
            composable(Destination.Video.route) {
                VideoScreen(
                    controller = controller,
                    isInPipMode = false,
                    onVideoActiveChange = onVideoActiveChange,
                    onPlayVideo = { url -> controller?.playStream(url, "Video") }
                )
            }
            composable(Destination.Shield.route) {
                ShieldScreen(
                    viewModel = shieldViewModel,
                    onRequestEnable = onRequestShield,
                    onDisable = onDisableShield
                )
            }
            composable(Destination.Settings.route) {
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }

    if (showNowPlaying) {
        NowPlayingSheet(
            state = playbackState,
            sleepRemainingMs = sleepRemaining,
            onDismiss = { showNowPlaying = false },
            onTogglePlay = { controller?.togglePlay() },
            onNext = { controller?.seekToNextMediaItem() },
            onPrevious = { controller?.seekToPreviousMediaItem() },
            onSeek = { position -> controller?.seekTo(position) },
            onSeekBack = { controller?.seekBack() },
            onSeekForward = { controller?.seekForward() },
            onToggleShuffle = {
                controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
            },
            onCycleRepeat = { controller?.cycleRepeatMode() },
            onCycleSpeed = { controller?.cycleSpeed() },
            onSleepTimer = { controller?.cycleSleepTimer(sleepRemaining != null) }
        )
    }
}

// --- Controller helpers -------------------------------------------------------------

private fun MediaController.togglePlay() {
    if (isPlaying) pause() else {
        if (playbackState == Media3Player.STATE_IDLE) prepare()
        play()
    }
}

private fun MediaController.playTracks(tracks: List<MediaLibrary.Track>, index: Int) {
    setMediaItems(tracks.map { it.toMediaItem() }, index, 0L)
    prepare()
    play()
}

private fun MediaController.playEpisodes(
    episodes: List<PodcastRepository.Episode>,
    index: Int
) {
    setMediaItems(episodes.map { it.episodeToMediaItem() }, index, 0L)
    prepare()
    play()
}

private fun MediaController.playStream(url: String, label: String) {
    if (url.isBlank()) return
    setMediaItem(streamMediaItem(url, label))
    prepare()
    play()
}

private fun MediaController.cycleRepeatMode() {
    repeatMode = when (repeatMode) {
        Media3Player.REPEAT_MODE_OFF -> Media3Player.REPEAT_MODE_ALL
        Media3Player.REPEAT_MODE_ALL -> Media3Player.REPEAT_MODE_ONE
        else -> Media3Player.REPEAT_MODE_OFF
    }
}

private val SPEEDS = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

private fun MediaController.cycleSpeed() {
    val current = playbackParameters.speed
    val next = SPEEDS.firstOrNull { it > current + 0.01f } ?: SPEEDS.first()
    setPlaybackSpeed(next)
}

private fun MediaController.cycleSleepTimer(running: Boolean) {
    val command = if (running) {
        SessionCommand(PlaybackService.COMMAND_CANCEL_SLEEP_TIMER, Bundle.EMPTY)
    } else {
        SessionCommand(PlaybackService.COMMAND_SLEEP_TIMER, Bundle.EMPTY)
    }
    val args = Bundle().apply { putLong(PlaybackService.EXTRA_SLEEP_MINUTES, 30L) }
    sendCustomCommand(command, args)
}

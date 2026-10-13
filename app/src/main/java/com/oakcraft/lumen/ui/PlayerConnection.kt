package com.oakcraft.lumen.ui

import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.oakcraft.lumen.player.PlaybackService
import kotlinx.coroutines.delay

/**
 * Binds the UI to the playback service.
 *
 * The controller is the only handle the UI has on the player; everything it shows comes
 * from the session, so the screen is always describing what the service is actually
 * doing rather than keeping its own copy of the state.
 */
@Composable
fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                controller = runCatching { future.get() }.getOrNull()
            },
            MoreExecutors.directExecutor()
        )
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    return controller
}

data class PlaybackUiState(
    val hasItem: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val artworkUri: Uri? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val speed: Float = 1f,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF
) {
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Mirrors the session into Compose state.
 *
 * Discrete changes arrive through the listener; only the playhead is polled, and only
 * while something is playing, so an idle screen costs nothing.
 */
@Composable
fun rememberPlaybackState(controller: MediaController?): State<PlaybackUiState> {
    val state = remember { mutableStateOf(PlaybackUiState()) }

    DisposableEffect(controller) {
        val player = controller ?: return@DisposableEffect onDispose { }

        fun snapshot(): PlaybackUiState {
            val metadata: MediaMetadata = player.mediaMetadata
            return PlaybackUiState(
                hasItem = player.mediaItemCount > 0,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                artworkUri = metadata.artworkUri,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.takeIf { it > 0 } ?: 0,
                hasNext = player.hasNextMediaItem(),
                hasPrevious = player.hasPreviousMediaItem(),
                speed = player.playbackParameters.speed,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode
            )
        }

        state.value = snapshot()

        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state.value = snapshot()
            }
        }
        player.addListener(listener)

        onDispose { player.removeListener(listener) }
    }

    val isPlaying = state.value.isPlaying
    LaunchedEffect(controller, isPlaying) {
        if (controller == null || !isPlaying) return@LaunchedEffect
        while (true) {
            state.value = state.value.copy(
                positionMs = controller.currentPosition.coerceAtLeast(0),
                durationMs = controller.duration.takeIf { it > 0 } ?: 0
            )
            delay(500)
        }
    }

    return state
}

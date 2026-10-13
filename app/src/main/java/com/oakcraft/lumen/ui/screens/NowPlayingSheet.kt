package com.oakcraft.lumen.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.oakcraft.lumen.ui.PlaybackUiState
import com.oakcraft.lumen.ui.components.Artwork
import java.util.Locale

/**
 * The full-height player.
 *
 * The seek bar keeps a local value while the user is dragging, otherwise the position
 * poll would fight the gesture and make the thumb stutter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    state: PlaybackUiState,
    sleepRemainingMs: Long?,
    onDismiss: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSleepTimer: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var scrubPosition by remember { mutableStateOf<Float?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Artwork(uri = state.artworkUri, size = 260.dp, corner = 24.dp)

            Spacer(Modifier.height(24.dp))

            Text(
                text = state.title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = state.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(20.dp))

            Slider(
                value = scrubPosition ?: state.progress,
                onValueChange = { scrubPosition = it },
                onValueChangeFinished = {
                    scrubPosition?.let { fraction ->
                        if (state.durationMs > 0) onSeek((fraction * state.durationMs).toLong())
                    }
                    scrubPosition = null
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(
                        scrubPosition?.let { (it * state.durationMs).toLong() } ?: state.positionMs
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(text = formatTime(state.durationMs), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (state.shuffle) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onPrevious, enabled = state.hasPrevious) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(onClick = onSeekBack) {
                    Icon(Icons.Rounded.Replay10, contentDescription = "Back 10 seconds")
                }

                FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(68.dp)) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Rounded.Pause
                        else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(onClick = onSeekForward) {
                    Icon(Icons.Rounded.Forward30, contentDescription = "Forward 30 seconds")
                }
                IconButton(onClick = onNext, enabled = state.hasNext) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
                }
                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        imageVector = if (state.repeatMode == Player.REPEAT_MODE_ONE)
                            Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "Repeat",
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AssistChip(
                    onClick = onCycleSpeed,
                    label = { Text(String.format(Locale.US, "%.2f×", state.speed)) },
                    leadingIcon = { Icon(Icons.Rounded.Speed, contentDescription = null) }
                )
                AssistChip(
                    onClick = onSleepTimer,
                    label = {
                        Text(
                            sleepRemainingMs?.let { formatTime(it) } ?: "Sleep timer"
                        )
                    },
                    leadingIcon = { Icon(Icons.Rounded.Bedtime, contentDescription = null) }
                )
            }
        }
    }
}

fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

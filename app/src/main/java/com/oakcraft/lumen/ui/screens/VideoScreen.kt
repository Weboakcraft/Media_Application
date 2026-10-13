package com.oakcraft.lumen.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView

/**
 * Video playback with picture-in-picture.
 *
 * The surface is attached to the same session the rest of the app uses, so leaving this
 * screen does not interrupt anything — the audio carries on, and if the user swipes home
 * while a video is playing the activity hands off to a PiP window.
 */
@Composable
fun VideoScreen(
    controller: MediaController?,
    isInPipMode: Boolean,
    onVideoActiveChange: (Boolean) -> Unit,
    onPlayVideo: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var url by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onVideoActiveChange(true)
        onDispose { onVideoActiveChange(false) }
    }

    if (isInPipMode) {
        // In a PiP window there is room for the surface and nothing else.
        PlayerSurface(
            controller = controller,
            useController = false,
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PlayerSurface(
            controller = controller,
            useController = true,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
        )

        Text("Play a video stream", style = MaterialTheme.typography.titleMedium)
        Text(
            "Direct MP4 and HLS addresses both work. Swipe home while it is playing and " +
                "it continues in a floating window.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Address") },
            placeholder = { Text("https://example.com/video.m3u8") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onPlayVideo(url.trim()) },
            enabled = url.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Play") }
    }
}

@Composable
private fun PlayerSurface(
    controller: MediaController?,
    useController: Boolean,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                this.useController = useController
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
        },
        update = { view ->
            view.useController = useController
            // Attaching and detaching the player is what releases the surface when the
            // composable leaves, so a backgrounded video does not hold the decoder.
            view.player = controller
        },
        onRelease = { view -> view.player = null }
    )
}

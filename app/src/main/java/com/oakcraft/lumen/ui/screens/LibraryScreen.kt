package com.oakcraft.lumen.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oakcraft.lumen.player.MediaLibrary
import com.oakcraft.lumen.player.PodcastRepository
import com.oakcraft.lumen.ui.LibraryViewModel
import com.oakcraft.lumen.ui.components.Artwork

private enum class LibraryTab(val label: String) {
    Device("On device"),
    Podcasts("Podcasts"),
    Stream("Stream")
}

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onPlayTracks: (List<MediaLibrary.Track>, Int) -> Unit,
    onPlayEpisodes: (List<PodcastRepository.Episode>, Int) -> Unit,
    onPlayStream: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = LibraryTab.entries

    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, entry ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(entry.label) },
                    icon = {
                        Icon(
                            imageVector = when (entry) {
                                LibraryTab.Device -> Icons.Rounded.LibraryMusic
                                LibraryTab.Podcasts -> Icons.Rounded.Podcasts
                                LibraryTab.Stream -> Icons.Rounded.Stream
                            },
                            contentDescription = null
                        )
                    }
                )
            }
        }

        when (tabs[tab]) {
            LibraryTab.Device -> DeviceTab(
                viewModel = viewModel,
                hasAudioPermission = hasAudioPermission,
                onRequestAudioPermission = onRequestAudioPermission,
                onPlayTracks = onPlayTracks
            )
            LibraryTab.Podcasts -> PodcastTab(viewModel, onPlayEpisodes)
            LibraryTab.Stream -> StreamTab(onPlayStream)
        }
    }
}

@Composable
private fun DeviceTab(
    viewModel: LibraryViewModel,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onPlayTracks: (List<MediaLibrary.Track>, Int) -> Unit
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val loading by viewModel.loadingTracks.collectAsStateWithLifecycle()

    LaunchedEffect(hasAudioPermission) {
        if (hasAudioPermission) viewModel.loadTracks()
    }

    if (!hasAudioPermission) {
        EmptyState(
            title = "Access your audio",
            body = "Lumen reads your audio files to build this list. Nothing is uploaded " +
                "and nothing is copied off your device.",
            actionLabel = "Grant access",
            onAction = onRequestAudioPermission
        )
        return
    }

    if (loading && tracks.isEmpty()) {
        LoadingState()
        return
    }

    if (tracks.isEmpty()) {
        EmptyState(
            title = "No audio found",
            body = "Nothing turned up in your audio library. Add some files, or use the " +
                "Podcasts and Stream tabs instead."
        )
        return
    }

    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayTracks(tracks, index) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Artwork(uri = track.artworkUri, size = 52.dp, corner = 10.dp)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    formatTime(track.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PodcastTab(
    viewModel: LibraryViewModel,
    onPlayEpisodes: (List<PodcastRepository.Episode>, Int) -> Unit
) {
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val loading by viewModel.loadingFeeds.collectAsStateWithLifecycle()
    var feedUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadFeeds() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Add a feed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = feedUrl,
                        onValueChange = { feedUrl = it },
                        label = { Text("RSS address") },
                        placeholder = { Text("https://example.com/feed.xml") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.subscribe(feedUrl)
                            feedUrl = ""
                        },
                        enabled = feedUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Subscribe") }
                }
            }
        }

        if (loading) {
            item { LoadingState() }
        }

        feeds.forEach { feed ->
            item(key = feed.url) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        feed.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.unsubscribe(feed.url) }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Unsubscribe from ${feed.title}")
                    }
                }
            }
            itemsIndexedEpisodes(feed, onPlayEpisodes)
        }

        if (feeds.isEmpty() && !loading) {
            item {
                EmptyState(
                    title = "No subscriptions yet",
                    body = "Paste a podcast RSS address above to start listening."
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedEpisodes(
    feed: PodcastRepository.Feed,
    onPlayEpisodes: (List<PodcastRepository.Episode>, Int) -> Unit
) {
    itemsIndexed(feed.episodes.take(40)) { index, episode ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPlayEpisodes(feed.episodes, index) }
                .padding(vertical = 6.dp)
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (episode.durationMs > 0) {
                    Text(
                        formatTime(episode.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamTab(onPlayStream: (String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Play a stream", style = MaterialTheme.typography.titleMedium)
        Text(
            "Any direct audio or HLS address works here — an internet radio station, " +
                "a file you host, an open archive recording.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Address") },
            placeholder = { Text("https://stream.example.com/live.m3u8") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onPlayStream(url.trim(), label.trim()) },
            enabled = url.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Play") }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

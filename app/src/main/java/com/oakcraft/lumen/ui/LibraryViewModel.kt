package com.oakcraft.lumen.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oakcraft.lumen.LumenApp
import com.oakcraft.lumen.player.MediaLibrary
import com.oakcraft.lumen.player.PodcastRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LumenApp

    private val _tracks = MutableStateFlow<List<MediaLibrary.Track>>(emptyList())
    val tracks: StateFlow<List<MediaLibrary.Track>> = _tracks.asStateFlow()

    private val _feeds = MutableStateFlow<List<PodcastRepository.Feed>>(emptyList())
    val feeds: StateFlow<List<PodcastRepository.Feed>> = _feeds.asStateFlow()

    private val _loadingTracks = MutableStateFlow(false)
    val loadingTracks: StateFlow<Boolean> = _loadingTracks.asStateFlow()

    private val _loadingFeeds = MutableStateFlow(false)
    val loadingFeeds: StateFlow<Boolean> = _loadingFeeds.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Called once the audio permission is in hand; harmless to call again. */
    fun loadTracks() {
        if (_loadingTracks.value) return
        viewModelScope.launch {
            _loadingTracks.value = true
            try {
                _tracks.value = app.mediaLibrary.queryTracks()
            } catch (e: Exception) {
                _message.value = "Could not read your audio library: ${e.message}"
            } finally {
                _loadingTracks.value = false
            }
        }
    }

    fun loadFeeds() {
        if (_loadingFeeds.value) return
        viewModelScope.launch {
            _loadingFeeds.value = true
            try {
                val urls = app.podcastRepository.subscriptions()
                val loaded = urls.mapNotNull { url ->
                    app.podcastRepository.loadFeed(url).getOrNull()
                }
                _feeds.value = loaded
                if (loaded.size < urls.size) {
                    _message.value = "${urls.size - loaded.size} feed(s) could not be refreshed."
                }
            } finally {
                _loadingFeeds.value = false
            }
        }
    }

    fun subscribe(url: String) = viewModelScope.launch {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            _message.value = "Enter a feed address starting with http:// or https://"
            return@launch
        }
        // Load first, so a typo is caught before it is saved.
        val result = app.podcastRepository.loadFeed(trimmed)
        result.onSuccess { feed ->
            app.podcastRepository.subscribe(trimmed)
            _feeds.value = (_feeds.value.filterNot { it.url == feed.url }) + feed
            _message.value = "Subscribed to ${feed.title}."
        }.onFailure {
            _message.value = "That feed could not be read: ${it.message}"
        }
    }

    fun unsubscribe(url: String) = viewModelScope.launch {
        app.podcastRepository.unsubscribe(url)
        _feeds.value = _feeds.value.filterNot { it.url == url }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

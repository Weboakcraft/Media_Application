package com.oakcraft.lumen.player

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Subscriptions to open RSS feeds, plus any direct stream URL the user adds.
 *
 * These are ordinary podcast feeds — the publisher distributes the audio file and the
 * feed points at it — so playing them in a third-party player is exactly what they are
 * published for.
 */
class PodcastRepository(private val context: Context) {

    data class Feed(
        val url: String,
        val title: String,
        val imageUrl: String?,
        val episodes: List<Episode>
    )

    data class Episode(
        val guid: String,
        val title: String,
        val author: String,
        val audioUrl: String,
        val imageUrl: String?,
        val durationMs: Long
    )

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val subscriptionsFile: File get() = File(context.filesDir, "subscriptions.txt")

    suspend fun subscriptions(): List<String> = withContext(Dispatchers.IO) {
        if (!subscriptionsFile.exists()) emptyList()
        else subscriptionsFile.readLines().map(String::trim).filter { it.isNotEmpty() }.distinct()
    }

    suspend fun subscribe(url: String) = withContext(Dispatchers.IO) {
        val normalised = url.trim()
        if (normalised.isEmpty()) return@withContext
        val current = subscriptions().toMutableList()
        if (current.none { it.equals(normalised, ignoreCase = true) }) {
            current += normalised
            subscriptionsFile.writeText(current.joinToString("\n"))
        }
    }

    suspend fun unsubscribe(url: String) = withContext(Dispatchers.IO) {
        val remaining = subscriptions().filterNot { it.equals(url.trim(), ignoreCase = true) }
        subscriptionsFile.writeText(remaining.joinToString("\n"))
    }

    suspend fun loadFeed(url: String): Result<Feed> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("Accept", "application/rss+xml, */*").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty feed")
                parseFeed(url, body.byteStream())
            }
        }
    }

    /**
     * A tolerant RSS reader. Feeds in the wild are inconsistent, so anything that cannot
     * be understood is skipped rather than failing the whole feed.
     */
    private fun parseFeed(feedUrl: String, stream: InputStream): Feed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        var feedTitle = ""
        var feedImage: String? = null
        var inItem = false
        var sawChannelTitle = false

        var title = ""
        var author = ""
        var audioUrl = ""
        var guid = ""
        var itemImage: String? = null
        var durationMs = 0L

        val episodes = mutableListOf<Episode>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "item", "entry" -> {
                        inItem = true
                        title = ""; author = ""; audioUrl = ""; guid = ""
                        itemImage = null; durationMs = 0L
                    }
                    "title" -> {
                        val text = parser.nextText().trim()
                        if (inItem) title = text
                        else if (!sawChannelTitle) {
                            feedTitle = text
                            sawChannelTitle = true
                        }
                    }
                    "enclosure" -> {
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val href = parser.getAttributeValue(null, "url").orEmpty()
                        if (href.isNotEmpty() && (type.startsWith("audio") || type.isEmpty())) {
                            audioUrl = href
                        }
                    }
                    "guid", "id" -> if (inItem) guid = parser.nextText().trim()
                    "itunes:author", "author", "dc:creator" ->
                        if (inItem) author = parser.nextText().trim()
                    "itunes:duration" -> if (inItem) durationMs = parseDuration(parser.nextText())
                    "itunes:image" -> {
                        val href = parser.getAttributeValue(null, "href")
                        if (inItem) itemImage = href else if (feedImage == null) feedImage = href
                    }
                    "url" -> if (!inItem && feedImage == null) feedImage = parser.nextText().trim()
                }

                XmlPullParser.END_TAG -> if (parser.name.lowercase() in setOf("item", "entry")) {
                    inItem = false
                    if (audioUrl.isNotEmpty() && title.isNotEmpty()) {
                        episodes += Episode(
                            guid = guid.ifEmpty { audioUrl },
                            title = title,
                            author = author.ifEmpty { feedTitle },
                            audioUrl = audioUrl,
                            imageUrl = itemImage ?: feedImage,
                            durationMs = durationMs
                        )
                    }
                }
            }
            event = parser.next()
        }

        return Feed(
            url = feedUrl,
            title = feedTitle.ifEmpty { feedUrl },
            imageUrl = feedImage,
            episodes = episodes
        )
    }

    /** Accepts `1:02:03`, `12:34` and plain seconds, which is what feeds actually use. */
    private fun parseDuration(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0L
        return try {
            val parts = text.split(':').map { it.trim().toLong() }
            when (parts.size) {
                1 -> parts[0] * 1000
                2 -> (parts[0] * 60 + parts[1]) * 1000
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                else -> 0L
            }
        } catch (e: NumberFormatException) {
            0L
        }
    }

    companion object {
        fun Episode.toMediaItem(): MediaItem = MediaItem.Builder()
            .setMediaId(guid)
            .setUri(audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(author)
                    .setArtworkUri(imageUrl?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

        /** Builds an item for a stream URL the user typed in by hand. */
        fun streamMediaItem(url: String, label: String): MediaItem = MediaItem.Builder()
            .setMediaId(url)
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(label.ifBlank { url })
                    .setArtist("Stream")
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
}

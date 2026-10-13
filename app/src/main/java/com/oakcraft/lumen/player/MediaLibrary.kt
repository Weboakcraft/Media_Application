package com.oakcraft.lumen.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the device's own audio files through MediaStore.
 *
 * Nothing is copied, indexed into a private database, or uploaded; the library is a
 * read-through view of what the system already knows about.
 */
class MediaLibrary(private val context: Context) {

    data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val uri: Uri,
        val artworkUri: Uri?
    )

    suspend fun queryTracks(limit: Int = 2000): List<Track> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )

        // Ringtones and notification blips are not something anyone wants in a library.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR " +
            "${MediaStore.Audio.Media.IS_PODCAST} != 0 OR " +
            "${MediaStore.Audio.Media.IS_AUDIOBOOK} != 0"

        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val results = ArrayList<Track>()
        context.contentResolver.query(collection, projection, selection, null, sortOrder)
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext() && results.size < limit) {
                    val id = cursor.getLong(idColumn)
                    val albumId = cursor.getLong(albumIdColumn)
                    results += Track(
                        id = id,
                        title = cursor.getString(titleColumn) ?: "Unknown title",
                        artist = cursor.getString(artistColumn) ?: "Unknown artist",
                        album = cursor.getString(albumColumn) ?: "",
                        durationMs = cursor.getLong(durationColumn),
                        uri = ContentUris.withAppendedId(collection, id),
                        artworkUri = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)
                    )
                }
            }
        results
    }

    companion object {
        private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

        fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
}

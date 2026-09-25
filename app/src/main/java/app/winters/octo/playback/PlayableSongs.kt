package app.winters.octo.playback

import android.content.Context
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.SourceTrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Turns library song ids into songs the player can play. Each song plays
// from one of its copies, chosen now by the settings and by what can play:
// the phone's file, or a stream from the server.
@Singleton
class PlayableSongs @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: CatalogDao,
    private val sources: SourceDao,
    private val streams: Streams,
) {
    // In the order asked for, dropping any the library no longer has.
    suspend fun items(ids: List<String>): List<MediaItem> {
        val tracks = catalog.tracksByIds(ids)
        val copies = tracks.map { it.id }.distinct().chunked(900)
            .flatMap { sources.copiesOf(it) }
            .groupBy { it.mergedId }
        val onServer = copies.values.filter { group -> group.any { it.isServerCopy } }
        // Only ask about the server when some song is on one.
        val reachable = onServer.isNotEmpty() && streams.online() && streams.client() != null
        // Phone files that could be swapped for a stream, checked in case one is gone.
        val missing = if (reachable) missingPhoneFiles(onServer.flatten().filterNot { it.isServerCopy }) else emptySet()
        val prefs = streams.prefs()
        return tracks.map { track ->
            val copy = chooseCopy(copies[track.id].orEmpty(), prefs.copies, reachable) { it.uri in missing }
            when {
                copy == null -> track.toMediaItem()
                copy.isServerCopy -> track.toMediaItem(streams.uriFor(copy), streams.mimeTypeFor(copy, prefs))
                else -> track.toMediaItem(copy.uri, copy.mimeType)
            }
        }
    }

    // The addresses of the phone files among these that are gone, asked of
    // the phone's media store in one go.
    private suspend fun missingPhoneFiles(copies: List<SourceTrackEntity>): Set<String> = withContext(Dispatchers.IO) {
        val uris = copies.mapNotNull { copy ->
            val uri = copy.uri ?: return@mapNotNull null
            uri.toUri().lastPathSegment?.toLongOrNull()?.let { it to uri }
        }.toMap()
        val present = HashSet<Long>()
        try {
            uris.keys.chunked(900).forEach { chunk ->
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media._ID} IN (${chunk.joinToString(",")})",
                    null,
                    null,
                )?.use { cursor -> while (cursor.moveToNext()) present += cursor.getLong(0) }
            }
        } catch (e: SecurityException) {
            // Without access to the phone's music, none of its files can play.
        }
        uris.filterKeys { it !in present }.values.toSet()
    }
}

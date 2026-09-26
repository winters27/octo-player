package app.winters.octo.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

// The free, open lyrics library used when nothing else has a song's lyrics.
// It needs no key; the shared client already names the app in every request.
private const val LIBRARY_ADDRESS = "https://lrclib.net"

// What the lyrics library answers for one song.
@Serializable
internal data class LibrarySong(
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

// Looks a song up in the online lyrics library by its title, artist, album
// and length, which is all that is sent.
class OnlineLyrics(private val http: OkHttpClient, private val base: HttpUrl) {
    @Inject constructor(http: OkHttpClient) : this(http, LIBRARY_ADDRESS.toHttpUrl())

    // The song's lyrics, or null when the library does not have them.
    // Throws IOException when the library cannot be reached.
    suspend fun find(title: String, artist: String, album: String, durationMs: Long): Lyrics? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(lookupUrl(base, title, artist, album, durationMs)).build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("Lyrics lookup failed: HTTP ${response.code}")
                else -> libraryLyrics(response.body.string())
            }
        }
    }
}

// The lookup for one song. The library matches the length to within a
// couple of seconds, so it is sent in whole seconds; an unknown album or
// length is left out.
internal fun lookupUrl(base: HttpUrl, title: String, artist: String, album: String, durationMs: Long): HttpUrl =
    base.newBuilder()
        .addPathSegments("api/get")
        .addQueryParameter("track_name", title)
        .addQueryParameter("artist_name", artist)
        .apply {
            if (album.isNotBlank()) addQueryParameter("album_name", album)
            if (durationMs > 0) addQueryParameter("duration", "${Math.round(durationMs / 1000.0)}")
        }
        .build()

// The library's answer as lyrics: synced when it has them, plain if not,
// and an instrumental marked as one.
internal fun libraryLyrics(body: String): Lyrics? {
    val song = runCatching { json.decodeFromString(LibrarySong.serializer(), body) }.getOrNull() ?: return null
    if (song.instrumental) return Lyrics(synced = false, lines = emptyList(), source = LyricsSource.Online, instrumental = true)
    return song.syncedLyrics?.let { parseLyricsText(it, LyricsSource.Online) }?.takeIf { it.synced }
        ?: song.plainLyrics?.let { parseLyricsText(it, LyricsSource.Online) }
}

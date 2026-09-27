package app.winters.octo.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import app.winters.octo.catalog.matchKey
import app.winters.octo.discovery.sameArtist
import app.winters.octo.discovery.versionOf
import kotlin.math.abs

// The free, open lyrics library used when nothing else has a song's lyrics.
// It needs no key; the shared client already names the app in every request.
private const val LIBRARY_ADDRESS = "https://lrclib.net"

// What the lyrics library answers for one song.
@Serializable
internal data class LibrarySong(
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
    // Seconds; only search answers carry it.
    val duration: Double? = null,
    val albumName: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
)

// How far apart two lengths may be and still be the same recording.
private const val SAME_LENGTH_S = 3.0

private val json = Json { ignoreUnknownKeys = true }

// Looks a song up in the online lyrics library by its title, artist, album
// and length, which is all that is sent.
class OnlineLyrics(private val http: OkHttpClient, private val base: HttpUrl) {
    @Inject constructor(http: OkHttpClient) : this(http, LIBRARY_ADDRESS.toHttpUrl())

    // The song's lyrics, or null when the library does not have them.
    // Throws IOException when the library cannot be reached.
    // The exact lookup comes first. When it finds nothing timed, the
    // library's search is asked too: it often holds a timed copy of the same
    // recording under a slightly different album or title.
    suspend fun find(title: String, artist: String, album: String, durationMs: Long): Lyrics? = withContext(Dispatchers.IO) {
        val exact = fetch(lookupUrl(base, title, artist, album, durationMs))?.let(::libraryLyrics)
        if (exact != null && (exact.synced || exact.instrumental)) return@withContext exact
        val found = runCatching { fetch(searchUrl(base, title, artist)) }.getOrNull()
        found?.let { bestSearchMatch(it, title, artist, album, durationMs) } ?: exact
    }

    // The answer's body, or null when the library has nothing for it.
    private fun fetch(url: HttpUrl): String? {
        val request = Request.Builder().url(url).build()
        return http.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("Lyrics lookup failed: HTTP ${response.code}")
                else -> response.body.string()
            }
        }
    }
}

// A search by title and artist, which returns every copy the library holds.
internal fun searchUrl(base: HttpUrl, title: String, artist: String): HttpUrl =
    base.newBuilder()
        .addPathSegments("api/search")
        .addQueryParameter("track_name", title)
        .addQueryParameter("artist_name", artist)
        .build()

// The timed copy in a search answer that is the same song: the same title
// (ignoring case, punctuation and bracketed extras, but not the kind of
// recording, so a remix never stands in for the original), the same
// artist, and a length within a few seconds when the song's is known, the
// same album preferred. The search is loose and returns other songs by the
// artist too; a length alone once let one of those stand in. Null when no
// timed copy of this song is there.
internal fun bestSearchMatch(body: String, title: String, artist: String, album: String, durationMs: Long): Lyrics? {
    val songs = runCatching { json.decodeFromString(ListSerializer(LibrarySong.serializer()), body) }.getOrNull() ?: return null
    val seconds = durationMs / 1000.0
    return songs
        .filter { !it.syncedLyrics.isNullOrBlank() }
        .filter { song ->
            val name = song.trackName.orEmpty()
            matchKey(name).isNotEmpty() && matchKey(name) == matchKey(title) && versionOf(name) == versionOf(title) &&
                sameArtist(artist, song.artistName.orEmpty())
        }
        .filter { durationMs <= 0 || (it.duration != null && abs(it.duration - seconds) <= SAME_LENGTH_S) }
        .sortedWith(
            compareBy<LibrarySong> { !it.albumName.equals(album, ignoreCase = true) }
                .thenBy { if (durationMs > 0 && it.duration != null) abs(it.duration - seconds) else 0.0 },
        )
        .firstNotNullOfOrNull { song -> song.syncedLyrics?.let { parseLyricsText(it, LyricsSource.Online) }?.takeIf { it.synced } }
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

package app.winters.octo.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.SongMatchOptions
import app.winters.octo.catalog.SongQuery
import app.winters.octo.catalog.SongVerdict
import kotlin.math.abs
import kotlin.math.roundToLong

// The free, open lyrics library used when nothing else has a song's lyrics.
// It needs no key; the shared client already names the app in every request.
private const val LIBRARY_ADDRESS = "https://lrclib.net"

// What the lyrics library answers for one song.
@Serializable
internal data class LibrarySong(
    // The library's number for this copy.
    val id: Long? = null,
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
    // The app provides it with this constructor (LyricsModule in its di package).
    constructor(http: OkHttpClient) : this(http, LIBRARY_ADDRESS.toHttpUrl())

    // The song's lyrics, or null when the library does not have them.
    // Throws IOException when the library cannot be reached.
    // The exact lookup comes first. When it finds nothing timed, the
    // library's search is asked too: it often holds a timed copy of the same
    // recording under a slightly different album or title. The search is
    // asked with each of the song's search queries in turn, until one answer
    // holds a timed copy. A search that cannot be reached still leaves plain
    // lyrics found; with none, it is a failure, never "the library has none".
    suspend fun find(title: String, artist: String, album: String, durationMs: Long): Lyrics? = withContext(Dispatchers.IO) {
        val exact = fetch(lookupUrl(base, title, artist, album, durationMs))?.let(::libraryLyrics)
        if (exact != null && (exact.synced || exact.instrumental)) return@withContext exact
        for (query in lyricsSearches(title, artist)) {
            val found = try {
                fetch(searchUrl(base, query.title, query.artist))
            } catch (e: IOException) {
                if (exact == null) throw e
                break
            } ?: continue
            bestSearchMatch(found, title, artist, album, durationMs)?.let { return@withContext it }
        }
        exact
    }

    // The library's own match for the song, or null when it has none.
    // Throws IOException when the library cannot be reached.
    suspend fun exact(title: String, artist: String, album: String, durationMs: Long): OnlineCopy? = withContext(Dispatchers.IO) {
        fetch(lookupUrl(base, title, artist, album, durationMs))?.let(::librarySong)?.toCopy()
    }

    // Every copy in the library's search with the song's title and artist,
    // for the listener to choose from. Looser than `find`: live takes and
    // remixes stay in the list, named, since the listener picks. The search
    // queries are asked in turn until one finds copies; only the first
    // request's failure is thrown.
    suspend fun copiesOf(title: String, artist: String, album: String, durationMs: Long): List<OnlineCopy> =
        withContext(Dispatchers.IO) {
            for ((index, query) in lyricsSearches(title, artist).withIndex()) {
                val url = searchUrl(base, query.title, query.artist)
                val body = if (index == 0) fetch(url) else runCatching { fetch(url) }.getOrElse { break }
                val copies = songCopies(body ?: continue).filter { sameTitleAndArtist(it, title, artist) }
                if (copies.isNotEmpty()) return@withContext rankCopies(copies, album, durationMs)
            }
            emptyList()
        }

    // Whatever the library finds for words the listener typed, as it
    // answers them: for a song whose tags are wrong, nothing is filtered.
    suspend fun search(query: String): List<OnlineCopy> = withContext(Dispatchers.IO) {
        fetch(freeSearchUrl(base, query))?.let(::songCopies).orEmpty()
    }

    // One copy by its number, for lyrics the listener picked before; null
    // when the library no longer has it.
    suspend fun byId(id: Long): Lyrics? = withContext(Dispatchers.IO) {
        fetch(recordUrl(base, id))?.let(::libraryLyrics)
    }

    // The answer's body, or null when the library has nothing for it. A
    // lookup no longer wanted (the song was skipped) stops its request.
    private suspend fun fetch(url: HttpUrl): String? {
        currentCoroutineContext().ensureActive()
        val call = http.newCall(Request.Builder().url(url).build())
        val stop = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            return read(call)
        } finally {
            stop?.dispose()
        }
    }

    private fun read(call: Call): String? {
        return call.execute().use { response ->
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

// How many searches a song may cost when the first finds nothing.
private const val MAX_SEARCHES = 3

// Lyrics compare lengths on their own, since not every copy knows one. A
// clean edit counts as the song here: its lyrics are the same words at the
// same times, with a few bleeped. Downloads and the library stay strict.
private val LyricsTitles = SongMatchOptions(lengthToleranceSeconds = null, alsoNeutral = setOf("clean"))

// Whether a copy is the song asked for, as lyrics read it: the same title
// whole (case, accents, punctuation, stylized characters, guests and upload
// noise ignored), the same kind of recording, and the same artist.
internal fun sameLyricsSong(wantTitle: String, wantArtist: String, gotTitle: String?, gotArtist: String?): Boolean =
    !gotTitle.isNullOrBlank() && wantArtist.isNotBlank() && SongIdentity.same(wantTitle, wantArtist, gotTitle, gotArtist, LyricsTitles).isSame

// The searches to ask for a song, from SongIdentity's query variants: as
// tagged, cleaned, with stylized characters read as letters, and by the
// primary artist alone. Each keeps an artist, since a search by title alone
// returns every song of that name, and there are at most three. Whatever
// they find is still held to the song as asked.
internal fun lyricsSearches(title: String, artist: String): List<SongQuery> =
    SongIdentity.queryVariants(title, artist).filter { it.artist.isNotEmpty() }.take(MAX_SEARCHES)
        .ifEmpty { listOf(SongQuery(title, artist)) }

// The timed copy in a search answer that is the same song (see
// sameLyricsSong: a remix never stands in for the original), with a length
// within a few seconds when the song's is known, the same album preferred.
// The search is loose and returns other songs by the artist too; a length
// alone once let one of those stand in. Null when no timed copy of this
// song is there.
internal fun bestSearchMatch(body: String, title: String, artist: String, album: String, durationMs: Long): Lyrics? {
    val songs = runCatching { json.decodeFromString(ListSerializer(LibrarySong.serializer()), body) }.getOrNull() ?: return null
    val seconds = durationMs / 1000.0
    return songs
        .filter { !it.syncedLyrics.isNullOrBlank() }
        .filter { song -> sameLyricsSong(title, artist, song.trackName, song.artistName) }
        .filter { durationMs <= 0 || (it.duration != null && abs(it.duration - seconds) <= SAME_LENGTH_S) }
        .sortedWith(
            compareBy<LibrarySong> { !it.albumName.equals(album, ignoreCase = true) }
                .thenBy { if (durationMs > 0 && it.duration != null) abs(it.duration - seconds) else 0.0 },
        )
        .firstNotNullOfOrNull { song ->
            song.syncedLyrics?.let { parseLyricsText(it, LyricsSource.Online) }?.takeIf { it.synced }?.copy(onlineId = song.id)
        }
}

// A search for words the listener typed, matched against any field.
internal fun freeSearchUrl(base: HttpUrl, query: String): HttpUrl =
    base.newBuilder()
        .addPathSegments("api/search")
        .addQueryParameter("q", query.trim())
        .build()

// One copy by the library's number for it.
internal fun recordUrl(base: HttpUrl, id: Long): HttpUrl =
    base.newBuilder()
        .addPathSegments("api/get")
        .addPathSegment(id.toString())
        .build()

// One copy of a song's lyrics in the online library: its number, what the
// library calls it, and its lyrics.
data class OnlineCopy(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val lyrics: Lyrics,
)

// The same song as the one playing, in any version: the same title and the
// same artist, as lyrics read them. A live take or a remix stays, since the
// listener picks.
internal fun sameTitleAndArtist(copy: OnlineCopy, title: String, artist: String): Boolean =
    copy.title.isNotBlank() && artist.isNotBlank() &&
        SongIdentity.same(title, artist, copy.title, copy.artist, LyricsTitles).verdict != SongVerdict.Different

// Copies in the order they are offered: timed ones first, then the same
// album, then the nearest length.
internal fun rankCopies(copies: List<OnlineCopy>, album: String, durationMs: Long): List<OnlineCopy> =
    copies.sortedWith(
        compareBy<OnlineCopy> { !it.lyrics.synced }
            .thenBy { !(album.isNotBlank() && it.album.equals(album, ignoreCase = true)) }
            .thenBy { if (durationMs > 0 && it.durationMs > 0) abs(it.durationMs - durationMs) else Long.MAX_VALUE },
    )

// Every copy in a search answer that has lyrics.
internal fun songCopies(body: String): List<OnlineCopy> =
    runCatching { json.decodeFromString(ListSerializer(LibrarySong.serializer()), body) }.getOrNull().orEmpty().mapNotNull { it.toCopy() }

private fun librarySong(body: String): LibrarySong? =
    runCatching { json.decodeFromString(LibrarySong.serializer(), body) }.getOrNull()

// A copy with its lyrics, or null when it has no number or no words.
internal fun LibrarySong.toCopy(): OnlineCopy? {
    val number = id ?: return null
    val words = asLyrics() ?: return null
    val length = duration?.let { (it * 1000).roundToLong() } ?: 0L
    return OnlineCopy(number, trackName.orEmpty(), artistName.orEmpty(), albumName.orEmpty(), length, words)
}

// Synced lyrics when the copy has them, plain if not, and an instrumental
// marked as one.
private fun LibrarySong.asLyrics(): Lyrics? {
    if (instrumental) return Lyrics(synced = false, lines = emptyList(), source = LyricsSource.Online, instrumental = true, onlineId = id)
    val words = syncedLyrics?.let { parseLyricsText(it, LyricsSource.Online) }?.takeIf { it.synced }
        ?: plainLyrics?.let { parseLyricsText(it, LyricsSource.Online) }
    return words?.copy(onlineId = id)
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
internal fun libraryLyrics(body: String): Lyrics? = librarySong(body)?.asLyrics()

package app.winters.octo.lyrics

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.winters.octo.catalog.CatalogDao
import app.winters.octo.catalog.FIND_PREFIX
import app.winters.octo.catalog.OnlineDao
import app.winters.octo.catalog.SourceDao
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.isFind
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.device.DEVICE
import app.winters.octo.device.TagReader
import app.winters.octo.player.PlayerSettings
import app.winters.octo.server.serverSourceId
import app.winters.octo.subsonic.SubsonicException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// How long to wait for the saved sign-in to come back as the app starts.
private const val SESSION_WAIT_MS = 3_000L

// What is known about the song on now, for the lookups that go by name.
data class LyricsSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

// One place lyrics can come from, tried in order.
class LyricsStep(val source: LyricsSource, val fetch: suspend () -> Lyrics?)

// What a search found, and whether every source answered. An answer with a
// source that could not be reached is not saved, so it is asked again.
data class LyricsSearch(val lyrics: Lyrics?, val complete: Boolean, val askedOnline: Boolean)

// Asks each source in order. The first synced lyrics win at once; plain
// lyrics are kept while the rest are asked for synced ones, and win if none
// have them. A source that fails is skipped.
suspend fun searchInOrder(steps: List<LyricsStep>): LyricsSearch {
    var plain: Lyrics? = null
    var complete = true
    var askedOnline = false
    for (step in steps) {
        if (step.source == LyricsSource.Online) askedOnline = true
        val found = try {
            step.fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            complete = false
            null
        }
        if (found == null || found.isEmpty) continue
        if (found.synced) return LyricsSearch(found, complete, askedOnline)
        if (plain == null) plain = found
    }
    return LyricsSearch(plain, complete, askedOnline)
}

// Finds a song's lyrics: from the server, then the song file, then an .lrc
// file beside it, then (when allowed) the online library. Answers are kept
// per song, in memory and in the cache folder.
@Singleton
class LyricsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val sessions: SessionRepository,
    private val sources: SourceDao,
    private val catalog: CatalogDao,
    private val finds: OnlineDao,
    private val tags: TagReader,
    private val files: LyricsFiles,
    private val online: OnlineLyrics,
    private val settings: PlayerSettings,
) {
    private val cache = LyricsCache(File(context.cacheDir, "lyrics"))
    private val recent = object : LinkedHashMap<String, CachedLyrics>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>) = size > 32
    }

    // The song's lyrics, or null when none were found.
    suspend fun lyricsFor(song: LyricsSong): Lyrics? {
        val onlineAllowed = settings.prefs.first().lyricsOnline
        val now = System.currentTimeMillis()
        val known = synchronized(recent) { recent[song.id] } ?: withContext(Dispatchers.IO) { cache.read(song.id) }
        if (known != null && known.stillGood(now, onlineAllowed)) {
            synchronized(recent) { recent[song.id] = known }
            return known.lyrics
        }

        val facts = described(song)
        val copies = if (isFind(song.id)) emptyList() else sources.copies(song.id)
        val phone = copies.firstOrNull { it.sourceId == DEVICE && !it.uri.isNullOrEmpty() }?.uri?.toUri()
        val steps = buildList {
            add(LyricsStep(LyricsSource.Server) { fromServer(facts, copies) })
            if (phone != null) {
                add(LyricsStep(LyricsSource.SongFile) { fromSongFile(phone) })
                add(LyricsStep(LyricsSource.LyricsFile) { fromLyricsFile(phone) })
            }
            if (onlineAllowed && facts.title.isNotBlank() && facts.artist.isNotBlank()) {
                add(LyricsStep(LyricsSource.Online) { online.find(facts.title, facts.artist, facts.album, facts.durationMs) })
            }
        }
        val search = searchInOrder(steps)
        val entry = CachedLyrics(now, search.lyrics, search.askedOnline)
        // A "none" from a search that could not reach a source is not kept
        // at all, so opening the lyrics again tries again.
        if (search.complete || search.lyrics != null) synchronized(recent) { recent[song.id] = entry }
        if (search.complete) withContext(Dispatchers.IO) { cache.write(song.id, entry) }
        return search.lyrics
    }

    private suspend fun fromSongFile(file: Uri): Lyrics? = withContext(Dispatchers.IO) {
        tags.allTags(file)?.let(::songFileLyrics)?.let { parseLyricsText(it, LyricsSource.SongFile) }
    }

    private suspend fun fromLyricsFile(file: Uri): Lyrics? = withContext(Dispatchers.IO) {
        files.beside(file)?.let { parseLyricsText(it, LyricsSource.LyricsFile) }
    }

    // The library's own facts for the song where it has them, since what the
    // player shows can be shortened.
    private suspend fun described(song: LyricsSong): LyricsSong {
        val known = when {
            isFind(song.id) -> finds.song(song.id)?.let { LyricsSong(song.id, it.title, it.artist, it.album, it.durationMs) }
            else -> catalog.track(song.id)?.let { LyricsSong(song.id, it.title, it.artist, it.album, it.durationMs) }
        } ?: return song
        return known.copy(
            title = known.title.ifBlank { song.title },
            artist = known.artist.ifBlank { song.artist },
            album = known.album.ifBlank { song.album },
            durationMs = known.durationMs.takeIf { it > 0 } ?: song.durationMs,
        )
    }

    // The server's lyrics for the song, when it is on the signed-in server.
    // A server with the songLyrics extension is asked by song id (version 2
    // adds word timings); an older one by artist and title.
    private suspend fun fromServer(song: LyricsSong, copies: List<SourceTrackEntity>): Lyrics? {
        val session = session() ?: return null
        val client = session.client
        val sourceId = serverSourceId(client.baseUrl)
        val copy = copies.firstOrNull { it.sourceId == sourceId }
        val serverId = if (isFind(song.id)) song.id.removePrefix(FIND_PREFIX) else copy?.nativeId ?: return null
        val versions = session.extensions
            .filter { it.startsWith("songLyrics:") }
            .mapNotNull { it.substringAfter(':').toIntOrNull() }
        return try {
            if (versions.isNotEmpty()) {
                serverLyrics(client.lyricsBySongId(serverId, enhanced = versions.max() >= 2), Locale.getDefault().language)
            } else {
                client.lyrics(copy?.artist ?: song.artist, copy?.title ?: song.title)
                    ?.let { parseLyricsText(it, LyricsSource.Server) }
            }
        } catch (_: SubsonicException.NotFound) {
            null
        } catch (e: SubsonicException.Unreachable) {
            throw IOException("Server unreachable", e)
        }
    }

    private suspend fun session(): Session? {
        val state = withTimeoutOrNull(SESSION_WAIT_MS) { sessions.state.first { it !is SessionState.Loading } }
        return (state as? SessionState.SignedIn)?.session
    }
}

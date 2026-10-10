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
import app.winters.octo.playback.RealLengths
import app.winters.octo.player.PlayerSettings
import app.winters.octo.server.serverSourceId
import app.winters.octo.subsonic.LYRICS_AUTO
import app.winters.octo.subsonic.LYRICS_NONE
import app.winters.octo.subsonic.LyricsCandidates
import app.winters.octo.subsonic.OCTO_LYRICS
import app.winters.octo.subsonic.SubsonicException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// How long to wait for the saved sign-in to come back as the app starts.
private const val SESSION_WAIT_MS = 3_000L

// How long what the server said about keeping lyrics choices is believed
// before it is asked again.
private const val RECHECK_CHOICES_MS = 30 * 60 * 1000L

// What is known about the song on now, for the lookups that go by name.
data class LyricsSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

// One place lyrics can come from, tried in order. What a `final` source
// finds is used even when it is plain: it has already chosen. An `unsure`
// source may answer "none" when it only ran out of time.
class LyricsStep(
    val source: LyricsSource,
    val final: Boolean = false,
    val unsure: Boolean = false,
    val fetch: suspend () -> Lyrics?,
)

// What a search found, and whether every source answered. An answer with a
// source that could not be reached is not saved, so it is asked again.
// `unsure` is set when a source that may have run out of time said "none";
// `pick` names the listener's pick the lyrics came from.
data class LyricsSearch(
    val lyrics: Lyrics?,
    val complete: Boolean,
    val askedOnline: Boolean,
    val unsure: Boolean = false,
    val pick: String? = null,
)

// Asks each source in order. The first synced lyrics, or any a final
// source finds, win at once; plain lyrics are kept while the rest are
// asked for synced ones, and win if none have them. A source that fails,
// or is cut off by a time limit of its own, is skipped, and the search is
// then not complete.
suspend fun searchInOrder(steps: List<LyricsStep>): LyricsSearch {
    var plain: Lyrics? = null
    var complete = true
    var askedOnline = false
    var unsure = false
    for (step in steps) {
        if (step.source == LyricsSource.Online) askedOnline = true
        val found = try {
            step.fetch()
        } catch (e: CancellationException) {
            // The whole search was stopped: that goes on. Otherwise the
            // source ran out of its own time.
            currentCoroutineContext().ensureActive()
            complete = false
            null
        } catch (_: Exception) {
            complete = false
            null
        }
        if (found == null || found.isEmpty) {
            if (step.unsure) unsure = true
            continue
        }
        if (found.synced || step.final) return LyricsSearch(found, complete, askedOnline)
        if (plain == null) plain = found
    }
    return LyricsSearch(plain, complete, askedOnline, unsure)
}

// Every set of lyrics found for a song to choose from, the sources that
// could not be reached, and whether the online library may be asked.
// `serverChoice` is what the server has the song set to ("auto", "none" or
// a copy's id) when the server keeps lyrics choices for every app, and
// null when every choice stays on this phone.
data class CandidateSearch(
    val found: List<LyricsCandidate>,
    val missed: Set<LyricsSource>,
    val onlineAllowed: Boolean,
    val serverChoice: String? = null,
)

// A song on the signed-in server: the session, and the song's id there.
private class ServerSong(val session: Session, val id: String, val copy: SourceTrackEntity?)

// Finds a song's lyrics: the ones the listener picked, if any, or else from
// the server, then the song file, then an .lrc file beside it, then (when
// allowed) the online library. Answers are kept per song, in memory and in
// the cache folder. A server that keeps lyrics choices for every app has
// the last word on its songs (see ServerChoices.kt).
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
    private val choices: LyricsChoices,
    // Real lengths learned by playing, which the online library matches by.
    private val lengths: RealLengths,
) {
    private val answers = LyricsAnswers(LyricsCache(File(context.cacheDir, "lyrics")))

    // The copy the server has pinned for a song, by song, as far as this
    // phone has heard, so its lyrics can say where they are from.
    private val serverPicks = HashMap<String, String>()

    // Whether each server keeps lyrics choices, and when that was learned.
    private val keepsChoices = HashMap<String, Pair<Boolean, Long>>()

    // Goes up for a song each time its lyrics must be fetched again though
    // the listener's choice on this phone stayed the same.
    private val revisions = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun revisionOf(songId: String): Flow<Int> = revisions.map { it[songId] ?: 0 }.distinctUntilChanged()

    // The song's lyrics, None when every source answered without any (or
    // the listener hid them), or Failed when the lookup could not finish.
    // Only a finished lookup is saved (see LyricsAnswers). Lyrics the
    // listener picked come first, while their source still has them; when
    // it does not (the file is gone, or the library cannot be reached), the
    // usual order stands in and the pick is tried again next time.
    // `resumed` and `away` are for the lyrics view coming back on screen and
    // being off it (see LyricsAnswers.answer).
    suspend fun answerFor(song: LyricsSong, resumed: Boolean = false, away: () -> Boolean = { false }): LyricsAnswer {
        val (choice, onlineAllowed) = try {
            choices.current(song.id) to settings.prefs.first().lyricsOnline
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return LyricsAnswer.Failed
        }
        if (choice.hidden) return LyricsAnswer.None
        val picked = choice.pick?.encoded()
        return answers.answer(song.id, picked, onlineAllowed, resumed, away) { search(song, choice.pick, picked, onlineAllowed) }
    }

    // Asks the sources for the song's lyrics: the listener's pick first,
    // then the usual order.
    private suspend fun search(song: LyricsSong, pick: LyricsPick?, picked: String?, onlineAllowed: Boolean): LyricsSearch {
        val facts = described(song)
        val copies = if (isFind(song.id)) emptyList() else sources.copies(song.id)
        val phone = copies.firstOrNull { it.sourceId == DEVICE && !it.uri.isNullOrEmpty() }?.uri?.toUri()
        pick?.let {
            val found = try {
                fetchPick(it, facts, copies, phone, onlineAllowed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (found != null && !found.isEmpty) {
                return LyricsSearch(found, complete = true, askedOnline = true, pick = picked)
            }
        }
        val serverDecides = serverSong(song.id, copies)?.let { keepsChoices(it.session) } == true
        val steps = lyricsSteps(
            server = { fromServer(facts, copies, forLookup = true) },
            serverDecides = serverDecides,
            songFile = phone?.let { file -> suspend { fromSongFile(file) } },
            lyricsFile = phone?.let { file -> suspend { fromLyricsFile(file) } },
            online = if (onlineAllowed && facts.title.isNotBlank() && facts.artist.isNotBlank()) {
                suspend { online.find(facts.title, facts.artist, facts.album, lengthOf(facts)) }
            } else {
                null
            },
        )
        return searchInOrder(steps)
    }

    // Asks for the song's lyrics again, for the lyrics view's "tap to retry".
    fun retry(songId: String) = refresh(songId)

    // Uses these lyrics for the song from now on, on this phone. They are
    // kept as the song's answer before the pick is saved, so the lyrics
    // view, which follows the pick, finds them at once. A copy the server
    // holds is chosen with chooseOnServer instead.
    suspend fun choose(songId: String, candidate: LyricsCandidate) {
        val lyrics = candidate.lyrics ?: return
        val entry = CachedLyrics(
            System.currentTimeMillis(),
            lyrics,
            askedOnline = true,
            lookupVersion = ONLINE_LOOKUP_VERSION,
            pick = candidate.pick.encoded(),
        )
        keep(songId, entry)
        choices.pick(songId, candidate.pick)
    }

    // Sets the song's lyrics on the server, for every app: one of its
    // copies by id, "auto" or "none". This phone then follows the server
    // (see ServerChoices.kt) and the song's lyrics are fetched again at
    // once. Throws IOException when the server cannot take it.
    suspend fun chooseOnServer(songId: String, choice: String, showAgain: Boolean = false) {
        val copies = if (isFind(songId)) emptyList() else sources.copies(songId)
        val server = serverSong(songId, copies) ?: throw IOException("The song is not on the server")
        sendServerChoice(
            songId,
            choice,
            choices,
            send = { wanted ->
                val now = try {
                    server.session.client.setLyricsChoice(server.id, wanted)
                } catch (e: SubsonicException) {
                    throw IOException(e.message, e)
                }
                notePin(songId, now)
            },
            forget = { forget(songId) },
            showAgain = showAgain,
        )
        refresh(songId)
    }

    // Hides the song's lyrics: on the server too, for every app, when it
    // keeps lyrics choices, or else only on this phone.
    suspend fun hide(songId: String) {
        if (!onServerIfKept(songId, LYRICS_NONE)) choices.hide(songId)
    }

    // Shows the song's lyrics again, on the server too when it keeps
    // lyrics choices.
    suspend fun show(songId: String) {
        if (!onServerIfKept(songId, LYRICS_AUTO, showAgain = true)) choices.show(songId)
    }

    // Sends a choice to the server when the song is on one that keeps
    // lyrics choices. False when it is not, or the server could not take it.
    private suspend fun onServerIfKept(songId: String, choice: String, showAgain: Boolean = false): Boolean {
        val copies = if (isFind(songId)) emptyList() else sources.copies(songId)
        val server = serverSong(songId, copies) ?: return false
        if (!keepsChoices(server.session)) return false
        return try {
            chooseOnServer(songId, choice, showAgain)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    // Every set of lyrics that can be found for the song, for the listener
    // to choose from. With a server that keeps lyrics choices for every app:
    // its automatic answer, the copies its sources hold, and the song
    // file's and .lrc file's. Otherwise: the server's, the song file's, the
    // .lrc file's, and (when allowed) the online library's match and the
    // copies its search holds with the same title and artist. A source that
    // cannot be reached is named in the answer and left out.
    suspend fun candidatesFor(song: LyricsSong): CandidateSearch {
        val onlineAllowed = settings.prefs.first().lyricsOnline
        val facts = described(song)
        val copies = if (isFind(song.id)) emptyList() else sources.copies(song.id)
        val phone = copies.firstOrNull { it.sourceId == DEVICE && !it.uri.isNullOrEmpty() }?.uri?.toUri()
        val server = serverSong(song.id, copies)
        if (server != null && keepsChoices(server.session)) {
            serverCandidatesFor(song.id, facts, copies, phone, server, onlineAllowed)?.let { return it }
        }
        return localCandidatesFor(facts, copies, phone, onlineAllowed)
    }

    // The chooser's list from a server that keeps lyrics choices, or null
    // when it turns out not to offer them for this song (its lookups were
    // switched off, or it does not know the song), so the usual list is
    // made instead.
    private suspend fun serverCandidatesFor(
        songId: String,
        facts: LyricsSong,
        copies: List<SourceTrackEntity>,
        phone: Uri?,
        server: ServerSong,
        onlineAllowed: Boolean,
    ): CandidateSearch? = coroutineScope {
        val missed = mutableSetOf<LyricsSource>()
        val listed: LyricsCandidates? = try {
            server.session.client.lyricsCandidates(server.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SubsonicException.Unreachable) {
            missed += LyricsSource.Server
            null
        } catch (_: SubsonicException.Server) {
            noteKeepsChoices(server.session, false)
            return@coroutineScope null
        } catch (_: Exception) {
            return@coroutineScope null
        }
        val choice = listed?.choice ?: LYRICS_AUTO
        if (listed != null) {
            notePin(songId, choice)
            // Changed from another app or device since this phone saved it.
            val saved = answers.saved(songId)
            if (servedStale(saved, choice)) {
                forget(songId)
                refresh(songId)
            }
        }
        suspend fun <T> attempt(source: LyricsSource, fetch: suspend () -> T?): T? = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            synchronized(missed) { missed += source }
            null
        }
        val answer = async { if (listed != null && choice == LYRICS_AUTO) attempt(LyricsSource.Server) { fromServer(facts, copies) } else null }
        val songFile = async { phone?.let { attempt(LyricsSource.SongFile) { fromSongFile(it) } } }
        val lyricsFile = async { phone?.let { attempt(LyricsSource.LyricsFile) { fromLyricsFile(it) } } }
        val local = buildList {
            songFile.await()?.let { add(LyricsCandidate(LyricsPick.Own(LyricsSource.SongFile), CandidateOrigin.SongFile, it)) }
            lyricsFile.await()?.let { add(LyricsCandidate(LyricsPick.Own(LyricsSource.LyricsFile), CandidateOrigin.LyricsFile, it)) }
        }
        CandidateSearch(serverCandidates(listed, answer.await(), local), synchronized(missed) { missed.toSet() }, onlineAllowed, choice)
    }

    // The chooser's list with every choice kept on this phone. All sources
    // are asked at once.
    private suspend fun localCandidatesFor(
        facts: LyricsSong,
        copies: List<SourceTrackEntity>,
        phone: Uri?,
        onlineAllowed: Boolean,
    ): CandidateSearch = coroutineScope {
        val missed = mutableSetOf<LyricsSource>()
        suspend fun <T> attempt(source: LyricsSource, fetch: suspend () -> T?): T? = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            synchronized(missed) { missed += source }
            null
        }
        val askOnline = onlineAllowed && facts.title.isNotBlank() && facts.artist.isNotBlank()
        val server = async { attempt(LyricsSource.Server) { fromServer(facts, copies) } }
        val songFile = async { phone?.let { attempt(LyricsSource.SongFile) { fromSongFile(it) } } }
        val lyricsFile = async { phone?.let { attempt(LyricsSource.LyricsFile) { fromLyricsFile(it) } } }
        val match = async {
            if (askOnline) attempt(LyricsSource.Online) { online.exact(facts.title, facts.artist, facts.album, lengthOf(facts)) } else null
        }
        val search = async {
            if (askOnline) attempt(LyricsSource.Online) { online.copiesOf(facts.title, facts.artist, facts.album, lengthOf(facts)) } else null
        }
        val found = buildList {
            server.await()?.let { add(LyricsCandidate(LyricsPick.Own(LyricsSource.Server), CandidateOrigin.Server, it)) }
            songFile.await()?.let { add(LyricsCandidate(LyricsPick.Own(LyricsSource.SongFile), CandidateOrigin.SongFile, it)) }
            lyricsFile.await()?.let { add(LyricsCandidate(LyricsPick.Own(LyricsSource.LyricsFile), CandidateOrigin.LyricsFile, it)) }
            match.await()?.let { add(it.asCandidate(CandidateOrigin.OnlineMatch)) }
            search.await().orEmpty().forEach { add(it.asCandidate(CandidateOrigin.OnlineSearch)) }
        }
        CandidateSearch(found, synchronized(missed) { missed.toSet() }, onlineAllowed)
    }

    // What the online library finds for words the listener typed. Throws
    // IOException when it cannot be reached.
    suspend fun searchOnline(query: String): List<LyricsCandidate> =
        online.search(query).map { it.asCandidate(CandidateOrigin.OnlineSearch) }

    // What the server's sources find for words the listener typed, for a
    // song whose tags are wrong: "artist - title", or a title. Throws
    // IOException when the server cannot be reached or asked.
    suspend fun searchOnServer(songId: String, query: String): List<LyricsCandidate> {
        val copies = if (isFind(songId)) emptyList() else sources.copies(songId)
        val server = serverSong(songId, copies) ?: throw IOException("The song is not on the server")
        val (title, artist) = splitLyricsSearch(query)
        val found = try {
            server.session.client.lyricsCandidates(server.id, title, artist)
        } catch (e: SubsonicException) {
            throw IOException(e.message, e)
        }
        return found.candidate.filter { it.id.isNotBlank() }.map { it.asCandidate() }
    }

    // The lyrics a pick names, fetched from its source again.
    private suspend fun fetchPick(
        pick: LyricsPick,
        facts: LyricsSong,
        copies: List<SourceTrackEntity>,
        phone: Uri?,
        onlineAllowed: Boolean,
    ): Lyrics? = when (pick) {
        is LyricsPick.Online -> if (onlineAllowed) online.byId(pick.id) else null
        // The server answers with its own choice.
        is LyricsPick.OnServer -> fromServer(facts, copies)
        is LyricsPick.Own -> when (pick.source) {
            LyricsSource.Server -> fromServer(facts, copies)
            LyricsSource.SongFile -> phone?.let { fromSongFile(it) }
            LyricsSource.LyricsFile -> phone?.let { fromLyricsFile(it) }
            LyricsSource.Online -> null
        }
    }

    // Keeps an answer in memory and in the cache folder.
    private suspend fun keep(songId: String, entry: CachedLyrics) = answers.keep(songId, entry)

    // Forgets a song's answer, in memory and in the cache folder.
    private suspend fun forget(songId: String) = answers.forget(songId)

    // Tells whoever shows the song's lyrics to fetch them again.
    private fun refresh(songId: String) {
        revisions.update { it + (songId to (it[songId] ?: 0) + 1) }
    }

    // Remembers what the server has the song set to, for where its lyrics
    // are from.
    private fun notePin(songId: String, choice: String) {
        synchronized(serverPicks) {
            if (choice == LYRICS_AUTO || choice == LYRICS_NONE) serverPicks.remove(songId) else serverPicks[songId] = choice
        }
    }

    // Whether the server keeps lyrics choices for every app: from what it
    // listed at sign-in, or by asking, since it may have been updated or
    // had its lookups switched on since. A "no" learned from the server
    // itself stands over what it listed at sign-in, for a while.
    private suspend fun keepsChoices(session: Session): Boolean {
        val now = System.currentTimeMillis()
        synchronized(keepsChoices) {
            keepsChoices[session.sourceId]?.let { (yes, at) -> if (now - at < RECHECK_CHOICES_MS) return yes }
        }
        if (offersLyricsChoices(session.extensions)) return true
        // Noted only when the server itself said.
        val yes = session.client.supportsIfKnown(OCTO_LYRICS) ?: return false
        noteKeepsChoices(session, yes)
        return yes
    }

    private fun noteKeepsChoices(session: Session, yes: Boolean) {
        synchronized(keepsChoices) { keepsChoices[session.sourceId] = yes to System.currentTimeMillis() }
    }

    private suspend fun fromSongFile(file: Uri): Lyrics? = withContext(Dispatchers.IO) {
        tags.allTags(file)?.let(::songFileLyrics)?.let { parseLyricsText(it, LyricsSource.SongFile) }
    }

    private suspend fun fromLyricsFile(file: Uri): Lyrics? = withContext(Dispatchers.IO) {
        files.beside(file)?.let { parseLyricsText(it, LyricsSource.LyricsFile) }
    }

    // The length the online library matches a song by: its real one once
    // playing it found the listing wrong. Read when the online library is
    // asked, after the server, by when the song playing has usually opened.
    private fun lengthOf(song: LyricsSong): Long = lengths.lengthMs(song.id, song.durationMs)

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

    // The song on the signed-in server, or null when it is not there. For
    // a lookup, a sign-in that has not come back yet throws IOException, so
    // it is not taken for "not on the server".
    private suspend fun serverSong(songId: String, copies: List<SourceTrackEntity>, forLookup: Boolean = false): ServerSong? {
        val session = session(forLookup) ?: return null
        val sourceId = serverSourceId(session.client.baseUrl)
        val copy = copies.firstOrNull { it.sourceId == sourceId }
        val serverId = if (isFind(songId)) songId.removePrefix(FIND_PREFIX) else copy?.nativeId ?: return null
        return ServerSong(session, serverId, copy)
    }

    // The server's lyrics for the song, when it is on the signed-in server.
    // A server with the songLyrics extension is asked by song id (version 2
    // adds word timings); an older one by artist and title. Lyrics from a
    // copy the server has pinned say which.
    private suspend fun fromServer(song: LyricsSong, copies: List<SourceTrackEntity>, forLookup: Boolean = false): Lyrics? {
        val server = serverSong(song.id, copies, forLookup) ?: return null
        val client = server.session.client
        val found = try {
            when (val call = serverLyricsCall(server.session.extensions)) {
                ServerLyricsCall.ByName -> client.lyrics(server.copy?.artist ?: song.artist, server.copy?.title ?: song.title)
                    ?.let { parseLyricsText(it, LyricsSource.Server) }
                else -> serverLyrics(client.lyricsBySongId(server.id, enhanced = call == ServerLyricsCall.WithWords), Locale.getDefault().language)
            }
        } catch (_: SubsonicException.NotFound) {
            null
        } catch (e: SubsonicException.Unreachable) {
            throw IOException("Server unreachable", e)
        }
        val pinned = synchronized(serverPicks) { serverPicks[song.id] }
        return if (pinned != null) found?.copy(serverPick = pinned) else found
    }

    private suspend fun session(forLookup: Boolean = false): Session? {
        val state = withTimeoutOrNull(SESSION_WAIT_MS) { sessions.state.first { it !is SessionState.Loading } }
        if (state == null && forLookup) throw IOException("The saved sign-in is not back yet")
        return (state as? SessionState.SignedIn)?.session
    }
}

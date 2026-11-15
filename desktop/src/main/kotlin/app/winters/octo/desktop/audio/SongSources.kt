package app.winters.octo.desktop.audio

import java.io.File
import app.winters.octo.audio.HttpHeader
import app.winters.octo.audio.QueueItem
import app.winters.octo.audio.ReplayGainInfo
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.desktop.system.isOpenedFile
import app.winters.octo.desktop.system.openedFileOf
import app.winters.octo.discovery.knownLengthMs
import app.winters.octo.playback.isRadio
import app.winters.octo.playback.radioOf
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.ui.family.listedAudioType
import app.winters.octo.ui.family.songParams
import app.winters.octo.subsonic.PLAY_TOKEN_PARAM
import app.winters.octo.subsonic.playToken
import app.winters.octo.subsonic.gateway.GatewayNames
import app.winters.octo.subsonic.httpsTwin
import app.winters.octo.subsonic.origin
import app.winters.octo.playback.radioIsHls
import okhttp3.HttpUrl

// Where the engine fetches a song from: a file path, or a signed stream
// address, with any extra request headers it needs, and those it adds only
// once a hop of the same server has moved the stream up to https (a
// sign-in page's, for a plain http home address whose https twin may have
// them).
data class SongAddress(
    val source: String,
    val headers: Map<String, String> = emptyMap(),
    val secureHeaders: Map<String, String> = emptyMap(),
)

// Turns a song into the address the engine plays it from, or null when it
// cannot be played now (signed out, say).
fun interface SongSources {
    fun addressOf(song: Song): SongAddress?

    // The address for one play of a queue entry, which the engine is given:
    // `restarts` counts the times a version picked for the song started the
    // entry over. A source whose addresses name no play gives the song's own.
    fun addressOf(entry: QueueEntry, restarts: Int): SongAddress? = addressOf(entry.song)
}

// Songs from the signed-in server, as the phone streams them: the file as
// it is ("format=raw", the original quality) or, at a smaller `quality`, a
// copy the server makes on the way (a lossy song already at or under that
// bitrate still comes as it is, see songParams), at an address signed the way every
// other call is, with the server's extra headers (a sign-in page's only
// where `gatewayOrigins` lets them go). The engine reuses the address for
// range requests and reconnects (a made MP3 it opens again at a time
// instead), so it is signed when the song is queued. A song found online
// that the server has since fetched into the library (`landed` names its
// library id) plays from that file. Each play of a queue entry names itself
// in its address (PLAY_TOKEN_PARAM: this run's `session`, the entry and its
// restarts), so the server keeps every request of one play (reconnects and
// seeks too) on one file, and a version picked for the song reaches the
// restart that follows the pick.
class ServerSongs(
    private val headers: () -> Map<String, String> = { emptyMap() },
    private val landed: (String) -> String? = { null },
    // What the stream asks for: the file as it is unless this app picks a
    // lower quality (see streamParams).
    private val params: () -> Map<String, String> = { mapOf("format" to "raw") },
    private val client: () -> SubsonicClient?,
    // The addresses the server's sign-in page headers may go to.
    private val gatewayOrigins: () -> Set<String> = { emptySet() },
    // Tells this run's plays apart from another run's, whose queue entries
    // are numbered from 1 again.
    private val session: String = newPlaySession(),
) : SongSources {
    override fun addressOf(song: Song): SongAddress? = address(song, emptyMap())

    override fun addressOf(entry: QueueEntry, restarts: Int): SongAddress? =
        address(entry.song, mapOf(PLAY_TOKEN_PARAM to playToken("$session-${entry.key}", restarts)))

    private fun address(song: Song, play: Map<String, String>): SongAddress? {
        val server = client() ?: return null
        val landedId = landed(song.id)
        val id = landedId ?: song.id
        // A song since fetched into the library is that file, whose type and
        // bitrate the listing here does not give: it asks as the quality says.
        val asked = if (landedId != null) params() else songParams(params(), listedAudioType(song.contentType, song.suffix), song.bitRate)
        val url = server.url("stream", mapOf("id" to id) + asked + play)
        val all = headers()
        val allowed = gatewayOrigins()
        return SongAddress(url.toString(), streamHeaders(all, url, allowed), secureStreamHeaders(all, url, allowed))
    }
}

// A name for one run's plays: eight random letters and digits.
fun newPlaySession(): String = java.util.UUID.randomUUID().toString().substringBefore('-')

// The headers a stream at `url` carries: the server's own, less the ones a
// sign-in page reads unless `url` is at an address they may go to (the
// main one, or a home one over https).
fun streamHeaders(headers: Map<String, String>, url: HttpUrl, gatewayOrigins: Set<String>): Map<String, String> =
    if (origin(url) in gatewayOrigins) headers else headers.filterKeys { !GatewayNames.isGatewayName(it) }

// The headers a stream at plain http `url` carries only once a hop of the
// same server moves it up to https: a sign-in page's, when `url`'s own
// address may not have them and its https twin may. None otherwise.
fun secureStreamHeaders(headers: Map<String, String>, url: HttpUrl, gatewayOrigins: Set<String>): Map<String, String> {
    val twin = httpsTwin(url) ?: return emptyMap()
    if (origin(url) in gatewayOrigins || twin !in gatewayOrigins) return emptyMap()
    return headers.filterKeys(GatewayNames::isGatewayName)
}

// A song's id on the server, for asking the server about it: the library
// copy of a song found online once it has one, and nothing for a file on
// this computer.
fun serverSongId(song: Song, landed: (String) -> String?): String? = when {
    song.id.startsWith(LOCAL_PREFIX) || isOpenedFile(song.id) -> null
    else -> landed(song.id) ?: song.id
}

// Songs whose id starts with this are files on this computer, the rest of
// the id being the path.
const val LOCAL_PREFIX = "local:"

// Files on this computer by path, live radio from its station's own
// address, and everything else from the server.
class LocalOrServer(
    private val server: SongSources,
    // A copy kept on this computer, played in place of the server's.
    private val kept: (String) -> File? = { null },
) : SongSources {
    override fun addressOf(song: Song): SongAddress? = pick(song) { server.addressOf(song) }

    override fun addressOf(entry: QueueEntry, restarts: Int): SongAddress? = pick(entry.song) { server.addressOf(entry, restarts) }

    // A song's own address, or the server's (`fromServer`) for any other.
    private fun pick(song: Song, fromServer: () -> SongAddress?): SongAddress? =
        when {
            kept(song.id) != null -> SongAddress(kept(song.id)!!.absolutePath)
            song.id.startsWith(LOCAL_PREFIX) -> SongAddress(song.id.removePrefix(LOCAL_PREFIX))
            // The station's own server, so none of Octo's headers go along.
            isRadio(song.id) -> radioOf(song.id)?.let { (stream, _) -> SongAddress(stream) }
            // A file opened from the system (a double click, a drop): the
            // file itself, or the address it was opened from.
            isOpenedFile(song.id) -> openedFileOf(song.id)?.let { SongAddress(it.path?.absolutePath ?: it.uri) }
            else -> fromServer()
        }
}

// The engine's name for a queue entry, handed back in its events.
fun itemId(key: Long): String = "q:$key"

fun keyOfItem(id: String?): Long? = id?.removePrefix("q:")?.toLongOrNull()

// Disc and track in one number, for telling an album played in order.
fun albumOrder(song: Song): Int? = song.track?.let { (song.discNumber ?: 1) * 1000 + it }

// A queue entry as the engine takes it. The length goes along when it is
// known: without it the engine cannot plan a crossfade and joins songs
// gaplessly instead. A song found online often comes with Octo's 3:00
// guess, which is left out, so the engine takes the length from the file
// as the phone does. A song that cannot be reached gets an address that fails, so
// the engine reports it and moves on, as it does for any broken file. Live
// radio is a live stream, with no length at all. The address is the one for
// this play of the entry, its `restarts` counted.
fun queueItem(entry: QueueEntry, sources: SongSources, restarts: Int = 0): QueueItem {
    val song = entry.song
    val address = sources.addressOf(entry, restarts)
    val live = isRadio(song.id)
    val gain = song.replayGain?.let {
        if (it.trackGain == null && it.albumGain == null) null else ReplayGainInfo(it.trackGain, it.trackPeak, it.albumGain, it.albumPeak)
    }
    return QueueItem(
        id = itemId(entry.key),
        source = address?.source ?: UNREACHABLE,
        albumId = song.albumId,
        albumOrder = albumOrder(song),
        durationMs = knownLengthMs(song).takeIf { it > 0 && !live }?.toULong(),
        replayGain = gain,
        headers = address?.headers.orEmpty().map { (name, value) -> HttpHeader(name, value) },
        secureHeaders = address?.secureHeaders.orEmpty().map { (name, value) -> HttpHeader(name, value) },
        genre = genreOf(song),
        bpm = song.bpm?.takeIf { it > 0 }?.toDouble(),
        live = live,
        hls = live && radioIsHls(song.id),
    )
}


// Every genre a song has, in one line: a song in any genre that is never
// mixed (classical, a podcast) gets the plain crossfade.
fun genreOf(song: Song): String? = (listOfNotNull(song.genre) + song.genres).distinct().joinToString("; ").ifEmpty { null }

// An address no file has, for a song with nowhere to play from.
private const val UNREACHABLE = "octo-unreachable:"

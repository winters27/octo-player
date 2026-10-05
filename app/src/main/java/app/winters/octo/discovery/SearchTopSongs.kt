package app.winters.octo.discovery

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.data.Session
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.data.runsOcto
import app.winters.octo.subsonic.OCTO_TOP_SONGS
import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.TopSong
import app.winters.octo.subsonic.TopSongs
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// How long the chart is kept before the empty search asks for it again.
private const val CHART_KEPT_MS = 60 * 60 * 1000L

// One ranked song as the app shows it: a library song, or a find. Plays are
// Last.fm's, when the list was ranked by them.
data class RankedTrack(val rank: Int, val track: TrackEntity, val plays: Long?)

// A ranked list: an artist's top songs, or the chart, which names no artist.
data class RankedTracks(val artist: String?, val source: String, val songs: List<RankedTrack>)

// The ranked lists an Octo server keeps for search: an artist's top songs
// and the chart of the moment. Only a server that lists octoTopSongs has
// them; one signed in to before it did is asked once, live, since it may
// have been updated since. Nothing comes back without one, or when it
// cannot be reached.
@Singleton
class SearchTopSongs @Inject constructor(
    private val sessions: SessionRepository,
    private val discovery: Discovery,
) {
    private val offered = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var chart: Triple<String, Long, RankedTracks>? = null

    // The artist's top songs. `libraryId` is a library artist's id, which
    // names the server's artist when it came from this server; `serverId`
    // an artist the server found online.
    suspend fun forArtist(name: String, libraryId: String? = null, serverId: String? = null): RankedTracks? {
        val session = session() ?: return null
        if (!offers(session)) return null
        val id = serverId ?: libraryId?.let { serverArtistIdOf(it, session.sourceId) }
        val list = answerOf { session.client.artistTopSongs(name, id, SEARCH_TOP_SONGS) } ?: return null
        if (session()?.id != session.id) return null
        return ranked(list)
    }

    // The chart of the moment, kept an hour per kept server: its songs are
    // matched to that server's library.
    suspend fun chart(): RankedTracks? {
        val session = session() ?: return null
        val key = session.id.ifEmpty { session.sourceId }
        val now = System.currentTimeMillis()
        chart?.let { (server, at, kept) -> if (server == key && now - at < CHART_KEPT_MS) return kept }
        if (!offers(session)) return null
        val list = answerOf { session.client.topChart(TOP_CHART_SONGS) } ?: return null
        // Another server in use by the time it came: not this one's chart.
        if (session()?.id != session.id) return null
        return ranked(list)?.also { chart = Triple(key, now, it) }
    }

    // The server's songs as the app shows them, each in its place.
    private suspend fun ranked(list: TopSongs): RankedTracks? {
        val entries = list.entry.filter { it.song != null }
        val songs = rankTracks(entries, discovery.resolveInPlace(entries.map { it.song!! }))
        return RankedTracks(list.artist, list.source, songs).takeIf { songs.isNotEmpty() }
    }

    // Kept only once the server itself said; a server out of reach is asked
    // again next time.
    private suspend fun offers(session: Session): Boolean {
        if ("$OCTO_TOP_SONGS:1" in session.extensions) return true
        if (!session.runsOcto) return false
        offered[session.sourceId]?.let { return it }
        val yes = session.client.supportsIfKnown(OCTO_TOP_SONGS) ?: return false
        offered[session.sourceId] = yes
        return yes
    }

    private fun session(): Session? = (sessions.state.value as? SessionState.SignedIn)?.session

    private suspend fun <T> answerOf(call: suspend () -> T): T? =
        try {
            call()
        } catch (e: SubsonicException) {
            null
        }
}

// Each entry with the song the app shows for it (`tracks`, in the same
// places), so a rank stays with its song. A song gone from the library is
// left out, and a song listed twice (two copies of it, or a find that became
// the library song above it) stands once, at its better place. An entry the
// server sent without a rank takes its place in the list.
internal fun rankTracks(entries: List<TopSong>, tracks: List<TrackEntity?>): List<RankedTrack> {
    val seen = HashSet<String>()
    return entries.zip(tracks).mapIndexedNotNull { index, (entry, track) ->
        track?.takeIf { seen.add(it.id) }?.let { RankedTrack(entry.rank.takeIf { rank -> rank > 0 } ?: (index + 1), it, entry.plays) }
    }
}

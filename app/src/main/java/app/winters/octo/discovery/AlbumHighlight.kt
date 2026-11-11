package app.winters.octo.discovery

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.subsonic.Song
import app.winters.octo.ui.paging.Keyed
import app.winters.octo.ui.paging.RowKey
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// How many of an artist's top songs are looked through for an album's main song.
private const val RANKED = 30

// A song as the ranking knows it: its id on the server, title and artist.
data class RankedSong(val id: String, val title: String, val artist: String)

// The album's main song, as Apple Music marks it with a star: of the album's
// songs, the one that comes first in the artist's top songs. Matched by id,
// or for a song the ranking knows by another id (one found online, say) by
// its title, features left out, and its artist. None when no album song is
// among the top songs.
fun albumHighlight(tracks: List<TrackEntity>, ranked: List<RankedSong>): String? {
    for (top in ranked) {
        tracks.firstOrNull { it.id == top.id }?.let { return it.id }
        val title = titleKey(top.title)
        if (title.isEmpty()) continue
        tracks.firstOrNull { titleKey(it.title) == title && SongIdentity.sameArtistName(it.artist, top.artist) }?.let { return it.id }
    }
    return null
}

private fun titleKey(title: String): String = SongIdentity.key(SongIdentity.stripFeatures(title))

// albumHighlight over rows read a page at a time, for an album too big to
// hold: one walk keeps, per top song, its first row with the same id and
// its first with the same title and artist, then the ranks pick from those
// as albumHighlight does. The walk ends early once the best rank has its id.
suspend fun highlightWalk(ranked: List<RankedSong>, page: suspend (after: RowKey?) -> List<Keyed<TrackEntity>>): String? {
    if (ranked.isEmpty()) return null
    val byId = arrayOfNulls<String>(ranked.size)
    val byTitle = arrayOfNulls<String>(ranked.size)
    val keys = ranked.map { titleKey(it.title) }
    var after: RowKey? = null
    while (true) {
        val rows = page(after)
        for (keyed in rows) {
            val track = keyed.row
            val key = titleKey(track.title)
            for ((i, top) in ranked.withIndex()) {
                if (byId[i] == null && track.id == top.id) byId[i] = track.id
                if (byTitle[i] == null && keys[i].isNotEmpty() && key == keys[i] && SongIdentity.sameArtistName(track.artist, top.artist)) {
                    byTitle[i] = track.id
                }
            }
        }
        if (byId[0] != null || rows.isEmpty()) break
        after = rows.last().key
    }
    for (i in ranked.indices) {
        byId[i]?.let { return it }
        if (keys[i].isEmpty()) continue
        byTitle[i]?.let { return it }
    }
    return null
}

// The artist's top songs from the signed-in server, for marking an album's
// main song. Kept for as long as the app runs, per server, user and artist;
// nothing without a server or when it cannot be reached (asked again then).
@Singleton
class TopSongsSource @Inject constructor(private val sessions: SessionRepository) {
    private val cache = ConcurrentHashMap<String, List<RankedSong>>()

    suspend fun forArtist(name: String): List<RankedSong> {
        if (name.isBlank()) return emptyList()
        val client = (sessions.state.value as? SessionState.SignedIn)?.session?.client ?: return emptyList()
        val key = "${client.username}@${client.baseUrl}|${SongIdentity.key(name)}"
        cache[key]?.let { return it }
        val songs = runCatching { client.topSongs(name, RANKED) }.getOrNull() ?: return emptyList()
        return songs.map(::ranked).also { cache[key] = it }
    }

    private fun ranked(song: Song) = RankedSong(song.id, song.title, (song.displayArtist ?: song.artist).orEmpty())
}

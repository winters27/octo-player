package app.winters.octo.discovery

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.subsonic.Song
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

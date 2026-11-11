package app.winters.octo.discovery.today

import app.winters.octo.catalog.SongIdentity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.discovery.RankedSong

// A labeled pin: today's album highlight over the album's whole list, as
// discovery/AlbumHighlight.kt had it at c2208eaa, frozen as the measure the
// bounded walk of a huge album must match. Never edited to follow the app.

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

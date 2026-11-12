package app.winters.octo.livelists.today

import app.winters.octo.catalog.PlayedTrack
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.select
import app.winters.octo.sort.TrackFields
import app.winters.octo.sort.songListening

// A labeled pin: what livelists/LiveListSongs.kt picked at c2208eaa, given
// what it read whole (every song in library order, the liked ids and the
// played songs), frozen as the measure the walked lists must match. Never
// edited to follow the app.
fun pick(library: List<TrackEntity>, liked: List<String>, played: List<PlayedTrack>, query: LibraryQuery, now: Long): List<TrackEntity> =
    query.select(library, now, TrackFields(liked.toHashSet(), songListening(played)))

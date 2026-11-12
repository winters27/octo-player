package app.winters.octo.catalog.today

import app.winters.octo.catalog.TrackPlace

// A labeled pin: today's album-by-album and artist-by-artist play order, as
// catalog/Places.kt had it at c2208eaa, frozen as the measure. Never edited
// to follow the app.

// The songs of whole groups (albums or artists) in the groups' order, each
// group's songs in the order given. Groups not listed are left out.
fun songsInGroupOrder(places: List<TrackPlace>, groupOf: (TrackPlace) -> String, groups: List<String>): List<String> {
    val byGroup = places.groupBy(groupOf)
    return groups.distinct().flatMap { group -> byGroup[group].orEmpty().map { it.id } }
}

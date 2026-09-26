package app.winters.octo.catalog

// Where a song sits: its album and its artist.
data class TrackPlace(val id: String, val albumId: String, val artistId: String)

// The songs of whole groups (albums or artists) in the groups' order, each
// group's songs in the order given. Groups not listed are left out.
fun songsInGroupOrder(places: List<TrackPlace>, groupOf: (TrackPlace) -> String, groups: List<String>): List<String> {
    val byGroup = places.groupBy(groupOf)
    return groups.distinct().flatMap { group -> byGroup[group].orEmpty().map { it.id } }
}

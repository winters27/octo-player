package app.winters.octo.discovery.today

import app.winters.octo.catalog.SourceTrackEntity

// A labeled pin: today's vote for a library album's album on the server,
// as Discovery.libraryAlbum had it at c2208eaa (over every copy of the
// album's songs, read whole), frozen as the measure the paged vote must
// match. Never edited to follow the app.

// The server album most of the songs' copies from `sourceId` belong to.
fun libraryAlbumVote(copies: List<SourceTrackEntity>, sourceId: String): String? =
    copies.filter { it.sourceId == sourceId }.groupingBy { it.albumId }.eachCount().maxByOrNull { it.value }?.key

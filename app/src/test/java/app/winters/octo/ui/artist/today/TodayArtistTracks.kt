package app.winters.octo.ui.artist.today

// A labeled pin: today's base order of an artist page's songs, as
// catalog/CatalogDao.kt's artistTracks had it at c2208eaa, frozen as the
// measure the artist page's walk must match. Never edited to follow the
// app. Today's last tie fell to the order SQLite read the rows in; the
// song's id is the declared tie after it.
const val TODAY_ARTIST_TRACKS = """
    SELECT t.id FROM track t LEFT JOIN album a ON a.id = t.albumId
    WHERE t.artistId = ?
    ORDER BY COALESCE(a.year, 0) DESC, a.sortKey, t.albumId, t.albumOrder, t.id
"""

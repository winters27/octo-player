package app.winters.octo.sort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SortQueriesTest {
    private val scopes = listOf(SongScope.All, SongScope.Genre("Rock"), SongScope.Liked)

    // Every piece an ORDER BY may be made of. Anything else in one means
    // text got into the query that was not written here.
    private val songKeys = setOf(
        "t.sortKey", "COALESCE(ar.sortKey, LOWER(t.artist))", "COALESCE(al.sortKey, LOWER(t.album))",
        "t.id", "t.albumId", "t.albumOrder", "t.addedAt", "t.year IS NULL", "t.year", "t.durationMs",
        "t.rating = 0", "t.rating", "EXISTS(SELECT 1 FROM liked_track lk WHERE lk.trackId = t.id)",
        "(SELECT lk.likedAt FROM liked_track lk WHERE lk.trackId = t.id) IS NULL",
        "(SELECT lk.likedAt FROM liked_track lk WHERE lk.trackId = t.id)",
    )
    private val albumKeys = setOf(
        "a.sortKey", "COALESCE(ar.sortKey, LOWER(a.artist))", "a.id", "a.year IS NULL", "a.year", "a.addedAt",
        "a.songCount", "a.durationMs",
    )
    private val artistKeys = setOf(
        "r.sortKey", "r.id", "r.songCount", "r.albumCount", "(SELECT MAX(t.addedAt) FROM track t WHERE t.artistId = r.id)",
    )

    // The ORDER BY's keys, split at the commas outside brackets.
    private fun keysOf(sql: String): List<String> {
        val keys = mutableListOf<String>()
        val order = sql.substringAfter(" ORDER BY ")
        var depth = 0
        var start = 0
        order.forEachIndexed { index, char ->
            when (char) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    keys += order.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        keys += order.substring(start).trim()
        return keys.map { it.removeSuffix(" DESC") }
    }

    private fun orderOf(sql: String) = sql.substringAfter(" ORDER BY ")

    @Test
    fun songOrdersUseOnlyKnownPieces() {
        SongSort.entries.forEach { sort ->
            scopes.forEach { scope ->
                listOf(false, true).forEach { descending ->
                    val keys = keysOf(songQuery(sort, descending, scope).sql)
                    assertTrue("$sort $scope: $keys", keys.all { it in songKeys })
                    // Always ends on the id, so equal rows keep one steady order.
                    assertEquals("t.id", keys.last())
                }
            }
        }
    }

    @Test
    fun albumAndArtistOrdersUseOnlyKnownPieces() {
        AlbumSort.entries.forEach { sort ->
            listOf(false, true).forEach { descending ->
                listOf(null, "device:artist:x").forEach { artistId ->
                    val keys = keysOf(albumQuery(sort, descending, artistId).sql)
                    assertTrue("$sort: $keys", keys.all { it in albumKeys })
                    assertEquals("a.id", keys.last())
                }
            }
        }
        ArtistSort.entries.forEach { sort ->
            listOf(false, true).forEach { descending ->
                val keys = keysOf(artistQuery(sort, descending).sql)
                assertTrue("$sort: $keys", keys.all { it in artistKeys })
                assertEquals("r.id", keys.last())
            }
        }
    }

    @Test
    fun namesAreBoundNeverWrittenIn() {
        val name = "Rock'; DROP TABLE track; --"
        val query = songQuery(SongSort.Title, descending = false, SongScope.Genre(name))
        assertFalse(query.sql.contains("DROP"))
        assertTrue(query.sql.contains("WHERE t.genre = ? COLLATE NOCASE"))
        assertEquals(listOf(name), query.args)

        val artist = "device:artist:it's"
        val albums = albumQuery(AlbumSort.Year, descending = true, artistId = artist)
        assertFalse(albums.sql.contains("it's"))
        assertTrue(albums.sql.contains("WHERE a.artistId = ?"))
        assertEquals(listOf(artist), albums.args)

        assertEquals(emptyList<String>(), songQuery(SongSort.Title, descending = false, SongScope.All).args)
        assertEquals(emptyList<String>(), albumQuery(AlbumSort.Title, descending = false).args)
    }

    @Test
    fun onlyTheChosenKeyFlips() {
        assertEquals(
            "t.addedAt DESC, t.albumId, t.albumOrder, t.sortKey, t.id",
            orderOf(songQuery(SongSort.RecentlyAdded, descending = true, SongScope.All).sql),
        )
        assertEquals(
            "t.addedAt, t.albumId, t.albumOrder, t.sortKey, t.id",
            orderOf(songQuery(SongSort.RecentlyAdded, descending = false, SongScope.All).sql),
        )
    }

    @Test
    fun artistOrderGoesAlbumThenAlbumOrderThenTitle() {
        assertEquals(
            "COALESCE(ar.sortKey, LOWER(t.artist)) DESC, COALESCE(al.sortKey, LOWER(t.album)), t.albumId, t.albumOrder, t.sortKey, t.id",
            orderOf(songQuery(SongSort.Artist, descending = true, SongScope.All).sql),
        )
        assertEquals(
            "COALESCE(al.sortKey, LOWER(t.album)), COALESCE(ar.sortKey, LOWER(t.artist)), t.albumId, t.albumOrder, t.sortKey, t.id",
            orderOf(songQuery(SongSort.Album, descending = false, SongScope.All).sql),
        )
    }

    @Test
    fun missingYearsAndRatingsGoLastEitherWay() {
        listOf(false, true).forEach { descending ->
            assertTrue(orderOf(songQuery(SongSort.Year, descending, SongScope.All).sql).startsWith("t.year IS NULL, t.year"))
            assertTrue(orderOf(songQuery(SongSort.Rating, descending, SongScope.All).sql).startsWith("t.rating = 0, t.rating"))
            assertTrue(orderOf(albumQuery(AlbumSort.Year, descending).sql).startsWith("a.year IS NULL, a.year"))
        }
        // Of songs rated the same, liked ones come first.
        assertTrue(
            orderOf(songQuery(SongSort.Rating, descending = false, SongScope.All).sql)
                .contains("EXISTS(SELECT 1 FROM liked_track lk WHERE lk.trackId = t.id) DESC"),
        )
    }

    @Test
    fun playOrdersStartFromNameOrder() {
        val byTitle = songQuery(SongSort.Title, descending = false, SongScope.All).sql
        // The direction of a play order is applied afterwards, not in SQL.
        assertEquals(byTitle.substringAfter("ORDER BY"), songQuery(SongSort.MostPlayed, descending = false, SongScope.All).sql.substringAfter("ORDER BY"))
        assertEquals(byTitle.substringAfter("ORDER BY"), songQuery(SongSort.RecentlyPlayed, descending = false, SongScope.All).sql.substringAfter("ORDER BY"))
    }

    @Test
    fun headingsOnlyForNameOrders() {
        assertTrue(songQuery(SongSort.Title, descending = false, SongScope.All).sql.startsWith("SELECT t.*, t.sortKey AS heading"))
        assertTrue(songQuery(SongSort.Length, descending = false, SongScope.All).sql.startsWith("SELECT t.*, NULL AS heading"))
        assertTrue(artistQuery(ArtistSort.Name, descending = false).sql.startsWith("SELECT r.*, r.sortKey AS heading"))
        assertTrue(albumQuery(AlbumSort.SongCount, descending = false).sql.startsWith("SELECT a.*, NULL AS heading"))
    }

    @Test
    fun onlyQueriesThatReadLikesWatchThem() {
        assertFalse(songQuery(SongSort.Title, descending = false, SongScope.All).readsLikes)
        assertFalse(songQuery(SongSort.RecentlyAdded, descending = true, SongScope.Genre("Pop")).readsLikes)
        assertTrue(songQuery(SongSort.Title, descending = false, SongScope.Liked).readsLikes)
        assertTrue(songQuery(SongSort.Rating, descending = true, SongScope.All).readsLikes)
        assertTrue(songQuery(SongSort.Liked, descending = true, SongScope.All).readsLikes)
        assertTrue(songQuery(SongSort.DateLiked, descending = true, SongScope.Liked).readsLikes)
    }

    @Test
    fun likedScopeJoinsTheLikes() {
        val sql = songQuery(SongSort.DateLiked, descending = true, SongScope.Liked).sql
        assertTrue(sql.contains("FROM liked_track l JOIN track t ON t.id = l.trackId"))
        assertTrue(
            orderOf(sql).startsWith(
                "(SELECT lk.likedAt FROM liked_track lk WHERE lk.trackId = t.id) IS NULL, " +
                    "(SELECT lk.likedAt FROM liked_track lk WHERE lk.trackId = t.id) DESC",
            ),
        )
    }
}

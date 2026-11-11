package app.winters.octo.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// An album asked for a page of songs at a time settles in one line once
// its last song lands, however many pages it took, and never between two
// pages; one whose songs all landed while it was being asked for says so
// when it closes.
class RequestAlbumPagedTest {
    @Test
    fun anAlbumAskedInPagesSettlesInOneLine() {
        val arrivals = Arrivals()
        val ask = arrivals.opened("Big Album")
        val pages = (0 until 2_000).map { "find:$it" }.chunked(500)
        val said = ArrayList<String>()
        pages.forEachIndexed { i, page ->
            arrivals.joined(ask, page)
            // A song of an earlier page landing meanwhile settles nothing.
            if (i == 1) pages[0].forEach { id -> arrivals.landed(id)?.let(said::add) }
        }
        assertNull(arrivals.closed(ask))
        pages.drop(1).flatten().forEach { id -> arrivals.landed(id)?.let(said::add) }
        assertEquals(listOf("Big Album is in your library"), said)
    }

    @Test
    fun anAlbumWhoseSongsLandedWhileAskedSaysSoWhenClosed() {
        val arrivals = Arrivals()
        val ask = arrivals.opened("Quick")
        arrivals.joined(ask, listOf("find:1", "find:2"))
        assertNull(arrivals.landed("find:1"))
        assertNull(arrivals.landed("find:2"))
        assertEquals("Quick is in your library", arrivals.closed(ask))
    }

    @Test
    fun anAlbumWithNothingComingSaysNothing() {
        val arrivals = Arrivals()
        val ask = arrivals.opened("Owned")
        assertNull(arrivals.closed(ask))
        assertNull(arrivals.landed("find:9"))
    }
}

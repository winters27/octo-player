package app.winters.octo.listening

import app.winters.octo.catalog.ServerCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarsTest {
    // Library songs a, b, c, d, each with one server copy: "sa" and so on.
    private val copies = listOf("a", "b", "c", "d").map { ServerCopy(it, "s$it") }

    private fun plan(liked: Set<String>, starred: Set<String>, synced: Set<String>, from: List<ServerCopy> = copies) =
        reconcileStars(from, liked, starred, synced)

    @Test
    fun agreeingSidesNeedNothing() {
        val p = plan(liked = setOf("a"), starred = setOf("sa"), synced = setOf("sa"))
        assertEquals(StarPlan(synced = setOf("sa")), p)
    }

    @Test
    fun aLikeOnThePhoneStarsTheServerCopy() {
        val p = plan(liked = setOf("a"), starred = emptySet(), synced = emptySet())
        assertEquals(setOf("sa"), p.star)
        assertEquals(setOf("sa"), p.synced)
        assertTrue(p.like.isEmpty() && p.unlike.isEmpty() && p.unstar.isEmpty())
    }

    @Test
    fun aStarOnTheServerBecomesALike() {
        val p = plan(liked = emptySet(), starred = setOf("sb"), synced = emptySet())
        assertEquals(setOf("b"), p.like)
        assertEquals(setOf("sb"), p.synced)
        assertTrue(p.star.isEmpty() && p.unstar.isEmpty() && p.unlike.isEmpty())
    }

    @Test
    fun anUnlikeOnThePhoneUnstarsTheServerCopy() {
        val p = plan(liked = emptySet(), starred = setOf("sa"), synced = setOf("sa"))
        assertEquals(setOf("sa"), p.unstar)
        assertTrue(p.synced.isEmpty())
        assertTrue(p.like.isEmpty())
    }

    @Test
    fun anUnstarOnTheServerUnlikesOnThePhone() {
        val p = plan(liked = setOf("a"), starred = emptySet(), synced = setOf("sa"))
        assertEquals(setOf("a"), p.unlike)
        assertTrue(p.synced.isEmpty())
        assertTrue(p.star.isEmpty())
    }

    @Test
    fun addedOnBothSidesIsSimplyAgreed() {
        val p = plan(liked = setOf("c"), starred = setOf("sc"), synced = emptySet())
        assertEquals(StarPlan(synced = setOf("sc")), p)
    }

    @Test
    fun removedOnBothSidesIsForgotten() {
        val p = plan(liked = emptySet(), starred = emptySet(), synced = setOf("sc"))
        assertEquals(StarPlan(), p)
    }

    @Test
    fun likesMadeOfflineArePushedAtTheNextSync() {
        // Liked b and d without a connection; the server still has a starred.
        val p = plan(liked = setOf("a", "b", "d"), starred = setOf("sa"), synced = setOf("sa"))
        assertEquals(setOf("sb", "sd"), p.star)
        assertEquals(setOf("sa", "sb", "sd"), p.synced)
    }

    @Test
    fun unlikesMadeOfflineWinOverTheServersOldStar() {
        val p = plan(liked = setOf("a"), starred = setOf("sa", "sb"), synced = setOf("sa", "sb"))
        assertEquals(setOf("sb"), p.unstar)
        assertEquals(setOf("sa"), p.synced)
        assertTrue(p.like.isEmpty())
    }

    @Test
    fun changesOnBothSidesAtOnceAllLand() {
        val p = plan(
            liked = setOf("a", "c"),
            starred = setOf("sb", "sd"),
            synced = setOf("sb", "sc"),
        )
        // a liked here, b unliked here, c unstarred there, d starred there.
        assertEquals(setOf("sa"), p.star)
        assertEquals(setOf("sb"), p.unstar)
        assertEquals(setOf("d"), p.like)
        assertEquals(setOf("c"), p.unlike)
        assertEquals(setOf("sa", "sd"), p.synced)
    }

    @Test
    fun aSongThatLostItsServerCopyIsLeftAlone() {
        // The server no longer has d. Its like stays, nothing is sent, and its
        // record is kept in case the copy comes back.
        val p = plan(liked = setOf("d"), starred = emptySet(), synced = setOf("sd"), from = copies.take(3))
        assertEquals(StarPlan(synced = setOf("sd")), p)
    }

    @Test
    fun aReturningCopyPicksUpWhereItLeftOff() {
        // d came back while unliked on the phone: the phone's removal wins.
        val p = plan(liked = emptySet(), starred = setOf("sd"), synced = setOf("sd"))
        assertEquals(setOf("sd"), p.unstar)
    }

    @Test
    fun aLibrarySongWithTwoServerCopiesStarsBoth() {
        val two = listOf(ServerCopy("a", "sa1"), ServerCopy("a", "sa2"))
        val p = plan(liked = setOf("a"), starred = emptySet(), synced = emptySet(), from = two)
        assertEquals(setOf("sa1", "sa2"), p.star)
    }

    @Test
    fun oneStarredCopyIsEnoughForALike() {
        val two = listOf(ServerCopy("a", "sa1"), ServerCopy("a", "sa2"))
        val p = plan(liked = emptySet(), starred = setOf("sa2"), synced = emptySet(), from = two)
        assertEquals(setOf("a"), p.like)
        assertEquals(setOf("sa2"), p.synced)
    }

    @Test
    fun songsOnlyOnThePhoneAreNotInvolved() {
        val p = plan(liked = setOf("phone-only"), starred = emptySet(), synced = emptySet())
        assertEquals(StarPlan(), p)
    }

    @Test
    fun aStarThatFailedIsTriedAgain() {
        val first = plan(liked = setOf("a"), starred = emptySet(), synced = emptySet())
        val record = first.settled(starFailed = setOf("sa"), unstarFailed = emptySet())
        assertTrue(record.isEmpty())
        assertEquals(setOf("sa"), plan(liked = setOf("a"), starred = emptySet(), synced = record).star)
    }

    @Test
    fun anUnstarThatFailedIsTriedAgain() {
        val first = plan(liked = emptySet(), starred = setOf("sa"), synced = setOf("sa"))
        val record = first.settled(starFailed = emptySet(), unstarFailed = setOf("sa"))
        assertEquals(setOf("sa"), record)
        assertEquals(setOf("sa"), plan(liked = emptySet(), starred = setOf("sa"), synced = record).unstar)
    }

    @Test
    fun serverTimesParse() {
        assertEquals(1_790_300_699_636, parseServerTime("2026-09-25T01:44:59.636933547Z"))
        assertNull(parseServerTime("yesterday"))
        assertNull(parseServerTime(null))
    }
}

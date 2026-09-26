package app.winters.octo.listening

import app.winters.octo.catalog.RatingCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingsTest {
    private fun copy(song: String, server: String, rating: Int = 0) = RatingCopy(song, server, rating)

    @Test
    fun bothSidesAgreeingChangesNothing() {
        val plan = reconcileRatings(listOf(copy("t1", "s1", 4)), local = mapOf("t1" to 4), synced = mapOf("s1" to 4))
        assertEquals(RatingPlan(synced = mapOf("s1" to 4)), plan)
    }

    @Test
    fun aRatingMadeOnThePhoneIsSent() {
        val plan = reconcileRatings(listOf(copy("t1", "s1", 2)), local = mapOf("t1" to 5), synced = mapOf("s1" to 2))
        assertEquals(mapOf("t1" to RatingSend(listOf("s1"), 5)), plan.send)
        assertTrue(plan.adopt.isEmpty())
        assertEquals(mapOf("s1" to 5), plan.synced)
    }

    @Test
    fun aRatingClearedOnThePhoneIsClearedOnTheServer() {
        val plan = reconcileRatings(listOf(copy("t1", "s1", 3)), local = emptyMap(), synced = mapOf("s1" to 3))
        assertEquals(mapOf("t1" to RatingSend(listOf("s1"), 0)), plan.send)
        assertTrue(plan.synced.isEmpty())
    }

    @Test
    fun aRatingMadeOnTheServerComesToThePhone() {
        val plan = reconcileRatings(listOf(copy("t1", "s1", 4)), local = mapOf("t1" to 2), synced = mapOf("s1" to 2))
        assertTrue(plan.send.isEmpty())
        assertEquals(mapOf("t1" to 4), plan.adopt)
        assertEquals(mapOf("s1" to 4), plan.synced)
    }

    @Test
    fun aRatingClearedOnTheServerIsClearedOnThePhone() {
        val plan = reconcileRatings(listOf(copy("t1", "s1", 0)), local = mapOf("t1" to 3), synced = mapOf("s1" to 3))
        assertEquals(mapOf("t1" to 0), plan.adopt)
        assertTrue(plan.synced.isEmpty())
    }

    @Test
    fun whenBothChangedThePhoneWins() {
        val plan = reconcileRatings(listOf(copy("t1", "s1", 1)), local = mapOf("t1" to 5), synced = mapOf("s1" to 3))
        assertEquals(mapOf("t1" to RatingSend(listOf("s1"), 5)), plan.send)
        assertTrue(plan.adopt.isEmpty())
    }

    @Test
    fun beforeEverSyncingEachSideFillsTheOthersGaps() {
        val plan = reconcileRatings(
            listOf(copy("t1", "s1", 0), copy("t2", "s2", 4), copy("t3", "s3", 0)),
            local = mapOf("t1" to 3),
            synced = emptyMap(),
        )
        assertEquals(mapOf("t1" to RatingSend(listOf("s1"), 3)), plan.send)
        assertEquals(mapOf("t2" to 4), plan.adopt)
        // Songs rated nowhere leave no record.
        assertEquals(mapOf("s1" to 3, "s2" to 4), plan.synced)
    }

    @Test
    fun everyServerCopyOfASongIsKeptTheSame() {
        val copies = listOf(copy("t1", "b", 3), copy("t1", "a", 3))
        // Rated 5 elsewhere on one copy only: the phone and the other copy follow.
        val plan = reconcileRatings(listOf(copy("t1", "b", 5), copy("t1", "a", 3)), local = mapOf("t1" to 3), synced = mapOf("a" to 3, "b" to 3))
        assertEquals(mapOf("t1" to RatingSend(listOf("a", "b"), 5)), plan.send)
        assertEquals(mapOf("t1" to 5), plan.adopt)
        assertEquals(mapOf("a" to 5, "b" to 5), plan.synced)
        assertEquals(RatingPlan(synced = mapOf("a" to 3, "b" to 3)), reconcileRatings(copies, mapOf("t1" to 3), mapOf("a" to 3, "b" to 3)))
    }

    @Test
    fun aSongThatLostItsServerCopyKeepsItsRecord() {
        val plan = reconcileRatings(emptyList(), local = mapOf("t1" to 4), synced = mapOf("gone" to 4))
        assertEquals(mapOf("gone" to 4), plan.synced)
    }

    @Test
    fun aRatingThatFailedToSendIsTriedAgainNextTime() {
        val before = mapOf("s1" to 2)
        val plan = reconcileRatings(listOf(copy("t1", "s1", 2), copy("t2", "s2", 0)), mapOf("t1" to 5, "t2" to 1), before)
        val record = plan.settled(failed = setOf("t1", "t2"), before = before)
        // The old record stays, and a song never agreed on has none.
        assertEquals(mapOf("s1" to 2), record)
        val again = reconcileRatings(listOf(copy("t1", "s1", 2), copy("t2", "s2", 0)), mapOf("t1" to 5, "t2" to 1), record)
        assertEquals(setOf("t1", "t2"), again.send.keys)
    }

    @Test
    fun theRecordSurvivesStorage() {
        val record = mapOf("s1" to 5, "a b" to 1)
        assertEquals(record, decodeRatings(encodeRatings(record)))
        assertEquals(emptyMap<String, Int>(), decodeRatings(setOf("x", "9 s1", "0 s2", "3 ")))
        assertTrue(encodeRatings(mapOf("s1" to 0)).isEmpty())
    }

    @Test
    fun ratingsStayInRange() {
        assertEquals(0, cleanRating(null))
        assertEquals(0, cleanRating(-1))
        assertEquals(0, cleanRating(6))
        assertEquals(3, cleanRating(3))
    }
}

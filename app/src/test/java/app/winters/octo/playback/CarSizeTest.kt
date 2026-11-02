package app.winters.octo.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CarSizeTest {
    private fun item(id: String, title: String = "t", extras: Bundle? = null) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtworkUri(Uri.parse("content://a/512/$id")).setExtras(extras).build())
        .build()

    private val rows = CarSplitRows(
        letter = { node, _, _, _ -> item(carId(node)) },
        part = { node, _, _, _, _ -> item(carId(node)) },
    )

    @Test
    fun aLetterSaysHowManyItHoldsAndTakesItsColorsFromItsFirstCovers() {
        val keys = (1..400).map { n -> (if (n <= 300) "a" else "b") + "name %04d".format(n) }
        val got = mutableMapOf<String, Pair<List<String>, String?>>()
        val asked = CarSplitRows(
            letter = { node, _, covers, count -> got[node.heading] = covers to count; item(carId(node)) },
            part = { node, _, _, _, _ -> item(carId(node)) },
        )
        // Two albums share each cover, and the first has none.
        val list = CarListing(keys.size, carLetters(keys), CarStyle.Grid, { "$it albums" }, { i -> if (i == 0) null else "cover-${i / 2}" }) {
            item("album:${keys[it]}", "x".repeat(60))
        }
        carListAnswer(CarNode.Albums, list, asked, budget = 20_000)
        assertEquals(listOf("cover-0", "cover-1", "cover-2", "cover-3") to "300 albums", got["A"])
        assertEquals(listOf("cover-150", "cover-151", "cover-152", "cover-153") to "100 albums", got["B"])
    }

    @Test
    fun aPartOpensItsItemsInTheListsStyleWithItsOwnCovers() {
        val keys = (1..2_000).map { "album %04d".format(it) }
        val got = mutableListOf<Triple<IntRange, CarStyle?, List<String>>>()
        val asked = CarSplitRows(
            letter = { node, _, _, _ -> item(carId(node)) },
            part = { node, range, _, style, covers -> got += Triple(range, style, covers); item(carId(node)) },
        )
        val list = CarListing(keys.size, carLetters(keys), CarStyle.Grid, coverOf = { "cover-$it" }) { item("album:${keys[it]}") }
        carListAnswer(CarNode.Albums, list, asked, budget = 20_000)
        assertTrue(got.size > 1)
        got.forEach { (range, style, covers) ->
            assertEquals(CarStyle.Grid, style)
            assertEquals(range.take(SPLIT_COVERS).map { "cover-$it" }, covers)
        }
    }

    @Test
    fun namesFileUnderTheirFirstLetterOrHash() {
        assertEquals(listOf("A", "A", "#", "Z", "#"), carLetters(listOf("album 1", "abba", "1999", "zed", "東京")))
        assertNull(carLetters(null))
    }

    @Test
    fun aBigListByNameOpensToItsLettersBuildingOnlyWhatEachNeeds() {
        val keys = (1..3_000).map { n -> listOf("a", "b", "c", "1")[n % 4] + "name %05d".format(n) }.sorted()
        var built = 0
        val list = CarListing(keys.size, carLetters(keys)) { built++; item("album:${keys[it]}", "x".repeat(60)) }
        val budget = 20_000
        val top = carListAnswer(CarNode.Albums, list, rows, budget)
        assertEquals(listOf("#", "A", "B", "C"), top.map { (parseCarId(it.mediaId) as CarNode.Heading).heading })
        assertTrue(built < keys.size)
        val reached = mutableListOf<String>()
        for (row in top) {
            val heading = parseCarId(row.mediaId) as CarNode.Heading
            built = 0
            val answer = carLetterAnswer(heading, list, rows, budget)
            assertEquals(keys.count { carLetters(listOf(it))!!.single() == heading.heading }, built)
            for (entry in answer) {
                val part = parseCarId(entry.mediaId) as? CarNode.Part
                if (part == null) reached += entry.mediaId else reached += carPartItems(part.index, list.under(heading.heading), budget).map { it.mediaId }
            }
        }
        assertEquals(keys.map { "album:$it" }, reached)
    }

    @Test
    fun aBigListUnderOneLetterOpensStraightToItsParts() {
        val keys = (1..2_000).map { "album %04d".format(it) }
        val list = CarListing(keys.size, carLetters(keys)) { item("album:${keys[it]}") }
        val top = carListAnswer(CarNode.Albums, list, rows, budget = 20_000)
        assertTrue(top.size > 1 && top.all { parseCarId(it.mediaId) is CarNode.Part })
        val reached = top.flatMap { row -> carPartItems((parseCarId(row.mediaId) as CarNode.Part).index, list.all(), 20_000) }
        assertEquals(keys.map { "album:$it" }, reached.map { it.mediaId })
    }

    @Test
    fun aListThatFitsIsSentWhole() {
        val list = CarListing.of(List(10) { item("song:$it") })
        assertEquals(list.all().map { it.mediaId }, carListAnswer(CarNode.Liked, list, rows).map { it.mediaId })
    }

    @Test
    fun anItemCostsMoreForEveryTextAndExtra() {
        val plain = carBytes(item("a"))
        assertTrue(carBytes(item("a", "a much longer title than before")) > plain)
        assertTrue(carBytes(item("a", extras = Bundle().apply { putLong("k", 1) })) > plain)
        assertTrue(carBytes(item("a".repeat(50))) > plain)
    }

    @Test
    fun partsHoldEveryItemOnceInOrderAndFit() {
        val sizes = List(1000) { 300 + (it % 7) * 40 }
        val parts = carParts(sizes, budget = 20_000)
        assertEquals((0 until 1000).toList(), parts.flatMap { it.toList() })
        assertTrue(parts.all { range -> range.sumOf { sizes[it] } <= 20_000 })
        assertEquals(parts, carParts(sizes, budget = 20_000))
    }

    @Test
    fun anItemBiggerThanThePartGetsOneOfItsOwn() {
        val parts = carParts(listOf(10, 500, 10), budget = 100)
        assertEquals(listOf(0..0, 1..1, 2..2), parts)
    }

    @Test
    fun aLongTextIsCutAtAWordAndNeverMidCharacter() {
        val words = "word ".repeat(40)
        val cut = carText(words)!!
        assertTrue(cut.length <= CAR_TEXT_MAX)
        assertTrue(cut.endsWith("word"))
        val emoji = "🎧".repeat(80)
        val cutEmoji = carText(emoji)!!
        assertTrue(cutEmoji.length <= CAR_TEXT_MAX)
        assertEquals(0, cutEmoji.length % 2)
        assertEquals("short", carText("short"))
    }

    @Test
    fun aPageIsNoMoreThanAskedFor() {
        val items = (1..12).toList()
        assertEquals((1..5).toList(), pageOf(items, 0, 5))
        assertEquals((11..12).toList(), pageOf(items, 2, 5))
        assertTrue(pageOf(items, 3, 5).isEmpty())
        assertEquals(items, pageOf(items, 0, Int.MAX_VALUE))
        assertEquals(items, pageOf(items, 1, Int.MAX_VALUE).let { if (it.isEmpty()) items else it })
    }
}

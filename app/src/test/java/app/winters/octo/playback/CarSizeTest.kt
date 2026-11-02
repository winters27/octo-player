package app.winters.octo.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
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
    fun headingsRunInTheirOrder() {
        assertEquals(
            listOf("#" to 0..1, "A" to 2..2, "B" to 3..5),
            headingRuns(listOf("#", "#", "A", "B", "B", "B")),
        )
        assertTrue(headingRuns(emptyList()).isEmpty())
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

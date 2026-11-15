package app.winters.octo.versions

import app.winters.octo.subsonic.StreamVersionChanges
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

// The change feed's cursor rules against the shared file's cases.
class StreamVersionCursorTest {
    private fun cursor(o: JsonObject): StreamVersionCursor = StreamVersionCursor(o.text("epoch")!!, o["seq"]!!.jsonPrimitive.long)

    @Test
    fun everyFeedCaseOfTheSharedFile() {
        versionsFixture["feed"]!!.jsonArray.forEach { case ->
            val c = case.jsonObject
            val name = c.text("name")
            val stored = (c["stored"] as? JsonObject)?.let(::cursor)
            val answer = versionsJson.decodeFromJsonElement(StreamVersionChanges.serializer(), c["answer"]!!)
            val plan = planFeed(stored, answer)
            val expect = c["expect"]!!.jsonObject
            assertEquals(name, expect["dropAll"]!!.jsonPrimitive.boolean, plan.dropAll)
            assertEquals(name, expect["dropIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(), plan.dropIds)
            assertEquals(name, cursor(expect["next"]!!.jsonObject), plan.next)
        }
    }

    // The cursor is kept as JSON on a device.
    @Test
    fun aCursorReadsBackAsItWasKept() {
        val kept = StreamVersionCursor("e1", 7)
        val text = versionsJson.encodeToString(StreamVersionCursor.serializer(), kept)
        assertEquals("""{"epoch":"e1","seq":7}""", text)
        assertEquals(kept, versionsJson.decodeFromString(StreamVersionCursor.serializer(), text))
    }
}

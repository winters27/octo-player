package app.winters.octo.versions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// The sheet's states against the shared file's cases, the ones the iPhone
// app's sheet runs too.
class StreamVersionsSheetStateTest {
    private fun event(step: JsonObject): StreamVersionsEvent = when (val name = step.text("event")) {
        "open" -> StreamVersionsEvent.Open(
            VersionsSong(step.text("entry")!!, step.text("song")!!, step.text("title")!!, step.text("artist")!!),
        )
        "listed" -> StreamVersionsEvent.Listed(fixtureAnswer(step.text("answer")!!))
        "listFailed" -> StreamVersionsEvent.ListFailed(step.text("message"))
        "retry" -> StreamVersionsEvent.Retry
        "pick" -> StreamVersionsEvent.Pick(step.text("video")!!, step.text("entry"), step.text("song"))
        "undo" -> StreamVersionsEvent.Undo(step.text("entry"), step.text("song"))
        "picked" -> StreamVersionsEvent.Picked(fixtureAnswer(step.text("answer")!!))
        "pickFailed" -> StreamVersionsEvent.PickFailed(step.text("message")!!)
        "entryChanged" -> StreamVersionsEvent.EntryChanged(step.text("entry"))
        "close" -> StreamVersionsEvent.Close
        else -> error("no event $name")
    }

    // What a case checks of a state, by the shared file's names.
    private fun seen(state: StreamVersionsSheetState): Map<String, JsonElement> {
        val shown = state as? StreamVersionsSheetState.Shown
        val name = when (state) {
            StreamVersionsSheetState.Closed -> "closed"
            is StreamVersionsSheetState.Looking -> "looking"
            is StreamVersionsSheetState.CouldNotReach -> "couldNotReach"
            is StreamVersionsSheetState.Unavailable -> "unavailable"
            is StreamVersionsSheetState.Shown -> "shown"
        }
        return mapOf(
            "state" to JsonPrimitive(name),
            "message" to JsonPrimitive(StreamVersionWords.stateLine(state)),
            "rows" to JsonArray(shown?.rows.orEmpty().map { JsonPrimitive(it.videoId) }),
            "playing" to JsonPrimitive(shown?.playing?.videoId),
            "checking" to JsonPrimitive(shown?.checking),
            "refused" to JsonPrimitive(shown?.refused),
            "refusal" to JsonPrimitive(shown?.refusal),
            "canUndo" to JsonPrimitive(shown?.canUndo ?: false),
            "empty" to JsonPrimitive(shown?.emptyText),
        )
    }

    @Test
    fun everyCaseOfTheSharedFile() {
        val cases = versionsFixture["reducer"]!!.jsonArray
        assertTrue(cases.size >= 16)
        cases.forEach { case ->
            val name = case.jsonObject.text("name")
            var state: StreamVersionsSheetState = StreamVersionsSheetState.Closed
            case.jsonObject["steps"]!!.jsonArray.forEachIndexed { at, step ->
                state = state.on(event(step.jsonObject))
                val seen = seen(state)
                step.jsonObject["expect"]!!.jsonObject.forEach { (key, want) ->
                    assertEquals("$name, step ${at + 1}, $key", want, seen[key])
                }
            }
        }
    }

    // A pick that does not apply hands back the very same state, which is
    // how a view model tells it from one that started.
    @Test
    fun anEventThatDoesNotApplyLeavesTheSameState() {
        val song = VersionsSong("e1", "X", "Song", "Artist")
        val shown = StreamVersionsSheetState.Closed
            .on(StreamVersionsEvent.Open(song))
            .on(StreamVersionsEvent.Listed(fixtureAnswer("octo")))
        assertSame(shown, shown.on(StreamVersionsEvent.Pick("v1", "e1", "X")))
        assertSame(shown, shown.on(StreamVersionsEvent.Pick("v2", "e2", "X")))
        assertSame(shown, shown.on(StreamVersionsEvent.Undo("e1", "X")))
        assertSame(shown, shown.on(StreamVersionsEvent.Retry))
        val checking = shown.on(StreamVersionsEvent.Pick("v2", "e1", "X"))
        assertSame(checking, checking.on(StreamVersionsEvent.Pick("v3", "e1", "X")))
    }
}

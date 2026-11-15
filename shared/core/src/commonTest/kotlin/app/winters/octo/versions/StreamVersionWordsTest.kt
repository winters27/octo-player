package app.winters.octo.versions

import app.winters.octo.subsonic.StreamVersionPlaying
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// Every stream version word against fixtures/stream-versions-words.json,
// the file the iPhone app's words are checked against too.
class StreamVersionWordsTest {
    private fun kotlinWords(): Map<String, String> = mapOf(
        "menu.row" to StreamVersionWords.MENU_ROW,
        "sheet.title" to StreamVersionWords.TITLE,
        "sheet.done" to StreamVersionWords.DONE,
        "section.playing" to StreamVersionWords.PLAYING_NOW,
        "section.others" to StreamVersionWords.OTHER_VERSIONS,
        "picked.mine" to StreamVersionWords.YOUR_PICK,
        "picked.by" to StreamVersionWords.pickedBy("{name}"),
        "picked.someone" to StreamVersionWords.PICKED_BY_SOMEONE,
        "picked.earlier" to StreamVersionWords.PICK_FROM_BEFORE,
        "picked.octo" to StreamVersionWords.OCTOS_PICK,
        "nextStart" to StreamVersionWords.NEXT_START,
        "kind.release" to StreamVersionWords.KIND_RELEASE,
        "kind.musicVideo" to StreamVersionWords.KIND_MUSIC_VIDEO,
        "kind.video" to StreamVersionWords.KIND_VIDEO,
        "looking" to StreamVersionWords.LOOKING,
        "couldNotReach" to StreamVersionWords.COULD_NOT_REACH,
        "serverUnreachable" to StreamVersionWords.SERVER_UNREACHABLE,
        "tryAgain" to StreamVersionWords.TRY_AGAIN,
        "nothing" to StreamVersionWords.NOTHING,
        "missed.youtubeMusic" to StreamVersionWords.MISSED_YOUTUBE_MUSIC_WORDS,
        "missed.youtube" to StreamVersionWords.MISSED_YOUTUBE_WORDS,
        "checking" to StreamVersionWords.CHECKING,
        "playsFrom.download" to StreamVersionWords.PLAYS_FROM_DOWNLOAD_WORDS,
        "playsFrom.file" to StreamVersionWords.PLAYS_FROM_FILE_WORDS,
        "busy" to StreamVersionWords.BUSY,
        "footer.note" to StreamVersionWords.NOTE,
        "footer.undo" to StreamVersionWords.GO_BACK,
        "moreBelow" to StreamVersionWords.MORE_BELOW,
        "pick.unreachable" to StreamVersionWords.PICK_UNREACHABLE,
        "song.line" to StreamVersionWords.songLine("{title}", "{artist}"),
    )

    @Test
    fun everyWordMatchesTheSharedFile() {
        val fixture = versionsFixture["words"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(fixture, kotlinWords())
    }

    // A streamed outside song is the song itself.
    @Test
    fun noWordSaysPreviewOrHasADashOrACurlyQuote() {
        kotlinWords().values.forEach { words ->
            assertFalse(words, words.contains("preview", ignoreCase = true))
            assertFalse(words, words.any { it.code in setOf(0x2014, 0x2013, 0x2018, 0x2019, 0x201C, 0x201D) })
        }
    }

    @Test
    fun songLinesLengthsAndRowDetailsMatchTheSharedFile() {
        versionsFixture["songLines"]!!.jsonArray.forEach { case ->
            val c = case.jsonObject
            assertEquals(c.text("text"), StreamVersionWords.songLine(c.text("title")!!, c.text("artist")!!))
        }
        versionsFixture["lengths"]!!.jsonArray.forEach { case ->
            val c = case.jsonObject
            assertEquals(c.text("text"), StreamVersionWords.length(c["seconds"]!!.jsonPrimitive.int))
        }
        versionsFixture["rowDetails"]!!.jsonArray.forEach { case ->
            val c = case.jsonObject
            assertEquals(
                c.text("text"),
                StreamVersionWords.rowDetail(c.text("channel"), c["duration"]!!.jsonPrimitive.intOrNull, c.text("kind")),
            )
        }
    }

    @Test
    fun playingNotesMatchTheSharedFile() {
        versionsFixture["playingNotes"]!!.jsonArray.forEach { case ->
            val c = case.jsonObject
            val playing = StreamVersionPlaying(
                videoId = c.text("videoId")!!,
                picked = c.text("picked")!!,
                pickedBy = c.text("pickedBy"),
                mine = c["mine"]!!.jsonPrimitive.boolean,
            )
            assertEquals(c.text("text"), StreamVersionWords.playingNote(playing, c.text("heard")))
        }
    }
}

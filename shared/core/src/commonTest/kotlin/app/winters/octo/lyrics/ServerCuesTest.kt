package app.winters.octo.lyrics

import app.winters.octo.lyrics.engine.LyricMapper
import app.winters.octo.subsonic.Cue
import app.winters.octo.subsonic.CueLine
import app.winters.octo.subsonic.LyricsLine
import app.winters.octo.subsonic.LyricsList
import app.winters.octo.subsonic.StructuredLyrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The server's word cues, exactly as an Octo server sends them, through to
// the words the flowing view lights one by one.
class ServerCuesTest {
    // "Café " is bytes 0..5 (é is two); "日本語" is 0..8, three bytes a letter.
    private val answer = """
        {"subsonic-response":{"status":"ok","version":"1.16.1","lyricsList":{"structuredLyrics":[
          {"lang":"xxx","synced":true,"offset":0,"kind":"main",
           "line":[{"start":1000,"value":"Café au lait"},{"start":4000,"value":"日本語の歌"},{"start":7000,"value":"Plain line"}],
           "cueLine":[
             {"index":0,"start":1000,"end":3500,"value":"Café au lait","cue":[
               {"start":1000,"end":1500,"byteStart":0,"byteEnd":5,"value":"Café "},
               {"start":1500,"end":2500,"byteStart":6,"byteEnd":8,"value":"au "},
               {"start":2500,"end":3500,"byteStart":9,"byteEnd":12,"value":"lait"}]},
             {"index":1,"start":4000,"end":6500,"value":"日本語の歌","cue":[
               {"start":4000,"end":5000,"byteStart":0,"byteEnd":8,"value":"日本語"},
               {"start":5000,"end":5500,"byteStart":9,"byteEnd":11,"value":"の"},
               {"start":5500,"end":6500,"byteStart":12,"byteEnd":14,"value":"歌"}]}]}]}}}
    """.trimIndent()

    private fun parsed(): Lyrics {
        val json = Json { ignoreUnknownKeys = true }
        val list = json.parseToJsonElement(answer).jsonObject.getValue("subsonic-response").jsonObject.getValue("lyricsList")
        return requireNotNull(serverLyrics(json.decodeFromJsonElement(LyricsList.serializer(), list).structuredLyrics, "fr"))
    }

    @Test
    fun theFlowingViewLightsEachWord() {
        val lines = LyricMapper.map(parsed()).filterNot { it.isInterlude }
        val cafe = lines[0]
        assertFalse(cafe.isLineTimed)
        assertEquals(listOf("Café", "au", "lait"), cafe.words.map { it.text })
        assertEquals(listOf(1.0, 1.5, 2.5), cafe.words.map { it.start })
        assertEquals(listOf(1.5, 2.5, 3.5), cafe.words.map { it.end })
        assertEquals(listOf(true, true, true), cafe.words.map { it.trailingSpace })

        // No spaces between the words of a Japanese line, so none are drawn.
        val song = lines[1]
        assertFalse(song.isLineTimed)
        assertEquals(listOf("日本語", "の", "歌"), song.words.map { it.text })
        assertEquals(listOf(false, false, true), song.words.map { it.trailingSpace })

        val plain = lines[2]
        assertTrue(plain.isLineTimed)
        assertEquals("Plain line", plain.text)
    }

    @Test
    fun spacesACueLineStartsWithDoNotMoveItsWords() {
        val line = CueLine(
            index = 0,
            start = 0,
            value = "  Café noir",
            cue = listOf(
                Cue(0, 500, "  Café ", 0, 7),
                Cue(500, 900, "noir", 8, 11),
            ),
        )
        val lyrics = requireNotNull(
            serverLyrics(
                listOf(
                    StructuredLyrics(
                        synced = true,
                        line = listOf(LyricsLine(0, "  Café noir")),
                        cueLine = listOf(line),
                    ),
                ),
                null,
            ),
        )
        val only = lyrics.lines.single()
        assertEquals("Café noir", only.text)
        assertEquals(listOf("Café ", "noir"), only.words.map { only.text.substring(it.from, it.to) })
    }
}

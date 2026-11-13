package app.winters.octo.desktop.settings

import app.winters.octo.playback.BetweenSongs
import app.winters.octo.playback.FadeCurve
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// The Between songs choice in the settings file: a new install, a file an
// older Octo wrote (its one crossfade field and the switches beside it),
// the fields an older Octo reads written from the choice, and a first
// change to a file that never chose.
class BetweenSongsSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file() = File(folder.root, "octo/${SettingsStore.FILE_NAME}")

    // A file as 1.12 wrote it: every playback field, with these changed.
    private fun older(crossfadeSeconds: Int = 0, smart: Boolean? = true, sweeps: Boolean = true, tempo: Boolean = false, curve: String = "EqualPower") {
        val smartField = smart?.let { "\"smartTransitions\": $it," }.orEmpty()
        file().apply { parentFile.mkdirs() }.writeText(
            """{"sidePanel": "queue", "playback": {"volume": 0.5, "crossfadeSeconds": $crossfadeSeconds, $smartField "filterSweeps": $sweeps, "matchTempo": $tempo, "crossfadeCurve": "$curve", "speed": 1.0}}""",
        )
    }

    private fun written(): JsonObject = Json.parseToJsonElement(file().readText()).jsonObject

    private fun JsonObject.playback(): JsonObject = getValue("playback").jsonObject

    @Test
    fun aNewInstallIsAutomixWithMatchTempoOnAndWritesTheChoiceWithItsMirrors() {
        val store = SettingsStore(file())
        val playback = store.current.playback
        assertEquals(BetweenSongs.Automix, playback.betweenSongs)
        assertTrue("Match tempo on", playback.matchTempo)
        assertEquals(8, playback.crossfadeLength)
        assertFalse("nothing is written until something changes", file().exists())
        store.update { it.copy(sidePanel = "lyrics") }
        val json = written()
        assertEquals(2, json.getValue("settingsVersion").jsonPrimitive.int)
        assertEquals("Automix", json.playback().getValue("betweenSongs").jsonPrimitive.content)
        assertTrue(json.playback().getValue("matchTempo").jsonPrimitive.boolean)
        // What an older Octo reads: crossfade on at 8 s with smart transitions.
        assertEquals(8, json.playback().getValue("crossfadeSeconds").jsonPrimitive.int)
        assertTrue(json.playback().getValue("smartTransitions").jsonPrimitive.boolean)
    }

    // Each old file, by what its crossfade settings come to.
    @Test
    fun eachOlderFileGivesItsChoice() {
        class Case(val name: String, val write: () -> Unit, val choice: BetweenSongs, val length: Int, val tempo: Boolean, val untouched: Boolean)
        val cases = listOf(
            Case("never touched", { older() }, BetweenSongs.Automix, 8, tempo = true, untouched = true),
            Case("no playback settings at all", { file().apply { parentFile.mkdirs() }.writeText("""{"sidePanel": "queue"}""") }, BetweenSongs.Automix, 8, tempo = true, untouched = true),
            Case("no smart transitions field", { older(smart = null) }, BetweenSongs.Automix, 8, tempo = true, untouched = true),
            Case("off with Match tempo on", { older(tempo = true) }, BetweenSongs.Gapless, 8, tempo = true, untouched = false),
            Case("off with Filter sweeps off", { older(sweeps = false) }, BetweenSongs.Gapless, 8, tempo = false, untouched = false),
            Case("off with smart transitions off", { older(smart = false) }, BetweenSongs.Gapless, 8, tempo = false, untouched = false),
            Case("off with another curve", { older(curve = "Linear") }, BetweenSongs.Gapless, 8, tempo = false, untouched = false),
            Case("on with smart transitions", { older(crossfadeSeconds = 6) }, BetweenSongs.Automix, 6, tempo = true, untouched = false),
            Case("on with no smart transitions field", { older(crossfadeSeconds = 10, smart = null) }, BetweenSongs.Automix, 10, tempo = true, untouched = false),
            Case("on with smart transitions off", { older(crossfadeSeconds = 6, smart = false) }, BetweenSongs.Crossfade, 6, tempo = false, untouched = false),
            Case("on with smart transitions off and Match tempo on", { older(crossfadeSeconds = 12, smart = false, tempo = true) }, BetweenSongs.Crossfade, 12, tempo = true, untouched = false),
            // 1.12 played 1 as 2 s and anything over 16 as 16 s.
            Case("1 s, smart transitions off", { older(crossfadeSeconds = 1, smart = false) }, BetweenSongs.Crossfade, 2, tempo = false, untouched = false),
            Case("20 s, smart transitions off", { older(crossfadeSeconds = 20, smart = false) }, BetweenSongs.Crossfade, 16, tempo = false, untouched = false),
            Case("1 s, smart transitions on", { older(crossfadeSeconds = 1) }, BetweenSongs.Automix, 2, tempo = true, untouched = false),
            Case("20 s, no smart transitions field", { older(crossfadeSeconds = 20, smart = null) }, BetweenSongs.Automix, 16, tempo = true, untouched = false),
        )
        for (case in cases) {
            file().delete()
            case.write()
            val before = file().readBytes()
            val playback = SettingsStore(file()).current.playback
            assertEquals(case.name, case.choice, playback.betweenSongs)
            assertEquals(case.name, case.length, playback.crossfadeLength)
            assertEquals("${case.name}: Match tempo", case.tempo, playback.matchTempo)
            if (case.untouched) {
                assertArrayEquals("${case.name}: never chose, so nothing is written", before, file().readBytes())
                continue
            }
            // A file that chose stores its choice at once, with the mirrors.
            val json = written()
            assertEquals("${case.name}: written at once", 2, json["settingsVersion"]?.jsonPrimitive?.intOrNull)
            assertEquals(case.name, case.choice.name, json.playback()["betweenSongs"]?.jsonPrimitive?.content)
            assertEquals(case.name, case.length, json.playback()["crossfadeLength"]?.jsonPrimitive?.intOrNull)
            assertEquals("${case.name}: the older field", if (case.choice == BetweenSongs.Gapless) 0 else case.length, json.playback().getValue("crossfadeSeconds").jsonPrimitive.int)
            assertEquals("${case.name}: the older switch", case.choice == BetweenSongs.Automix, json.playback().getValue("smartTransitions").jsonPrimitive.boolean)
            assertEquals(case.name, "queue", json.getValue("sidePanel").jsonPrimitive.content)
            assertEquals("${case.name}: read again the same", playback, SettingsStore(file()).current.playback)
        }
    }

    // A file with the choice is never read as an older one again, even
    // with mirrors that disagree with it.
    @Test
    fun aFileWithTheChoiceKeepsIt() {
        file().apply { parentFile.mkdirs() }.writeText(
            """{"settingsVersion": 2, "playback": {"betweenSongs": "Gapless", "crossfadeLength": 12, "crossfadeSeconds": 6, "smartTransitions": true, "matchTempo": false}}""",
        )
        val store = SettingsStore(file())
        assertEquals(BetweenSongs.Gapless, store.current.playback.betweenSongs)
        assertEquals(12, store.current.playback.crossfadeLength)
        assertFalse(store.current.playback.matchTempo)
        store.update { it.copy(sidePanel = "info") }
        assertEquals(0, written().playback().getValue("crossfadeSeconds").jsonPrimitive.int)
        assertFalse(written().playback().getValue("smartTransitions").jsonPrimitive.boolean)
    }

    // An older Octo opened over this one keeps only the fields it knows,
    // the mirrors among them, and drops the version; read again here, the
    // choice comes back from the mirrors.
    @Test
    fun anOlderOctoWritingOverTheFileKeepsTheChoice() {
        for ((choice, length) in listOf(BetweenSongs.Crossfade to 6, BetweenSongs.Automix to 8, BetweenSongs.Gapless to 8)) {
            file().delete()
            SettingsStore(file()).update { it.copy(sidePanel = "queue", playback = it.playback.copy(betweenSongs = choice, crossfadeLength = length, matchTempo = choice == BetweenSongs.Automix)) }
            val json = written()
            val olderPlayback = JsonObject(json.playback() - setOf("betweenSongs", "crossfadeLength"))
            file().writeText(JsonObject(json - "settingsVersion" + ("playback" to olderPlayback)).toString())
            val read = SettingsStore(file()).current.playback
            assertEquals("$choice", choice, read.betweenSongs)
            if (choice != BetweenSongs.Gapless) assertEquals("$choice", length, read.crossfadeLength)
        }
    }

    // A first change to a file that never chose keeps what the card showed
    // (Automix with Match tempo on), not what its old values would now read
    // as with that change (Filter sweeps off would read as Gapless).
    @Test
    fun aFirstChangeToAFileThatNeverChoseKeepsWhatItShowed() {
        older()
        val store = SettingsStore(file())
        assertEquals(BetweenSongs.Automix, store.current.playback.betweenSongs)
        assertTrue(store.current.playback.matchTempo)
        store.editBetweenSongs(BetweenSongsChange.FilterSweeps(false))
        val read = SettingsStore(file()).current.playback
        assertEquals(BetweenSongs.Automix, read.betweenSongs)
        assertTrue("Match tempo as shown", read.matchTempo)
        assertFalse(read.filterSweeps)
        assertEquals("Automix", written().playback().getValue("betweenSongs").jsonPrimitive.content)
    }

    // Every Between songs change goes through the one setter, with the
    // mirrors written beside it and Crossfade's length kept to 2 to 16.
    @Test
    fun theSetterWritesEachChangeWithItsMirrors() {
        val store = SettingsStore(file())
        store.editBetweenSongs(BetweenSongsChange.Choose(BetweenSongs.Crossfade))
        store.editBetweenSongs(BetweenSongsChange.Length(40))
        store.editBetweenSongs(BetweenSongsChange.Curve(FadeCurve.Linear))
        assertEquals(16, store.current.playback.crossfadeLength)
        assertEquals(16, written().playback().getValue("crossfadeSeconds").jsonPrimitive.int)
        assertFalse(written().playback().getValue("smartTransitions").jsonPrimitive.boolean)
        assertEquals("Linear", written().playback().getValue("crossfadeCurve").jsonPrimitive.content)
        store.editBetweenSongs(BetweenSongsChange.Length(1))
        assertEquals(2, store.current.playback.crossfadeLength)
        store.editBetweenSongs(BetweenSongsChange.Choose(BetweenSongs.Gapless))
        assertEquals("Gapless is off to an older Octo", 0, written().playback().getValue("crossfadeSeconds").jsonPrimitive.int)
        store.editBetweenSongs(BetweenSongsChange.Choose(BetweenSongs.Automix))
        store.editBetweenSongs(BetweenSongsChange.MatchTempo(false))
        assertEquals(2, written().playback().getValue("crossfadeSeconds").jsonPrimitive.int)
        assertTrue(written().playback().getValue("smartTransitions").jsonPrimitive.boolean)
        assertFalse(SettingsStore(file()).current.playback.matchTempo)
    }

    // The raw pre-pass on its own: nothing for a file with a version, and
    // an untouched file's result says so.
    @Test
    fun thePrePassRunsOnlyWithoutAVersion() {
        val versioned = JsonObject(mapOf("settingsVersion" to JsonPrimitive(2)))
        assertEquals(null, migratedBetweenSongs(versioned))
        val old = migratedBetweenSongs(JsonObject(emptyMap()))!!
        assertTrue(old.untouched)
        assertEquals("Automix", old.json.playback().getValue("betweenSongs").jsonPrimitive.content)
    }
}

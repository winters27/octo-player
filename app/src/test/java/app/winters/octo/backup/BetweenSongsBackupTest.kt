package app.winters.octo.backup

import app.winters.octo.playback.BetweenSongs
import app.winters.octo.player.MemoryPrefs
import app.winters.octo.player.OLD_DEFAULTS
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.player.PlayerSettings
import app.winters.octo.player.StreamPrefs
import app.winters.octo.player.keys
import app.winters.octo.player.phoneThatRanAnOlderApp
import app.winters.octo.player.storedPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// The Between songs choice through a backup: the version that has it, the
// choice only when the phone stored one, the mirrors older apps read, and
// restores that leave an untouched phone untouched and a chosen one chosen.
class BetweenSongsBackupTest {
    @get:Rule val folder = TemporaryFolder()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    @After
    fun close() = runBlocking { job.cancelAndJoin() }

    // A phone that chose Crossfade, with Match tempo off.
    private val chosenPhone = mapOf("between_songs" to "Crossfade", "crossfade" to true, "smart_transitions" to false, "match_tempo" to false, "crossfade_seconds" to 5)

    private fun backupOf(player: String) = """{"kind": "octo-settings", "version": 1, "player": $player}"""

    // Restores a backup file's player settings on a phone holding `keys`,
    // as a restore does, and gives the phone back.
    private suspend fun restore(text: String, keys: Map<String, Any> = chosenPhone): MemoryPrefs {
        val backup = (decodeBackup(text) as BackupRead.Read).backup
        val store = MemoryPrefs(storedPrefs(keys))
        PlayerSettings(store).restore(backup.player!!.prefs, backup.streaming ?: StreamPrefs())
        return store
    }

    private suspend fun shown(store: MemoryPrefs): PlayerPrefs = PlayerSettings(store).prefs.first()

    // The backup file a phone holding these settings writes.
    private suspend fun backupFrom(store: MemoryPrefs): String = encodeBackup(Backup(player = PlayerBackup(PlayerSettings(store).snapshot().first)))

    private val untouchedRemoves = listOf("between_songs", "crossfade", "smart_transitions", "match_tempo")

    @Test
    fun anUntouchedBackupRestoresUntouched() = runBlocking {
        val legacy = backupOf("""{"crossfade": false, "crossfadeSeconds": 8, "smartTransitions": true, "filterSweeps": true, "matchTempo": false, "crossfadeCurve": "EqualPower"}""")
        val newer = backupOf("""{"settingsVersion": 2, "crossfade": true, "smartTransitions": true, "crossfadeSeconds": 8, "matchTempo": true}""")
        for (text in listOf(legacy, newer)) {
            val phone = restore(text)
            val keys = phone.keys()
            untouchedRemoves.forEach { assertFalse("$it after $text", it in keys) }
            val after = shown(phone)
            assertEquals(BetweenSongs.Automix, after.betweenSongs)
            assertFalse(after.betweenSongsStored)
            assertTrue(after.matchTempo)
        }
    }

    @Test
    fun aCrossfadeBackupRestoresCrossfade() = runBlocking {
        val legacy = backupOf("""{"crossfade": true, "crossfadeSeconds": 6, "smartTransitions": false, "matchTempo": false}""")
        val newer = backupOf("""{"settingsVersion": 2, "betweenSongs": "Crossfade", "crossfade": true, "smartTransitions": false, "crossfadeSeconds": 6, "matchTempo": false}""")
        for (text in listOf(legacy, newer)) {
            val phone = restore(text, keys = emptyMap())
            val after = shown(phone)
            assertEquals(BetweenSongs.Crossfade, after.betweenSongs)
            assertTrue(after.betweenSongsStored)
            assertEquals(6, after.crossfadeSeconds)
            assertFalse(after.matchTempo)
            assertEquals(mapOf("between_songs" to "Crossfade", "crossfade" to true, "smart_transitions" to false), phone.keys().filterKeys { it in untouchedRemoves - "match_tempo" })
        }
    }

    // What is written: the version, the choice only when stored, and the
    // mirrors an older app reads for every phone.
    @Test
    fun aBackupCarriesTheVersionTheStoredChoiceAndTheMirrors() = runBlocking {
        fun player(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject.getValue("player").jsonObject
        fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.int
        fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content
        fun JsonObject.mirrors() = this["crossfade"]?.jsonPrimitive?.boolean to this["smartTransitions"]?.jsonPrimitive?.boolean
        val untouched = player(backupFrom(MemoryPrefs()))
        assertEquals(2, untouched.number("settingsVersion"))
        assertNull(untouched["betweenSongs"])
        assertEquals(true to true, untouched.mirrors())
        val mirrors = mapOf("Gapless" to (false to false), "Crossfade" to (true to false), "Automix" to (true to true))
        for ((choice, mirror) in mirrors) {
            val written = player(backupFrom(MemoryPrefs(storedPrefs(mapOf("between_songs" to choice)))))
            assertEquals(choice, 2, written.number("settingsVersion"))
            assertEquals(choice, written.text("betweenSongs"))
            assertEquals(choice, mirror, written.mirrors())
        }
    }

    // A phone's settings go through its own backup and back unchanged:
    // untouched, and each choice with Match tempo on and off.
    @Test
    fun aBackupRestoresWhatThePhoneShowed() = runBlocking {
        val phones = listOf(emptyMap<String, Any>(), OLD_DEFAULTS) + BetweenSongs.entries.flatMap { choice ->
            listOf(true, false).map { tempo -> mapOf("between_songs" to choice.stored, "match_tempo" to tempo, "crossfade_seconds" to 11, "filter_sweeps" to false) }
        }
        for (keys in phones) {
            val before = shown(MemoryPrefs(storedPrefs(keys)))
            val text = backupFrom(MemoryPrefs(storedPrefs(keys)))
            assertEquals("$keys", before, (decodeBackup(text) as BackupRead.Read).backup.player!!.prefs)
            assertEquals("$keys", before, shown(restore(text)))
        }
    }

    // Restored, backed up and restored again, a Gapless phone from before
    // the choice stays Gapless, its choice in the backup in between.
    @Test
    fun aLegacyGaplessBackupStaysGaplessThroughTheNextBackup() = runBlocking {
        val legacy = backupOf("""{"crossfade": false, "crossfadeSeconds": 4, "smartTransitions": true, "filterSweeps": true, "matchTempo": false, "crossfadeCurve": "EqualPower"}""")
        val first = restore(legacy, keys = emptyMap())
        assertEquals(BetweenSongs.Gapless, shown(first).betweenSongs)
        val next = backupFrom(first)
        assertTrue(next, next.contains("\"betweenSongs\": \"Gapless\""))
        val second = restore(next, keys = emptyMap())
        assertEquals(BetweenSongs.Gapless, shown(second).betweenSongs)
        assertTrue(shown(second).betweenSongsStored)
    }

    // A backup from before the choice that blended with smart transitions
    // restores as Automix with Match tempo on, both stored and shown, and
    // the backup's own Match tempo off is not written back over it.
    @Test
    fun aLegacyAutomixBackupTurnsMatchTempoOn() = runBlocking {
        val legacy = backupOf("""{"crossfade": true, "crossfadeSeconds": 8, "smartTransitions": true, "filterSweeps": true, "matchTempo": false}""")
        assertTrue((decodeBackup(legacy) as BackupRead.Read).backup.player!!.prefs.matchTempo)
        val phone = restore(legacy)
        assertEquals(true, phone.keys()["match_tempo"])
        assertEquals("Automix", phone.keys()["between_songs"])
        val after = shown(phone)
        assertEquals(BetweenSongs.Automix, after.betweenSongs)
        assertTrue(after.betweenSongsStored)
        assertTrue(after.matchTempo)
    }

    // A phone that had crossfade on before the choice existed backs up with
    // the choice once its settings are first read, so the backup restores
    // as Crossfade and not as untouched.
    @Test
    fun aChoiceFromBeforeTheChoiceExistedIsInTheNextBackup() = runBlocking {
        val phone = phoneThatRanAnOlderApp(folder.newFolder(), scope, mapOf("crossfade" to true, "smart_transitions" to false))
        val text = encodeBackup(Backup(player = PlayerBackup(PlayerSettings(phone).snapshot().first)))
        assertTrue(text, text.contains("\"betweenSongs\": \"Crossfade\""))
        val restored = shown(restore(text, keys = emptyMap()))
        assertEquals(BetweenSongs.Crossfade, restored.betweenSongs)
        assertTrue(restored.betweenSongsStored)
    }

    // The player sections every app writes, read as every app reads them.
    @Test
    fun theSharedBackupsRestoreAsEveryAppRestoresThem() = runBlocking {
        val file = listOf(
            "../shared/core/src/commonTest/resources/backup/between-songs-player.json",
            "shared/core/src/commonTest/resources/backup/between-songs-player.json",
        ).map(::File).first { it.exists() }
        val cases = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size >= 10)
        for (case in cases.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val phone = restore(backupOf(case.getValue("player").toString()))
            val after = shown(phone)
            assertEquals(name, BetweenSongs.valueOf(case.getValue("choice").jsonPrimitive.content), after.betweenSongs)
            assertEquals(name, case.getValue("stored").jsonPrimitive.boolean, after.betweenSongsStored)
            assertEquals(name, case.getValue("matchTempo").jsonPrimitive.boolean, after.matchTempo)
            assertEquals(name, case.getValue("crossfadeSeconds").jsonPrimitive.int, after.crossfadeSeconds)
            if (!after.betweenSongsStored) untouchedRemoves.forEach { assertFalse("$name: $it", it in phone.keys()) }
        }
    }
}

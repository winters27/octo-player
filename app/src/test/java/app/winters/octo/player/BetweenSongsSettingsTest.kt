package app.winters.octo.player

import app.winters.octo.playback.AUTOMIX_LONGEST_BLEND_MS
import app.winters.octo.playback.AUTOMIX_LONGEST_INTRO_MS
import app.winters.octo.playback.BetweenSongs
import app.winters.octo.playback.FadeCurve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// The Between songs settings on the phone: an untouched phone shows
// Automix with Match tempo on and stores nothing, a choice made before
// this app is stored at the first read, and every change goes through the
// one setter, which keeps what an untouched phone shows.
class BetweenSongsSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    @After
    fun close() = runBlocking { job.cancelAndJoin() }

    @Test
    fun aNewInstallShowsAutomixWithMatchTempoAndStoresNothing() = runBlocking {
        val store = MemoryPrefs()
        val prefs = PlayerSettings(store).prefs.first()
        assertEquals(BetweenSongs.Automix, prefs.betweenSongs)
        assertFalse(prefs.betweenSongsStored)
        assertTrue(prefs.matchTempo)
        assertTrue(prefs.filterSweeps)
        assertEquals(8, prefs.crossfadeSeconds)
        assertEquals(PlayerPrefs(), prefs)
        assertEquals(emptyMap<String, Any>(), store.keys())
    }

    @Test
    fun thePlayerGetsWhatTheChoiceGives() = runBlocking {
        suspend fun prefsOf(keys: Map<String, Any>) = PlayerSettings(MemoryPrefs(storedPrefs(keys))).prefs.first()
        val length = mapOf("crossfade_seconds" to 12, "crossfade_curve" to "Linear", "filter_sweeps" to false, "match_tempo" to false)
        val gapless = prefsOf(length + ("between_songs" to "Gapless"))
        assertEquals(0L, gapless.crossfadeMs)
        assertEquals(0L, gapless.automix.maxOverlapMs)
        val crossfade = prefsOf(length + ("between_songs" to "Crossfade"))
        assertEquals(12_000L, crossfade.crossfadeMs)
        assertEquals(12_000L, crossfade.automix.maxOverlapMs)
        assertFalse(crossfade.automix.smart)
        assertEquals(FadeCurve.Linear, crossfade.automix.fadeCurve)
        // Automix picks its own lengths, whatever Crossfade's is.
        val automix = prefsOf(length + ("between_songs" to "Automix"))
        assertEquals(AUTOMIX_LONGEST_BLEND_MS, automix.crossfadeMs)
        assertEquals(AUTOMIX_LONGEST_BLEND_MS, automix.automix.maxOverlapMs)
        assertEquals(AUTOMIX_LONGEST_INTRO_MS, automix.automix.longestIntroMs)
        assertTrue(automix.automix.smart)
        assertFalse(automix.automix.filterSweeps)
        assertFalse(automix.automix.beatMatch)
        // An untouched phone blends as Automix with Match tempo.
        val untouched = prefsOf(OLD_DEFAULTS)
        assertEquals(AUTOMIX_LONGEST_BLEND_MS, untouched.crossfadeMs)
        assertTrue(untouched.automix.smart)
        assertTrue(untouched.automix.beatMatch)
    }

    // A phone whose older app had crossfade on stores the choice that comes
    // to at its first read, Match tempo on with Automix, once.
    @Test
    fun theFirstReadStoresAChoiceMadeBeforeIt() = runBlocking {
        val crossfade = mapOf("crossfade" to true, "smart_transitions" to false, "crossfade_seconds" to 5, "match_tempo" to false)
        val crossfadePhone = phoneThatRanAnOlderApp(folder.newFolder(), scope, crossfade)
        val shown = PlayerSettings(crossfadePhone).prefs.first()
        assertEquals(BetweenSongs.Crossfade, shown.betweenSongs)
        assertTrue(shown.betweenSongsStored)
        assertEquals(5, shown.crossfadeSeconds)
        assertFalse(shown.matchTempo)
        assertEquals(crossfade + ("between_songs" to "Crossfade"), crossfadePhone.keys())

        val automix = mapOf("crossfade" to true, "crossfade_seconds" to 12, "match_tempo" to false)
        val automixPhone = phoneThatRanAnOlderApp(folder.newFolder(), scope, automix)
        val mixed = PlayerSettings(automixPhone).prefs.first()
        assertEquals(BetweenSongs.Automix, mixed.betweenSongs)
        assertTrue(mixed.betweenSongsStored)
        assertTrue(mixed.matchTempo)
        assertEquals(automix + mapOf("between_songs" to "Automix", "smart_transitions" to true, "match_tempo" to true), automixPhone.keys())

        val gapless = OLD_DEFAULTS + ("crossfade_seconds" to 4)
        val gaplessPhone = phoneThatRanAnOlderApp(folder.newFolder(), scope, gapless)
        assertEquals(BetweenSongs.Gapless, PlayerSettings(gaplessPhone).prefs.first().betweenSongs)
        assertEquals(gapless + mapOf("between_songs" to "Gapless", "smart_transitions" to false), gaplessPhone.keys())
    }

    // An untouched phone, and one whose choice is stored, are left as they are.
    @Test
    fun theFirstReadLeavesAnUntouchedPhoneAlone() = runBlocking {
        for (old in listOf(OLD_DEFAULTS, mapOf("crossfade" to false), mapOf("smart_transitions" to false, "crossfade_seconds" to 12))) {
            val phone = phoneThatRanAnOlderApp(folder.newFolder(), scope, old)
            val shown = PlayerSettings(phone).prefs.first()
            assertEquals("$old", BetweenSongs.Automix, shown.betweenSongs)
            assertFalse("$old", shown.betweenSongsStored)
            assertTrue("$old", shown.matchTempo)
            assertEquals("$old", old, phone.keys())
        }
        val chosen = mapOf("between_songs" to "Gapless", "crossfade" to true, "smart_transitions" to true)
        val phone = phoneThatRanAnOlderApp(folder.newFolder(), scope, chosen)
        assertEquals(BetweenSongs.Gapless, PlayerSettings(phone).prefs.first().betweenSongs)
        assertEquals(chosen, phone.keys())
    }

    // Each row's first change on an untouched phone (new, or restored at
    // the old defaults) keeps everything else the page showed, and stores
    // the choice it showed, so Match tempo never flips back and a restored
    // phone never turns Gapless.
    @Test
    fun eachRowsFirstChangeOnAnUntouchedPhoneKeepsWhatItShowed() = runBlocking {
        val changes: List<Pair<BetweenSongsChange, (PlayerPrefs) -> PlayerPrefs>> = listOf(
            BetweenSongsChange.Choose(BetweenSongs.Crossfade) to { p -> p.copy(betweenSongs = BetweenSongs.Crossfade) },
            BetweenSongsChange.Choose(BetweenSongs.Gapless) to { p -> p.copy(betweenSongs = BetweenSongs.Gapless) },
            BetweenSongsChange.Choose(BetweenSongs.Automix) to { p -> p },
            BetweenSongsChange.Length(12) to { p -> p.copy(crossfadeSeconds = 12) },
            BetweenSongsChange.Curve(FadeCurve.Linear) to { p -> p.copy(crossfadeCurve = FadeCurve.Linear) },
            BetweenSongsChange.FilterSweeps(false) to { p -> p.copy(filterSweeps = false) },
            BetweenSongsChange.MatchTempo(false) to { p -> p.copy(matchTempo = false) },
        )
        for (start in listOf(emptyMap(), OLD_DEFAULTS)) for ((change, applied) in changes) {
            val settings = PlayerSettings(MemoryPrefs(storedPrefs(start)))
            val before = settings.prefs.first()
            assertFalse(before.betweenSongsStored)
            settings.editBetweenSongs(change)
            val after = settings.prefs.first()
            assertEquals("$change from $start", applied(before).copy(betweenSongsStored = true), after)
        }
    }

    // Once the choice is stored, a change writes only its own key, and a new
    // choice its mirrors.
    @Test
    fun aStoredChoiceChangesOnlyWhatChanged() = runBlocking {
        val chosen = mapOf("between_songs" to "Crossfade", "crossfade" to true, "smart_transitions" to false, "match_tempo" to false)
        val store = MemoryPrefs(storedPrefs(chosen))
        val settings = PlayerSettings(store)
        settings.editBetweenSongs(BetweenSongsChange.Length(4))
        assertEquals(chosen + ("crossfade_seconds" to 4), store.keys())
        settings.editBetweenSongs(BetweenSongsChange.FilterSweeps(false))
        assertEquals(chosen + mapOf("crossfade_seconds" to 4, "filter_sweeps" to false), store.keys())
        // A choice of Automix leaves Match tempo as it was set.
        settings.editBetweenSongs(BetweenSongsChange.Choose(BetweenSongs.Automix))
        assertFalse(settings.prefs.first().matchTempo)
        assertEquals(chosen + mapOf("crossfade_seconds" to 4, "filter_sweeps" to false, "between_songs" to "Automix", "smart_transitions" to true), store.keys())
    }

    // An older app installed over this one reads the choice from the
    // mirrors written beside it.
    @Test
    fun eachChoiceWritesTheMirrorsOlderAppsRead() = runBlocking {
        val mirrors = mapOf(BetweenSongs.Gapless to (false to false), BetweenSongs.Crossfade to (true to false), BetweenSongs.Automix to (true to true))
        for ((choice, mirror) in mirrors) {
            val store = MemoryPrefs(storedPrefs(mapOf("between_songs" to "Gapless", "crossfade" to false, "smart_transitions" to false)))
            PlayerSettings(store).editBetweenSongs(BetweenSongsChange.Choose(choice))
            val keys = store.keys()
            assertEquals("$choice", choice.stored, keys["between_songs"])
            assertEquals("$choice", mirror, keys["crossfade"] to keys["smart_transitions"])
        }
    }
}

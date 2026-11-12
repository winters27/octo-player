package app.winters.octo.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BetweenSongsTest {
    // Mapping

    @Test
    fun eachChoiceGivesItsLongestBlend() {
        assertEquals(0L, crossfadeMsOf(BetweenSongs.Gapless, 8))
        assertEquals(0L, crossfadeMsOf(BetweenSongs.Gapless, 16))
        assertEquals(8_000L, crossfadeMsOf(BetweenSongs.Crossfade, 8))
        assertEquals(2_000L, crossfadeMsOf(BetweenSongs.Crossfade, 2))
        assertEquals(16_000L, crossfadeMsOf(BetweenSongs.Crossfade, 16))
        // A length outside Crossfade's range is its nearest end.
        assertEquals(2_000L, crossfadeMsOf(BetweenSongs.Crossfade, 0))
        assertEquals(16_000L, crossfadeMsOf(BetweenSongs.Crossfade, 30))
        // Automix picks its own lengths, whatever Crossfade's is.
        for (seconds in listOf(2, 8, 16)) assertEquals(AUTOMIX_LONGEST_BLEND_MS, crossfadeMsOf(BetweenSongs.Automix, seconds))
        assertEquals(8_000L, AUTOMIX_LONGEST_BLEND_MS)
        assertEquals(16_000L, AUTOMIX_LONGEST_INTRO_MS)
    }

    @Test
    fun eachChoiceGivesThePlannerItsSettings() {
        for (choice in BetweenSongs.entries) {
            for (curve in FadeCurve.entries) {
                for (sweeps in listOf(true, false)) {
                    for (tempo in listOf(true, false)) {
                        val settings = automixSettingsOf(choice, 12, curve, sweeps, tempo)
                        val what = "$choice $curve sweeps $sweeps tempo $tempo"
                        assertEquals(AutomixSettings(
                            maxOverlapMs = crossfadeMsOf(choice, 12),
                            longestIntroMs = AUTOMIX_LONGEST_INTRO_MS,
                            smart = choice == BetweenSongs.Automix,
                            filterSweeps = sweeps,
                            beatMatch = tempo,
                            fadeCurve = curve,
                        ), settings, what)
                    }
                }
            }
        }
        assertEquals(12_000L, automixSettingsOf(BetweenSongs.Crossfade, 12, FadeCurve.Linear, true, true).maxOverlapMs)
        assertEquals(0L, automixSettingsOf(BetweenSongs.Gapless, 8, FadeCurve.EqualPower, true, true).maxOverlapMs)
        assertEquals(8_000L, automixSettingsOf(BetweenSongs.Automix, 3, FadeCurve.EqualPower, true, true).maxOverlapMs)
    }

    @Test
    fun theChoiceIsStoredByName() {
        assertEquals(listOf("Gapless", "Crossfade", "Automix"), BetweenSongs.entries.map { it.stored })
        for (choice in BetweenSongs.entries) assertEquals(choice, BetweenSongs.ofStored(choice.stored))
        assertNull(BetweenSongs.ofStored(null))
        assertNull(BetweenSongs.ofStored("automix"))
        assertNull(BetweenSongs.ofStored("Smart"))
    }

    // Untouched rule

    // Every value each key can be stored with that the rule tells apart,
    // null for not stored.
    private val choices = listOf(null, "Gapless", "Crossfade", "Automix", "Later")
    private val crossfades = listOf(null, false, true)
    private val lengths = listOf(null, 8, 2, 5, 16)
    private val flags = listOf(null, true, false)
    private val curves = listOf(null, "EqualPower", "Linear", "MixOnly", "Unknown")

    private fun everyCombination(): Sequence<StoredBetweenSongs> = sequence {
        for (b in choices) for (c in crossfades) for (s in lengths) for (smart in flags) for (sweeps in flags) for (tempo in flags) for (curve in curves) {
            yield(StoredBetweenSongs(b, c, s, smart, sweeps, tempo, curve))
        }
    }

    // The rule in its own words: untouched when the choice is missing and
    // either crossfade is missing, or crossfade is off with the length
    // missing or 8, smart transitions missing or on, Filter sweeps missing
    // or on, Match tempo missing or off and the curve missing or equal
    // power (an unknown name reads as equal power).
    private fun expected(k: StoredBetweenSongs): BetweenSongsReading {
        val stored = when (k.betweenSongs) {
            "Gapless" -> BetweenSongs.Gapless
            "Crossfade" -> BetweenSongs.Crossfade
            "Automix" -> BetweenSongs.Automix
            else -> null
        }
        if (stored != null) return BetweenSongsReading(stored, k.matchTempo ?: true, stored = true, untouched = false)
        val curveAtDefault = k.crossfadeCurve == null || k.crossfadeCurve == "EqualPower" || k.crossfadeCurve == "Unknown"
        val oldDefaults = k.crossfade == false && k.crossfadeSeconds in listOf(null, 8) && k.smartTransitions in listOf(null, true) &&
            k.filterSweeps in listOf(null, true) && k.matchTempo in listOf(null, false) && curveAtDefault
        if (k.crossfade == null || oldDefaults) return BetweenSongsReading(BetweenSongs.Automix, true, stored = false, untouched = true)
        val choice = if (k.crossfade == false) BetweenSongs.Gapless else if (k.smartTransitions == false) BetweenSongs.Crossfade else BetweenSongs.Automix
        val tempo = if (choice == BetweenSongs.Automix) true else k.matchTempo ?: true
        return BetweenSongsReading(choice, tempo, stored = false, untouched = false)
    }

    @Test
    fun everyCombinationOfStoredKeysGivesTheRulesChoice() {
        var count = 0
        for (keys in everyCombination()) {
            assertEquals(expected(keys), readBetweenSongs(keys), "$keys")
            count++
        }
        assertEquals(5 * 3 * 5 * 3 * 3 * 3 * 5, count)
    }

    @Test
    fun aNewInstallAndOneThatNeverTouchedCrossfadeAreUntouched() {
        val untouched = BetweenSongsReading(BetweenSongs.Automix, matchTempo = true, stored = false, untouched = true)
        assertEquals(untouched, readBetweenSongs(StoredBetweenSongs()))
        // Other settings changed, crossfade itself never stored.
        assertEquals(untouched, readBetweenSongs(StoredBetweenSongs(smartTransitions = false, crossfadeSeconds = 12, matchTempo = false)))
    }

    @Test
    fun aRestoredPhoneAtTheOldDefaultsIsUntouched() {
        // A backup restore writes every key, each at its old default.
        val restored = StoredBetweenSongs(
            crossfade = false, crossfadeSeconds = 8, smartTransitions = true, filterSweeps = true, matchTempo = false, crossfadeCurve = "EqualPower",
        )
        assertTrue(readBetweenSongs(restored).untouched)
        // Turned crossfade on and off again: still untouched.
        assertTrue(readBetweenSongs(StoredBetweenSongs(crossfade = false)).untouched)
        // Any setting away from its old default is a choice of Gapless.
        for (changed in listOf(
            restored.copy(crossfadeSeconds = 9),
            restored.copy(smartTransitions = false),
            restored.copy(filterSweeps = false),
            restored.copy(matchTempo = true),
            restored.copy(crossfadeCurve = "SCurve"),
        )) {
            assertEquals(BetweenSongsReading(BetweenSongs.Gapless, changed.matchTempo ?: true, stored = false, untouched = false), readBetweenSongs(changed), "$changed")
        }
    }

    @Test
    fun crossfadeOnIsAutomixWithMatchTempoOrCrossfadeWithSmartOff() {
        assertEquals(BetweenSongsReading(BetweenSongs.Automix, true, false, false), readBetweenSongs(StoredBetweenSongs(crossfade = true)))
        assertEquals(BetweenSongsReading(BetweenSongs.Automix, true, false, false), readBetweenSongs(StoredBetweenSongs(crossfade = true, smartTransitions = true, matchTempo = false)))
        assertEquals(BetweenSongsReading(BetweenSongs.Crossfade, false, false, false), readBetweenSongs(StoredBetweenSongs(crossfade = true, smartTransitions = false, matchTempo = false)))
        assertEquals(BetweenSongsReading(BetweenSongs.Crossfade, true, false, false), readBetweenSongs(StoredBetweenSongs(crossfade = true, smartTransitions = false)))
    }

    @Test
    fun aStoredChoiceIsTheChoice() {
        for (choice in BetweenSongs.entries) {
            val keys = StoredBetweenSongs(betweenSongs = choice.stored, crossfade = false, matchTempo = false)
            assertEquals(BetweenSongsReading(choice, matchTempo = false, stored = true, untouched = false), readBetweenSongs(keys))
        }
        // An unknown stored choice, as from a newer app, falls back to the mirrors.
        assertEquals(BetweenSongs.Crossfade, readBetweenSongs(StoredBetweenSongs("Later", crossfade = true, smartTransitions = false)).choice)
    }

    @Test
    fun theKeysReadByName() {
        val prefs = mapOf<String, Any>(
            "between_songs" to "Crossfade",
            "crossfade" to true,
            "crossfade_seconds" to 12L,
            "smart_transitions" to false,
            "filter_sweeps" to true,
            "match_tempo" to false,
            "crossfade_curve" to "Linear",
            "unrelated" to 3,
        )
        assertEquals(StoredBetweenSongs("Crossfade", true, 12, false, true, false, "Linear"), StoredBetweenSongs.read(prefs::get))
        assertEquals(StoredBetweenSongs(), StoredBetweenSongs.read { null })
        // A value of the wrong type is not a stored key.
        assertEquals(StoredBetweenSongs(), StoredBetweenSongs.read { if (it == "crossfade") "yes" else null })
    }

    // Writes

    @Test
    fun aWriteCarriesTheMirrorsAnOlderAppReads() {
        assertEquals(false to false, BetweenSongsWrite(BetweenSongs.Gapless).let { it.crossfade to it.smartTransitions })
        assertEquals(true to false, BetweenSongsWrite(BetweenSongs.Crossfade).let { it.crossfade to it.smartTransitions })
        assertEquals(true to true, BetweenSongsWrite(BetweenSongs.Automix).let { it.crossfade to it.smartTransitions })
        // An older app reading the mirrors back gets the same choice.
        for (choice in BetweenSongs.entries) {
            val w = BetweenSongsWrite(choice)
            val older = StoredBetweenSongs(crossfade = w.crossfade, smartTransitions = w.smartTransitions, crossfadeSeconds = 12)
            assertEquals(choice, readBetweenSongs(older).choice)
        }
    }

    @Test
    fun theDerivedWriteStoresWhatTheDeviceShows() {
        for (keys in everyCombination()) {
            val reading = readBetweenSongs(keys)
            val write = derivedBetweenSongsWrite(keys)
            if (reading.stored) {
                assertNull(write, "$keys")
                continue
            }
            requireNotNull(write)
            assertEquals(reading.choice, write.choice, "$keys")
            // Match tempo is written only when Automix turned it on.
            val turned = reading.choice == BetweenSongs.Automix && keys.matchTempo != true
            assertEquals(if (turned) true else null, write.matchTempo, "$keys")
            // Once written, the device reads the same.
            val after = keys.copy(
                betweenSongs = write.choice.stored,
                crossfade = write.crossfade,
                smartTransitions = write.smartTransitions,
                matchTempo = write.matchTempo ?: keys.matchTempo,
            )
            assertEquals(reading.copy(stored = true, untouched = false), readBetweenSongs(after), "$keys")
        }
    }

    @Test
    fun theFirstReadStoresAChoiceButLeavesAnUntouchedDeviceAlone() {
        assertNull(firstReadBetweenSongsWrite(StoredBetweenSongs()))
        assertNull(firstReadBetweenSongsWrite(StoredBetweenSongs(crossfade = false, crossfadeSeconds = 8)))
        assertNull(firstReadBetweenSongsWrite(StoredBetweenSongs(betweenSongs = "Gapless", crossfade = true)))
        assertEquals(BetweenSongsWrite(BetweenSongs.Crossfade), firstReadBetweenSongsWrite(StoredBetweenSongs(crossfade = true, smartTransitions = false)))
        assertEquals(BetweenSongsWrite(BetweenSongs.Automix, matchTempo = true), firstReadBetweenSongsWrite(StoredBetweenSongs(crossfade = true)))
        assertEquals(BetweenSongsWrite(BetweenSongs.Automix), firstReadBetweenSongsWrite(StoredBetweenSongs(crossfade = true, matchTempo = true)))
        assertEquals(BetweenSongsWrite(BetweenSongs.Gapless), firstReadBetweenSongsWrite(StoredBetweenSongs(crossfade = false, crossfadeSeconds = 4)))
        for (keys in everyCombination()) {
            val write = firstReadBetweenSongsWrite(keys)
            val reading = readBetweenSongs(keys)
            if (reading.stored || reading.untouched) assertNull(write, "$keys") else assertEquals(derivedBetweenSongsWrite(keys), write, "$keys")
        }
    }

    @Test
    fun theFirstEditOnAnUntouchedDeviceKeepsAutomixAndMatchTempoThenMakesTheChange() {
        val untouched = StoredBetweenSongs(crossfade = false, crossfadeSeconds = 8, matchTempo = false)
        val derived = BetweenSongsWrite(BetweenSongs.Automix, matchTempo = true)
        // A change to another row: the derived write alone, the row's key after it.
        assertEquals(listOf(derived), betweenSongsEdit(untouched))
        // A change of the choice: the derived write, then the choice.
        assertEquals(listOf(derived, BetweenSongsWrite(BetweenSongs.Crossfade)), betweenSongsEdit(untouched, BetweenSongs.Crossfade))
        // Once the choice is stored, only the change.
        val chosen = untouched.copy(betweenSongs = "Automix", crossfade = true, smartTransitions = true, matchTempo = true)
        assertEquals(emptyList(), betweenSongsEdit(chosen))
        assertEquals(listOf(BetweenSongsWrite(BetweenSongs.Gapless)), betweenSongsEdit(chosen, BetweenSongs.Gapless))
        // A Crossfade user who never had the choice stored keeps Crossfade
        // while changing its length.
        assertEquals(listOf(BetweenSongsWrite(BetweenSongs.Crossfade)), betweenSongsEdit(StoredBetweenSongs(crossfade = true, smartTransitions = false)))
    }

    // Restores

    @Test
    fun anOlderBackupGoesThroughTheUntouchedRule() {
        val oldDefaults = StoredBetweenSongs(
            crossfade = false, crossfadeSeconds = 8, smartTransitions = true, filterSweeps = true, matchTempo = false, crossfadeCurve = "EqualPower",
        )
        assertEquals(RestoredBetweenSongs.Untouched, restoredBetweenSongs(null, oldDefaults))
        assertEquals(RestoredBetweenSongs.Untouched, restoredBetweenSongs(null, StoredBetweenSongs()))
        assertEquals(
            RestoredBetweenSongs.Chosen(BetweenSongsWrite(BetweenSongs.Crossfade)),
            restoredBetweenSongs(null, oldDefaults.copy(crossfade = true, smartTransitions = false)),
        )
        assertEquals(RestoredBetweenSongs.Chosen(BetweenSongsWrite(BetweenSongs.Gapless)), restoredBetweenSongs(null, oldDefaults.copy(crossfadeSeconds = 4)))
        // An Automix backup with Match tempo off restores with it on.
        assertEquals(
            RestoredBetweenSongs.Chosen(BetweenSongsWrite(BetweenSongs.Automix, matchTempo = true)),
            restoredBetweenSongs(null, oldDefaults.copy(crossfade = true)),
        )
        for (keys in everyCombination().filter { it.betweenSongs == null }) {
            val restored = restoredBetweenSongs(null, keys)
            if (readBetweenSongs(keys).untouched) {
                assertEquals(RestoredBetweenSongs.Untouched, restored, "$keys")
            } else {
                assertEquals(RestoredBetweenSongs.Chosen(derivedBetweenSongsWrite(keys)!!), restored, "$keys")
            }
        }
    }

    @Test
    fun aNewerBackupWithoutAChoiceIsUntouched() {
        val crossfadeMirrors = StoredBetweenSongs(crossfade = true, smartTransitions = false, crossfadeSeconds = 12, matchTempo = false)
        assertEquals(RestoredBetweenSongs.Untouched, restoredBetweenSongs(BETWEEN_SONGS_SETTINGS_VERSION, crossfadeMirrors))
        assertEquals(RestoredBetweenSongs.Untouched, restoredBetweenSongs(BETWEEN_SONGS_SETTINGS_VERSION, crossfadeMirrors.copy(betweenSongs = "Later")))
        for (choice in BetweenSongs.entries) {
            assertEquals(
                RestoredBetweenSongs.Chosen(BetweenSongsWrite(choice)),
                restoredBetweenSongs(BETWEEN_SONGS_SETTINGS_VERSION, crossfadeMirrors.copy(betweenSongs = choice.stored)),
            )
            // A later version reads the same way.
            assertEquals(RestoredBetweenSongs.Chosen(BetweenSongsWrite(choice)), restoredBetweenSongs(3, StoredBetweenSongs(betweenSongs = choice.stored)))
        }
        assertEquals(2, BETWEEN_SONGS_SETTINGS_VERSION)
    }

    @Test
    fun aRestoredChoiceBacksUpAndRestoresTheSame() {
        // A 1.12 Gapless backup restored, backed up (version 2 with the
        // choice) and restored again stays Gapless.
        val legacy = StoredBetweenSongs(crossfade = false, crossfadeSeconds = 6)
        val first = restoredBetweenSongs(null, legacy) as RestoredBetweenSongs.Chosen
        assertEquals(BetweenSongs.Gapless, first.write.choice)
        val backedUp = legacy.copy(betweenSongs = first.write.choice.stored, crossfade = first.write.crossfade, smartTransitions = first.write.smartTransitions)
        assertEquals(RestoredBetweenSongs.Chosen(BetweenSongsWrite(BetweenSongs.Gapless)), restoredBetweenSongs(BETWEEN_SONGS_SETTINGS_VERSION, backedUp))
        // An untouched restore takes the four keys off, which then read untouched.
        assertEquals(listOf("between_songs", "crossfade", "smart_transitions", "match_tempo"), BetweenSongsKeys.UNTOUCHED_RESTORE_REMOVES)
        val afterRemoval = StoredBetweenSongs(crossfadeSeconds = 8, filterSweeps = true, crossfadeCurve = "EqualPower")
        assertTrue(readBetweenSongs(afterRemoval).untouched)
    }
}

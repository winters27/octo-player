package app.winters.octo.desktop.audio

import app.winters.octo.audio.SongProfile
import app.winters.octo.audio.SongProfileSection
import app.winters.octo.audio.SongProfileTempo
import app.winters.octo.desktop.player.QueueEntry
import app.winters.octo.playback.SectionProfile
import app.winters.octo.playback.Tempo
import app.winters.octo.playback.TransitionProfile
import app.winters.octo.playback.TransitionProfiles
import app.winters.octo.subsonic.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// How many queue entries are remembered as handed their profile.
private const val ENTRIES_KEPT = 256

// Hands the engine the transition profiles of the playing song and the one
// after it, from the server in use, so it plans their blend from them
// instead of reading the songs. `profiles` gives the server's kept
// profiles, or null when it makes none; `serverIdOf` a song's id there, or
// null for a song that is not the server's (a file on this computer).
// `unsend` takes a profile back from the engine; `beforeAsk` runs before a
// song is asked about (what changed on the server is read first).
class EngineProfiles(
    private val scope: CoroutineScope,
    private val profiles: () -> TransitionProfiles?,
    private val serverIdOf: (Song) -> String?,
    private val send: (itemId: String, profile: SongProfile) -> Unit,
    private val unsend: (itemId: String) -> Unit = {},
    private val beforeAsk: suspend (Song) -> Unit = {},
) {
    // One entry's ask, for the song's id on the server. Its answer is handed
    // over only while the entry still has this very ask, so an answer about
    // a file let go meanwhile never reaches the engine.
    private class Ask(val id: String)

    // Queue entries already handed theirs, or being asked about.
    private val handled = LinkedHashMap<Long, Ask>()

    // Asks for each entry's profile once, and hands over the ones there are.
    fun follow(entries: List<QueueEntry>) {
        val source = profiles() ?: return
        for (entry in entries) {
            val id = serverIdOf(entry.song) ?: continue
            val ask = synchronized(handled) {
                if (handled.containsKey(entry.key)) return@synchronized null
                Ask(id).also {
                    handled[entry.key] = it
                    if (handled.size > ENTRIES_KEPT) handled.remove(handled.keys.first())
                }
            } ?: continue
            scope.launch {
                beforeAsk(entry.song)
                if (synchronized(handled) { handled[entry.key] !== ask }) return@launch
                val profile = source.profile(id)
                synchronized(handled) {
                    if (handled[entry.key] !== ask) return@launch
                    if (profile != null) {
                        send(itemId(entry.key), profile.toEngine())
                    } else {
                        // Asked again when it next comes up.
                        handled.remove(entry.key)
                    }
                }
            }
        }
    }

    // The server gave songs another file: what was kept of their old ones
    // goes, from the kept profiles and from the engine, so they are asked
    // for again when they next come up. Null: every song's.
    fun forget(ids: Set<String>?) {
        if (ids != null && ids.isEmpty()) return
        profiles()?.let { kept -> if (ids == null) kept.clear() else kept.forget(ids) }
        synchronized(handled) {
            val gone = handled.filterValues { ids == null || it.id in ids }.keys.toList()
            for (key in gone) {
                handled.remove(key)
                unsend(itemId(key))
            }
        }
    }

    // An entry starts over as a new play: its profile goes, from the engine
    // and the kept ones, and is asked for again.
    fun forgetEntry(key: Long) {
        val ask = synchronized(handled) {
            handled.remove(key)?.also { unsend(itemId(key)) }
        } ?: return
        profiles()?.forget(listOf(ask.id))
    }
}

// The profile as the engine takes it.
fun TransitionProfile.toEngine(): SongProfile = SongProfile(
    durationMs = durationMs,
    bodyDb = bodyDb,
    tempo = tempo?.toEngine(),
    head = head.toEngine(),
    tail = tail.toEngine(),
)

private fun SectionProfile.toEngine() = SongProfileSection(
    startMs = startMs,
    hopMs = hopMs.toUInt(),
    levels = levels.toList(),
    bodyDb = features.bodyDb,
    gateDb = features.gateDb,
    soundStartMs = features.soundStartMs,
    soundEndMs = features.soundEndMs,
    outroStartMs = features.outroStartMs,
    introEndMs = features.introEndMs,
    boundariesMs = features.boundariesMs,
    tempo = features.tempo?.toEngine(),
)

private fun Tempo.toEngine() = SongProfileTempo(
    bpm = bpm,
    beatMs = beatMs,
    firstBeatMs = firstBeatMs,
    downbeatMs = downbeatMs,
    confidence = confidence,
    consistency = consistency,
    steady = steady,
)

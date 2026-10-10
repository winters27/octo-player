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
class EngineProfiles(
    private val scope: CoroutineScope,
    private val profiles: () -> TransitionProfiles?,
    private val serverIdOf: (Song) -> String?,
    private val send: (itemId: String, profile: SongProfile) -> Unit,
) {
    // Queue entries already handed theirs, or being asked about.
    private val handled = LinkedHashSet<Long>()

    // Asks for each entry's profile once, and hands over the ones there are.
    fun follow(entries: List<QueueEntry>) {
        val source = profiles() ?: return
        for (entry in entries) {
            val id = serverIdOf(entry.song) ?: continue
            synchronized(handled) {
                if (!handled.add(entry.key)) return@synchronized null
                if (handled.size > ENTRIES_KEPT) handled.remove(handled.first())
                entry
            } ?: continue
            scope.launch {
                val profile = source.profile(id)
                if (profile != null) {
                    send(itemId(entry.key), profile.toEngine())
                } else {
                    // Asked again when it next comes up.
                    synchronized(handled) { handled.remove(entry.key) }
                }
            }
        }
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

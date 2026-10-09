package app.winters.octo.playback

import app.winters.octo.subsonic.ProfileSection
import app.winters.octo.subsonic.ProfileTempo
import app.winters.octo.subsonic.TransitionProfileAnswer
import java.util.Base64

// The version of the server's analysis these players read. A profile of
// any other version is ignored and the player scouts as before.
const val PROFILE_VERSION = 1

// Levels come as whole steps of this many dB above SILENT_DB.
const val LEVEL_STEP_DB = 0.5f

// The level a sent byte stands for.
fun levelOfCode(code: Int): Float = SILENT_DB + code * LEVEL_STEP_DB

// One end of a song from its profile: its level every hopMs from startMs,
// and its features.
class SectionProfile(val startMs: Long, val hopMs: Int, val levels: FloatArray, val features: SectionFeatures) {
    // The section as the planner takes it.
    fun analysis(): SectionAnalysis =
        SectionAnalysis.fromFeatures(
            SectionEnvelope(startMs, hopMs, levels, levels.copyOf(), FloatArray(levels.size), FloatArray(levels.size)),
            features,
        )
}

// What the server worked out about a song from its whole file: the start
// and the end the planner reads, and the whole song's level and tempo, as a
// live tap that heard all of it would give. The end is already measured
// against the whole song's level, so the planner takes it without a level
// from the live tap.
class TransitionProfile(
    val durationMs: Long,
    val bodyDb: Double?,
    val tempo: Tempo?,
    val head: SectionProfile,
    val tail: SectionProfile,
) {
    fun headAnalysis(): SectionAnalysis = head.analysis()

    fun tailAnalysis(): SectionAnalysis = tail.analysis()

    // The whole song's tempo when it is trusted.
    val tempoPrior: Double? get() = tempo?.takeIf { it.confident }?.bpm

    companion object {
        // The profile in a server's answer, or null when it is not ready,
        // of a version these players cannot read, or not whole.
        fun of(answer: TransitionProfileAnswer): TransitionProfile? {
            if (!answer.ready || answer.version != PROFILE_VERSION || answer.durationMs <= 0) return null
            val head = answer.head?.let(::sectionOf) ?: return null
            val tail = answer.tail?.let(::sectionOf) ?: return null
            return TransitionProfile(answer.durationMs, answer.bodyDb, answer.tempo?.let(::tempoOf), head, tail)
        }

        private fun sectionOf(section: ProfileSection): SectionProfile? {
            if (section.hopMs <= 0) return null
            val bytes = try {
                Base64.getDecoder().decode(section.levels)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val levels = FloatArray(bytes.size) { levelOfCode(bytes[it].toInt() and 0xFF) }
            val features = SectionFeatures(
                bodyDb = section.bodyDb,
                gateDb = section.gateDb,
                soundStartMs = section.soundStartMs,
                soundEndMs = section.soundEndMs,
                outroStartMs = section.outroStartMs,
                boundariesMs = section.boundariesMs,
                tempo = section.tempo?.let(::tempoOf),
                introEndMs = section.introEndMs,
            )
            return SectionProfile(section.startMs, section.hopMs, levels, features)
        }

        private fun tempoOf(t: ProfileTempo): Tempo? {
            if (t.bpm <= 0 || t.beatMs <= 0) return null
            return Tempo(
                bpm = t.bpm,
                confidence = t.confidence,
                consistency = t.consistency,
                beatMs = t.beatMs,
                firstBeatMs = t.firstBeatMs,
                downbeatMs = t.downbeatMs,
                steady = t.steady,
            )
        }
    }
}

// How long a "not ready" answer is believed before the song is asked
// about again: the server makes profiles in the background, one song at a
// time.
const val PROFILE_RETRY_MS = 60_000L

// How many songs' profiles are kept.
private const val PROFILES_KEPT = 64

// The transition profiles of the songs about to blend, asked of the server
// once and kept. `fetch` asks the server for one song by its id there, and
// gives null when this server hands out no profiles or could not be asked.
class TransitionProfiles(
    private val fetch: suspend (String) -> TransitionProfileAnswer?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val ready = object : LinkedHashMap<String, TransitionProfile>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TransitionProfile>) = size > PROFILES_KEPT
    }

    // When each song was last found to have no profile yet.
    private val missing = HashMap<String, Long>()

    // The song's profile when it is already here.
    fun cached(id: String): TransitionProfile? = synchronized(this) { ready[id] }

    // The song's profile, asking the server unless it has lately said there
    // is none. Null when there is none to have.
    suspend fun profile(id: String): TransitionProfile? {
        synchronized(this) {
            ready[id]?.let { return it }
            missing[id]?.let { at -> if (now() - at < PROFILE_RETRY_MS) return null }
        }
        val answer = try {
            fetch(id)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        val profile = answer?.let(TransitionProfile::of)
        synchronized(this) {
            if (profile != null) {
                ready[id] = profile
                missing.remove(id)
            } else {
                missing[id] = now()
                if (missing.size > PROFILES_KEPT * 4) missing.clear()
            }
        }
        return profile
    }

    // Forgets everything, as when the server changes.
    fun clear() = synchronized(this) {
        ready.clear()
        missing.clear()
    }
}

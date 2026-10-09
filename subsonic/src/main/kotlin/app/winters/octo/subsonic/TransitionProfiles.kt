package app.winters.octo.subsonic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The OpenSubsonic extension an Octo server lists when it hands out songs'
// transition profiles (getTransitionProfile): what the players need to plan
// the blend into and out of a song, worked out on the server from the file.
// It is listed only while the server makes them.
const val OCTO_TRANSITIONS = "octoTransitions"

// A beat grid as the server sends it: beats fall on firstBeatMs + n * beatMs
// and bars start on downbeatMs + n * 4 * beatMs, in song time.
@Serializable
data class ProfileTempo(
    val bpm: Double = 0.0,
    val trusted: Boolean = false,
    val beatMs: Double = 0.0,
    val firstBeatMs: Double = 0.0,
    val downbeatMs: Double = 0.0,
    val confidence: Double = 0.0,
    val consistency: Double = 0.0,
    val steady: Boolean = false,
)

// One end of a song: its level every `hopMs` from `startMs` (`levels`,
// base64 of one byte a hop, half-dB steps above -100 dBFS), and its
// features. A time the song does not have is left out.
@Serializable
data class ProfileSection(
    val startMs: Long = 0,
    val hopMs: Int = 0,
    val levels: String = "",
    val bodyDb: Double = -100.0,
    val gateDb: Double = -60.0,
    val soundStartMs: Long? = null,
    val soundEndMs: Long? = null,
    val outroStartMs: Long? = null,
    val introEndMs: Long? = null,
    val boundariesMs: List<Long> = emptyList(),
    val tempo: ProfileTempo? = null,
)

// The server's answer for one song. When `ready`, the profile follows: its
// analysis `version`, the song's length, the whole song's level and tempo,
// and its first 30 s and last 60 s. When not, `version` is the one the
// server makes now, when it says.
@Serializable
data class TransitionProfileAnswer(
    val id: String = "",
    val ready: Boolean = false,
    val version: Int = 0,
    val durationMs: Long = 0,
    val bodyDb: Double? = null,
    val tempo: ProfileTempo? = null,
    val head: ProfileSection? = null,
    val tail: ProfileSection? = null,
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        // A profile as JSON, as the server writes it.
        fun parse(text: String): TransitionProfileAnswer = json.decodeFromString(serializer(), text)
    }
}

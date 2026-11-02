package app.winters.octo.playback

import app.winters.octo.data.userMessage
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CancellationException

// A list a car plays from: its songs, its queue heading, whether it came
// from the server just now, the song to begin at and where in it.
data class CarList(val ids: List<String>, val source: QueueSource, val network: Boolean = false, val positionMs: Long = 0, val start: Int = 0)

// What a choice made in a car plays: the songs, the one to start at, where
// in it, under which heading, and whether it waited on the server.
data class CarPlay(val ids: List<String>, val start: Int, val source: QueueSource, val network: Boolean, val positionMs: Long = 0)

sealed interface CarPlayAnswer {
    data class Play(val play: CarPlay) : CarPlayAnswer

    // Nothing plays, and the car is told why, in the phone's words.
    data class Refused(val words: String) : CarPlayAnswer
}

// The words for a choice the server cannot play.
const val CAR_SIGN_IN = "Sign in to a server in Octo on your phone"
const val CAR_RESUME_GONE = "Couldn't pick up the other device's queue"
const val CAR_NOTHING_TO_PLAY = "Nothing here can play right now"
const val CAR_EMPTY_RADIO = "Keep stations in Live radio on your phone to hear them here"

fun nothingFoundLine(words: String): String = if (words.isBlank()) CAR_NOTHING_TO_PLAY else "Nothing found for \"${words.trim()}\""

// A list the server holds, opened with no server signed in.
class CarNotSignedIn : Exception()

// "Start radio" on a song in a car: the song, then songs like it (`like`,
// as the phone's song radio picks them), under "<title> radio".
const val NO_SIMILAR_SONGS = "No similar songs found"

suspend fun carRadio(seed: TrackEntity?, like: suspend (TrackEntity) -> List<String>): CarPlayAnswer {
    if (seed == null) return CarPlayAnswer.Refused(CAR_NOTHING_TO_PLAY)
    val songs = like(seed)
    if (songs.isEmpty()) return CarPlayAnswer.Refused(NO_SIMILAR_SONGS)
    return CarPlayAnswer.Play(CarPlay(listOf(seed.id) + songs, 0, QueueSource.Played("${seed.title} radio"), network = true))
}

fun nothingOnLine(name: String): String = "Nothing is playing on $name right now"

fun goneLine(name: String): String = "$name isn't on the server any more"

fun couldNotStartLine(name: String): String = "Could not start $name"

// What a choice made in a car plays. `songsOf` gives a list's songs: `fresh`
// asks the server again (a list's own play), otherwise what the car was last
// shown serves (a song chosen inside it). `signedIn` says whether a server
// can be asked at all, and `gone` hears of a list the server no longer has.
// A song chosen in a list plays that list from it, or alone when the list
// no longer holds it.
class CarPlays(
    private val songsOf: suspend (CarNode, Boolean) -> CarList?,
    private val signedIn: () -> Boolean,
    private val gone: (String) -> Unit,
) {
    // Null only for an id that is not a car's (the app's own requests). A
    // car's choice that holds nothing to play (a place that does not play,
    // a list gone or empty) is refused, so the queue stays as it is.
    suspend fun playFor(id: String): CarPlayAnswer? {
        val node = parseCarId(id) ?: return null
        if (node is CarNode.Song) return song(node)
        return when (val got = load(node, fresh = true)) {
            is Got.Refused -> CarPlayAnswer.Refused(got.words)
            Got.Nothing -> CarPlayAnswer.Refused(emptyWords(node) ?: CAR_NOTHING_TO_PLAY)
            is Got.Songs -> if (got.list.ids.isEmpty()) {
                CarPlayAnswer.Refused(emptyWords(node) ?: CAR_NOTHING_TO_PLAY)
            } else {
                CarPlayAnswer.Play(CarPlay(got.list.ids, got.list.start.coerceIn(got.list.ids.indices), got.list.source, got.list.network, got.list.positionMs))
            }
        }
    }

    private suspend fun song(node: CarNode.Song): CarPlayAnswer {
        val alone = CarPlay(listOf(node.trackId), 0, NoSource, network = false)
        val parent = node.list ?: return CarPlayAnswer.Play(alone)
        return when (val got = load(parent, fresh = false)) {
            is Got.Refused -> CarPlayAnswer.Refused(got.words)
            Got.Nothing -> CarPlayAnswer.Play(alone)
            is Got.Songs -> {
                val start = got.list.ids.indexOf(node.trackId)
                if (start < 0) {
                    CarPlayAnswer.Play(alone.copy(source = got.list.source, network = got.list.network))
                } else {
                    CarPlayAnswer.Play(CarPlay(got.list.ids, start, got.list.source, got.list.network))
                }
            }
        }
    }

    private sealed interface Got {
        class Songs(val list: CarList) : Got
        object Nothing : Got
        class Refused(val words: String) : Got
    }

    // The list's songs, or the words for why there are none to be had.
    private suspend fun load(node: CarNode, fresh: Boolean): Got {
        if (needsServer(node) && !signedIn()) return Got.Refused(CAR_SIGN_IN)
        val name = nameOf(node)
        return try {
            songsOf(node, fresh)?.let { Got.Songs(it) } ?: Got.Nothing
        } catch (e: CancellationException) {
            throw e
        } catch (e: SubsonicException.NotFound) {
            listIdOf(node)?.let(gone)
            Got.Refused(name?.let(::goneLine) ?: e.userMessage())
        } catch (e: SubsonicException) {
            Got.Refused(e.userMessage())
        } catch (e: Exception) {
            Got.Refused(name?.let(::couldNotStartLine) ?: e.userMessage())
        }
    }

    private fun emptyWords(node: CarNode): String? = when (node) {
        CarNode.Resume -> CAR_RESUME_GONE
        is CarNode.Station, is CarNode.OctoList, is CarNode.ChartList -> nothingOnLine(nameOf(node).orEmpty())
        else -> null
    }
}

// Lists only the server holds: asking for them needs a server signed in.
fun needsServer(node: CarNode): Boolean = when (node) {
    is CarNode.OctoList, is CarNode.Station, is CarNode.ChartList, is CarNode.OnlineAlbum, is CarNode.OnlineArtist,
    CarNode.Resume, CarNode.Chart, CarNode.Charts, is CarNode.Retry,
    -> true
    is CarNode.Part -> needsServer(node.parent)
    else -> false
}

private fun nameOf(node: CarNode): String? = when (node) {
    is CarNode.OctoList -> node.name
    is CarNode.Station -> node.name
    is CarNode.ChartList -> node.name
    else -> null
}

// The server's id of a list it may say it no longer has.
private fun listIdOf(node: CarNode): String? = when (node) {
    is CarNode.OctoList -> node.id
    is CarNode.Station -> node.id
    else -> null
}

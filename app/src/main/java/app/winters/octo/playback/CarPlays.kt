package app.winters.octo.playback

import app.winters.octo.data.userMessage
import app.winters.octo.listening.TotalsFailed
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.CancellationException

// A list a car plays from, named by what it is: the list, its queue
// heading, whether it waits on the server, where to begin (its top for
// null), and, as a page says it, that it shows more than QUEUE_WINDOW songs
// and the owner of the listing the car shows of it.
data class CarList(
    val spec: ListSpec,
    val source: QueueSource,
    val network: Boolean = false,
    val start: ListStart? = null,
    val shownOverWindow: Boolean = false,
    val pageOwner: String? = null,
)

// What a choice made in a car plays: the list, where it starts, under which
// heading, whether it waited on the server, whether it is shuffled, what a
// page says of it, and the words for a list that turns out empty.
data class CarPlay(
    val spec: ListSpec,
    val start: ListStart?,
    val source: QueueSource,
    val network: Boolean,
    val shuffle: Boolean = false,
    val shownOverWindow: Boolean = false,
    val pageOwner: String? = null,
    val emptyWords: String = CAR_NOTHING_TO_PLAY,
)

// A list of songs named one by one, at most QUEUE_WINDOW of them.
fun carIds(ids: List<String>, source: QueueSource, network: Boolean = false, start: Int? = null): CarList =
    CarList(ListSpec.Ids(ids.take(QUEUE_WINDOW)), source, network, start?.let { ListStart.At(it.coerceIn(0, (ids.size - 1).coerceAtLeast(0)), 0) })

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

fun nothingOnLine(name: String): String = "Nothing is playing on $name right now"

fun goneLine(name: String): String = "$name isn't on the server any more"

fun couldNotStartLine(name: String): String = "Could not start $name"

// What a choice made in a car plays. `songsOf` gives a list by what it is:
// `fresh` asks the server again (a list's own play), otherwise what the car
// was last shown serves (a song chosen inside it). `signedIn` says whether a
// server can be asked at all, and `gone` hears of a list the server no
// longer has. A song chosen in a list plays that list from it; the queue
// plays it alone when the list no longer holds it.
class CarPlays(
    private val songsOf: suspend (CarNode, Boolean) -> CarList?,
    private val signedIn: () -> Boolean,
    private val gone: (String) -> Unit,
) {
    // Null only for an id that is not a car's (the app's own requests). A
    // car's choice that holds nothing to play (a place that does not play,
    // a list gone or empty) is refused, so the queue stays as it is; a list
    // found empty only once the queue reads it is refused with `emptyWords`.
    suspend fun playFor(id: String): CarPlayAnswer? {
        val node = parseCarId(id) ?: return null
        if (node is CarNode.Song) return song(node)
        val empty = emptyWords(node) ?: CAR_NOTHING_TO_PLAY
        return when (val got = load(node, fresh = true)) {
            is Got.Refused -> CarPlayAnswer.Refused(got.words)
            Got.Nothing -> CarPlayAnswer.Refused(empty)
            is Got.Songs -> if ((got.list.spec as? ListSpec.Ids)?.ids?.isEmpty() == true) {
                CarPlayAnswer.Refused(empty)
            } else {
                CarPlayAnswer.Play(got.list.play(got.list.start, empty))
            }
        }
    }

    private suspend fun song(node: CarNode.Song): CarPlayAnswer {
        val alone = CarPlay(ListSpec.Ids(listOf(node.trackId)), null, NoSource, network = false)
        val parent = node.list ?: return CarPlayAnswer.Play(alone)
        return when (val got = load(parent, fresh = false)) {
            is Got.Refused -> CarPlayAnswer.Refused(got.words)
            Got.Nothing -> CarPlayAnswer.Play(alone)
            is Got.Songs -> CarPlayAnswer.Play(got.list.play(ListStart.Track(node.trackId), CAR_NOTHING_TO_PLAY))
        }
    }

    private fun CarList.play(start: ListStart?, empty: String) =
        CarPlay(spec, start, source, network, shownOverWindow = shownOverWindow, pageOwner = pageOwner, emptyWords = empty)

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
        } catch (e: TotalsFailed) {
            Got.Refused(e.words)
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

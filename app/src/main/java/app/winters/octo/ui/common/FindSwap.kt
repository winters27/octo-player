package app.winters.octo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.winters.octo.catalog.TrackEntity
import kotlinx.coroutines.delay

// How long a find's row keeps its check once the song is in the library,
// from when the check starts drawing: the draw and a short hold.
const val CHECK_HOLD_MS = 1_200L

// How long the row's words and artwork take to fade into the library song's.
const val SWAP_FADE_MS = 300

// What a row for a song found online shows: the find, the check saying it
// is now in the library, or the library song it became.
enum class FindFace { Find, Check, Library }

// The face a find's row moves to, given whether its song is in the library
// (null while that is not known yet). A row that opens on a song already in
// the library shows the library song straight away. One whose song arrives
// while it is shown has the check's moment first, where the row has the add
// button; elsewhere it turns at once.
fun nextFindFace(face: FindFace?, adopted: Boolean?, offersAdd: Boolean): FindFace? = when {
    adopted == null -> face
    face == null -> if (adopted) FindFace.Library else FindFace.Find
    face == FindFace.Find && adopted -> if (offersAdd) FindFace.Check else FindFace.Library
    else -> face
}

// The face once the check has had its moment.
fun afterCheck(face: FindFace?): FindFace? = if (face == FindFace.Check) FindFace.Library else face

// How long the turn into the library song fades. Only a row that showed the
// find fades; one that opened on the library song, or with reduced motion,
// swaps at once.
fun swapFadeMs(sawFind: Boolean, calm: Boolean): Int = if (sawFind && !calm) SWAP_FADE_MS else 0

// Where a find's row is on its way to the library song.
class FindSwap(val face: FindFace?, val library: TrackEntity?, val fadeMs: Int)

// Follows a find's row from the find to the library song it became. The
// library song is only looked up once the find is in the library.
@Composable
internal fun rememberFindSwap(findId: String, offersAdd: Boolean, calm: Boolean): FindSwap {
    val adoptions = LocalAdoptions.current
    val libraryId = adoptions?.get(findId)
    var face by remember(findId) { mutableStateOf<FindFace?>(null) }
    var sawFind by remember(findId) { mutableStateOf(false) }
    val next = nextFindFace(face, adoptions?.let { libraryId != null }, offersAdd)
    SideEffect {
        face = next
        if (next == FindFace.Find) sawFind = true
    }
    if (next == FindFace.Check) {
        LaunchedEffect(findId) {
            delay(CHECK_HOLD_MS)
            face = afterCheck(face)
        }
    }
    // Looked up from the check on, so it is ready when the row turns.
    val library = if (next == FindFace.Check || next == FindFace.Library) rememberLibrarySong(libraryId) else null
    return FindSwap(next, library.takeIf { next == FindFace.Library }, swapFadeMs(sawFind || next == FindFace.Find, calm))
}

@Composable
private fun rememberLibrarySong(id: String?): TrackEntity? {
    val songs = LocalAdoptedSongs.current
    if (id == null || songs == null) return null
    val flow = remember(id, songs) { songs.song(id) }
    val song by flow.collectAsStateWithLifecycle(songs.lastSeen(id))
    return song
}

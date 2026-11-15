package app.winters.octo.subsonic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

// The OpenSubsonic extension a server lists when it takes a call's params
// in a form body, so a long list of songs fits in one call.
const val FORM_POST_EXTENSION = "formPost"

// How many song ids go in one call when they must fit in the address. 150
// ids come to about 5 KB, under the 8 KB most servers and proxies allow.
const val PLAYLIST_SONGS_PER_CALL = 150

// One call that changes a playlist. A param name may repeat.
data class PlaylistCall(val endpoint: String, val params: List<Pair<String, String>>)

// The songs split into the calls that carry them. With a form body they all
// go at once; otherwise in batches. There is always at least one batch, so
// an empty list still makes its call.
fun songBatches(songIds: List<String>, formPost: Boolean, perCall: Int = PLAYLIST_SONGS_PER_CALL): List<List<String>> =
    if (formPost || songIds.size <= perCall) listOf(songIds) else songIds.chunked(perCall)

// The calls that make a playlist's songs exactly these, in this order.
// createPlaylist with a playlistId replaces the whole list; batches after
// the first are added to the end with updatePlaylist.
fun replaceSongsCalls(
    playlistId: String,
    songIds: List<String>,
    formPost: Boolean,
    perCall: Int = PLAYLIST_SONGS_PER_CALL,
    ifStamp: String? = null,
    ownIds: Boolean = false,
): List<PlaylistCall> {
    val batches = songBatches(songIds, formPost, perCall)
    val first = PlaylistCall("createPlaylist", replaceParams(playlistId, ifStamp, ownIds) + batches.first().map { "songId" to it })
    return listOf(first) + addSongsCalls(playlistId, batches.drop(1))
}

// The param a replace carries so the server refuses it when the playlist
// changed since the phone read it (the changed time the server sent), and
// the one that says the replace names the server's own ids (no 1.12.0
// title matches to pair).
const val IF_STAMP_PARAM = "octoIfStamp"
const val OWN_IDS_PARAM = "octoOwnIds"

// A replace's first params: the playlist, then the stamp and own-ids marks
// when given. Only the first call of a replace carries them: the calls
// after it add to the list it made.
internal fun replaceParams(playlistId: String, ifStamp: String?, ownIds: Boolean): List<Pair<String, String>> =
    listOf("playlistId" to playlistId) +
        listOfNotNull(ifStamp?.takeIf(String::isNotEmpty)?.let { IF_STAMP_PARAM to it }, if (ownIds) OWN_IDS_PARAM to "1" else null)

// The ids an answer to a playlist change says the server skipped
// (octoSkipped), from the answer's own object; none when it names none.
internal fun skippedIn(answer: JsonObject?): List<String> =
    (answer?.get("octoSkipped") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

// The same, from a whole answer's text.
internal fun skippedInBody(body: String): List<String> =
    skippedIn(runCatching { Json.parseToJsonElement(body).jsonObject["subsonic-response"]?.jsonObject }.getOrNull())

// The calls that make a new playlist: the first batch goes with the name,
// and the rest are added once the server says the new id.
fun createCalls(name: String, songIds: List<String>, formPost: Boolean, perCall: Int = PLAYLIST_SONGS_PER_CALL): Pair<PlaylistCall, List<List<String>>> {
    val batches = songBatches(songIds, formPost, perCall)
    return PlaylistCall("createPlaylist", listOf("name" to name) + batches.first().map { "songId" to it }) to batches.drop(1)
}

// One updatePlaylist call per batch, each adding its songs to the end.
fun addSongsCalls(playlistId: String, batches: List<List<String>>): List<PlaylistCall> =
    batches.filter { it.isNotEmpty() }.map { batch ->
        PlaylistCall("updatePlaylist", listOf("playlistId" to playlistId) + batch.map { "songIdToAdd" to it })
    }

// The calls for updatePlaylist. Removals go by position in the list as it is
// before the call, and may repeat; added songs go to the end. Songs past the
// first batch follow in calls of their own.
fun updateCalls(
    playlistId: String,
    name: String?,
    comment: String?,
    public: Boolean?,
    songIdsToAdd: List<String>,
    songIndexesToRemove: List<Int>,
    songsToRemove: List<Pair<String, Int>> = emptyList(),
    formPost: Boolean,
    perCall: Int = PLAYLIST_SONGS_PER_CALL,
): List<PlaylistCall> {
    val batches = songBatches(songIdsToAdd, formPost, perCall)
    val params = buildList {
        add("playlistId" to playlistId)
        name?.let { add("name" to it) }
        comment?.let { add("comment" to it) }
        public?.let { add("public" to "$it") }
        songIndexesToRemove.forEach { add("songIndexToRemove" to "$it") }
        // Songs taken out by id and copy go as octoSongToRemove (Octo's family playlists).
        songsToRemove.forEach { (id, copy) -> add("octoSongToRemove" to "$id@$copy") }
        batches.first().forEach { add("songIdToAdd" to it) }
    }
    return listOf(PlaylistCall("updatePlaylist", params)) + addSongsCalls(playlistId, batches.drop(1))
}

// createPlaylist's answer. Servers since API 1.14 send the playlist back;
// older ones send nothing.
@Serializable
internal data class CreatedPlaylist(val playlist: PlaylistWithSongs? = null)

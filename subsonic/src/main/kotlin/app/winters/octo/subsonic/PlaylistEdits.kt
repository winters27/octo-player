package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

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
): List<PlaylistCall> {
    val batches = songBatches(songIds, formPost, perCall)
    val first = PlaylistCall("createPlaylist", listOf("playlistId" to playlistId) + batches.first().map { "songId" to it })
    return listOf(first) + addSongsCalls(playlistId, batches.drop(1))
}

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
        batches.first().forEach { add("songIdToAdd" to it) }
    }
    return listOf(PlaylistCall("updatePlaylist", params)) + addSongsCalls(playlistId, batches.drop(1))
}

// createPlaylist's answer. Servers since API 1.14 send the playlist back;
// older ones send nothing.
@Serializable
internal data class CreatedPlaylist(val playlist: PlaylistWithSongs? = null)

package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

// The OpenSubsonic extension an Octo server lists for a caller who may
// choose the YouTube video an outside song streams from (getStreamVersions,
// setStreamVersion). A choice plays for everyone on the server. A family's
// Listener and Kid accounts are not offered it.
const val STREAM_VERSIONS_EXTENSION = "octoStreamVersions"

// The extension every signed-in caller is offered: the outside songs whose
// chosen version changed after a cursor (getStreamVersionChanges), so a
// device drops its saved copies of them.
const val STREAM_VERSION_CHANGES_EXTENSION = "octoStreamVersionChanges"

// The `video` setStreamVersion takes to go back to Octo's own pick.
const val STREAM_VERSION_AUTO = "auto"

// The request parameter every stream request of one play carries: see
// playToken.
const val PLAY_TOKEN_PARAM = "octoPlay"

// The response header an outside song's stream names the video it was made
// from in, so a saved copy of another video is told from it.
const val VIDEO_HEADER = "X-Octo-Video"

// Where an outside song plays from for the caller (StreamVersions.playsFrom):
// a stream of its video, a library file, or a download of every play.
const val PLAYS_FROM_STREAM = "stream"
const val PLAYS_FROM_FILE = "file"
const val PLAYS_FROM_DOWNLOAD = "download"

// Who picked the video playing (StreamVersionPlaying.picked): a person, a
// pick the server kept from before without its record, or Octo itself.
const val PICKED_PERSON = "person"
const val PICKED_EARLIER = "earlier"
const val PICKED_OCTO = "octo"

// What a video is (StreamVersion.kind).
const val VERSION_KIND_RELEASE = "release"
const val VERSION_KIND_MUSIC_VIDEO = "musicVideo"
const val VERSION_KIND_VIDEO = "video"

// The sources a listing could not hear from (StreamVersions.missed).
const val MISSED_YOUTUBE_MUSIC = "youtubeMusic"
const val MISSED_YOUTUBE = "youtube"

// An outside song's versions, as getStreamVersions and setStreamVersion
// answer: where it plays from for the caller, the video playing and who
// picked it, the caller's latest heard video when it is another, whether
// the caller's own download of the song runs, the other videos it can play
// from, the sources that could not answer, and every id the song has (a
// pick's answer lists the ids whose saved copies go).
@Serializable
data class StreamVersions(
    val id: String,
    val playsFrom: String,
    val playing: StreamVersionPlaying? = null,
    val heardVideoId: String? = null,
    val busy: Boolean = false,
    val version: List<StreamVersion> = emptyList(),
    val missed: List<String> = emptyList(),
    val ids: List<String> = emptyList(),
)

// One other video the song can stream from. `duration` is the video's, in
// seconds.
@Serializable
data class StreamVersion(
    val videoId: String,
    val title: String,
    val channel: String? = null,
    val duration: Int? = null,
    val kind: String,
)

// The video the song streams from now, with what is known of it and who
// picked it. `mine` is whether the caller did; `pickedAt` is ISO 8601.
@Serializable
data class StreamVersionPlaying(
    val videoId: String,
    val title: String? = null,
    val channel: String? = null,
    val duration: Int? = null,
    val kind: String? = null,
    val picked: String,
    val pickedBy: String? = null,
    val mine: Boolean = false,
    val pickedAt: String? = null,
)

// The outside songs whose chosen version changed after a cursor, and the
// cursor to send next time (`epoch`, `seq`). Not `complete`: another epoch,
// a cursor older than the server keeps, or more changes than one answer
// lists, so every saved outside song goes.
@Serializable
data class StreamVersionChanges(
    val epoch: String,
    val seq: Long,
    val complete: Boolean,
    val change: List<StreamVersionChange> = emptyList(),
)

// One change: its number and every id of its song.
@Serializable
data class StreamVersionChange(
    val seq: Long,
    val ids: List<String>,
)

// The token every stream request of one play carries (PLAY_TOKEN_PARAM):
// the queue entry's id and how many times a version pick restarted it. A
// rebuffer, seek, reconnect, Repeat one or Previous is the same play and
// keeps it.
fun playToken(entryId: String, restarts: Int): String = "$entryId.$restarts"

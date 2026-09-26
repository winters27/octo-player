package app.winters.octo.folders

import app.winters.octo.catalog.TrackEntity
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.discovery.Discovery
import app.winters.octo.subsonic.DirectoryRef
import app.winters.octo.subsonic.MusicDirectory
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Most songs one Play or Shuffle gathers from under a server folder.
const val SERVER_SONG_LIMIT = 2_000

// Most folders one Play or Shuffle opens on the way.
private const val SERVER_FOLDER_LIMIT = 400

// One level of the server's folders, ready to show: its folders, then its
// songs as the app shows them.
class ServerLevel(val folders: List<DirectoryRef>, val songs: List<TrackEntity>)

// Browses the signed-in server by folder, live. Songs come back as the
// library has them when it does, so they play the phone's copy, and as
// finds otherwise. Calls throw SubsonicException when the server cannot
// answer, and answer null when no server is signed in.
@Singleton
class ServerFolders @Inject constructor(
    private val sessions: SessionRepository,
    private val discovery: Discovery,
) {
    // What to call the signed-in server, or null while none is. Unknown
    // while the saved sign-in is still being read.
    val name: Flow<ServerName> = sessions.state.map { state ->
        when (state) {
            SessionState.Loading -> ServerName.Unknown
            is SessionState.SignedIn -> ServerName.Known(
                serverName(state.session.isOcto, state.session.serverType, state.session.client.primaryUrl.host),
            )
            else -> ServerName.None
        }
    }

    // One level: the top when `id` is null, otherwise that folder.
    suspend fun level(id: String?): ServerLevel? {
        val client = client() ?: return null
        val (folders, songs) = if (id == null) {
            client.indexes().let { it.folders to it.songs }
        } else {
            client.musicDirectory(id).let { it.folders to it.songs }
        }
        return ServerLevel(folders, discovery.resolveFromServer(songs))
    }

    // Every song under a folder, up to the limit, in the order shown.
    suspend fun songsUnder(id: String?): Gathered<TrackEntity>? {
        val client = client() ?: return null
        val start = if (id == null) client.indexes().let { root -> FolderContents(root.folders.map { it.id }, root.songs) }
        else client.musicDirectory(id).contents()
        val gathered = gatherSongs(start, SERVER_SONG_LIMIT, SERVER_FOLDER_LIMIT) { client.musicDirectory(it).contents() }
        return Gathered(discovery.resolveFromServer(gathered.songs), gathered.limitReached)
    }

    private fun MusicDirectory.contents() = FolderContents(folders.map { it.id }, songs)

    private fun client(): SubsonicClient? = (sessions.state.value as? SessionState.SignedIn)?.session?.client
}

sealed interface ServerName {
    data object Unknown : ServerName
    data object None : ServerName
    data class Known(val name: String) : ServerName
}

// A name for the server that people would recognise: Octo, the kind of
// server it says it is, or failing both its address.
fun serverName(isOcto: Boolean, type: String?, host: String): String = when {
    isOcto -> "Octo"
    !type.isNullOrBlank() -> type.trim().replaceFirstChar { it.uppercaseChar() }
    else -> host
}

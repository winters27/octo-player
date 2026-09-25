package app.winters.octo.data

import app.winters.octo.subsonic.AlbumListType
import app.winters.octo.subsonic.Playlist
import app.winters.octo.subsonic.SubsonicClient
import javax.inject.Inject
import javax.inject.Singleton

// Library reads for the signed-in server.
@Singleton
class LibraryRepository @Inject constructor(private val sessions: SessionRepository) {
    private var playlistsFor: SubsonicClient? = null
    private var playlistsCache: List<Playlist>? = null

    private fun client(): SubsonicClient =
        (sessions.state.value as? SessionState.SignedIn)?.session?.client
            ?: throw IllegalStateException("Not signed in")

    suspend fun albumList(type: AlbumListType, size: Int, offset: Int = 0) = client().albumList(type, size, offset)
    suspend fun album(id: String) = client().album(id)
    suspend fun artists() = client().artists()
    suspend fun artist(id: String) = client().artist(id)
    suspend fun playlist(id: String) = client().playlist(id)
    suspend fun search(query: String) = client().search(query)
    suspend fun starred() = client().starred()

    // Octo sets things up the first time playlists are listed, so the list
    // is fetched once per sign-in unless someone asks for a refresh.
    suspend fun playlists(refresh: Boolean = false): List<Playlist> {
        val client = client()
        val cached = playlistsCache
        if (!refresh && cached != null && playlistsFor === client) return cached
        return client.playlists().also {
            playlistsCache = it
            playlistsFor = client
        }
    }
}

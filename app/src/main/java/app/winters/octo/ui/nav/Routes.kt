package app.winters.octo.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute : NavKey
@Serializable data object SearchRoute : NavKey
@Serializable data object LibraryRoute : NavKey
@Serializable data object SettingsRoute : NavKey
@Serializable data class AlbumRoute(val id: String) : NavKey
@Serializable data class ArtistRoute(val id: String) : NavKey

// An album or artist on the server, not in the library, by the server's id.
@Serializable data class OnlineAlbumRoute(val id: String) : NavKey
@Serializable data class OnlineArtistRoute(val id: String) : NavKey

@Serializable data object AlbumsRoute : NavKey
@Serializable data object ArtistsRoute : NavKey
@Serializable data object SongsRoute : NavKey
@Serializable data object GenresRoute : NavKey
@Serializable data class GenreRoute(val name: String) : NavKey
@Serializable data object PlaylistsRoute : NavKey
@Serializable data object LikedRoute : NavKey
@Serializable data class PlaylistRoute(val id: String) : NavKey
@Serializable data object SignInRoute : NavKey

// The sign-in form, starting from the saved connection to change it.
@Serializable data object EditConnectionRoute : NavKey
@Serializable data object OctoAdminRoute : NavKey

// The server's shared links, and its internet radio stations.
@Serializable data object SharesRoute : NavKey
@Serializable data object RadioStationsRoute : NavKey

// The equalizer and everything else that shapes the sound.
@Serializable data object SoundRoute : NavKey

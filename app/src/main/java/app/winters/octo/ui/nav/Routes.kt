package app.winters.octo.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute : NavKey
@Serializable data object SearchRoute : NavKey
@Serializable data object LibraryRoute : NavKey
@Serializable data object SettingsRoute : NavKey
@Serializable data class AlbumRoute(val id: String) : NavKey
@Serializable data class ArtistRoute(val id: String) : NavKey

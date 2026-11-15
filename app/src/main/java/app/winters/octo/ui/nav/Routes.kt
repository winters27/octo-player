package app.winters.octo.ui.nav

import androidx.navigation3.runtime.NavKey
import app.winters.octo.data.SignInAsk
import app.winters.octo.ui.settings.SettingsPage
import app.winters.octo.query.LibraryQuery
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute : NavKey
@Serializable data object SearchRoute : NavKey
@Serializable data object LibraryRoute : NavKey
@Serializable data object SettingsRoute : NavKey

// One page of Settings, and the row a search result points at, if any.
@Serializable data class SettingsPageRoute(val page: SettingsPage, val highlight: String? = null) : NavKey

@Serializable data class AlbumRoute(val id: String) : NavKey
@Serializable data class ArtistRoute(val id: String) : NavKey

// An album or artist on the server, not in the library, by the server's id.
@Serializable data class OnlineAlbumRoute(val id: String) : NavKey
@Serializable data class OnlineArtistRoute(val id: String) : NavKey

// A list the server makes (a station, a Made for you list), by its id, with
// the name and cover it was shown with. A station (`radio`) keeps going once
// played; a list ends with its songs.
@Serializable data class ServerListRoute(val id: String, val name: String, val artwork: String?, val radio: Boolean = false) : NavKey

@Serializable data object AlbumsRoute : NavKey
@Serializable data object ArtistsRoute : NavKey
@Serializable data object SongsRoute : NavKey
@Serializable data object GenresRoute : NavKey
@Serializable data class GenreRoute(val name: String) : NavKey
@Serializable data object FoldersRoute : NavKey
@Serializable data object PlaylistsRoute : NavKey

// What is worth fixing in the library's files, and one check's songs.
@Serializable data object LibraryHealthRoute : NavKey
@Serializable data class HealthCheckRoute(val check: String) : NavKey

// Songs deleted from the server's disk, still in its trash.
@Serializable data object HealthTrashRoute : NavKey

// Liked songs: the Favourites page, opened on its songs. The way in for
// anything that means hearted songs, and what a back stack saved when this
// was a page of its own still lands on.
@Serializable data object LikedRoute : NavKey

// Liked songs, favourite albums and favourite artists. From the Favourite
// albums shelf it opens on the albums.
@Serializable data class FavouritesRoute(val albums: Boolean = false) : NavKey
@Serializable data object DownloadsRoute : NavKey
@Serializable data class PlaylistRoute(val id: String) : NavKey

// A playlist folder's page.
@Serializable data class PlaylistFolderRoute(val id: String) : NavKey

// A live list: songs picked by rules, kept on the phone.
@Serializable data class LiveListRoute(val id: String) : NavKey

// A live list's editor: an existing one by `id`, or a new one, begun from
// `start` (a page's filters) when given.
@Serializable data class LiveListEditRoute(val id: String? = null, val start: LibraryQuery? = null) : NavKey
@Serializable data object SignInRoute : NavKey

// The sign-in form, starting from the saved connection to change it.
@Serializable data object EditConnectionRoute : NavKey

// What the sign-in form is for, opened from the list of servers.
@Serializable
enum class ServerForm {
    // Keep another server without leaving the one in use.
    Add,

    // Change a kept server's details.
    Edit,

    // Sign in again to a kept server, which then is the one in use.
    SignIn,
}

// The sign-in form for one of the kept servers (`id`), or a new one, with a
// note to show first (why its password is asked for). `asked`: Octo opened
// it by itself (a sign-in it asked for), not the person.
@Serializable data class ServerFormRoute(val form: ServerForm, val id: String? = null, val note: String? = null, val asked: Boolean = false) : NavKey
@Serializable data object OctoAdminRoute : NavKey
@Serializable data object ImportRoute : NavKey

// Family on the server in use: what the account may do, requests, devices and, for a
// manager, the members and the requests waiting.
@Serializable data object FamilyRoute : NavKey

// Signing up or signing in, filled in from a family link: which opening of
// it, so each opening is a page (and a view model) of its own. A back stack
// saved before openings were counted reads as 0.
@Serializable data class FamilyJoinRoute(val link: String, val opened: Long = 0) : NavKey

// Opens a family link's page: one already on top is replaced, so going back
// never lands on a spent invite's form; on any other top it is added. One
// write either way.
fun MutableList<NavKey>.openJoin(route: FamilyJoinRoute) {
    if (lastOrNull() is FamilyJoinRoute) this[lastIndex] = route else add(route)
}

// Removes the topmost entry equal to `key`, and nothing else: a page that
// signs in closes itself this way, so a page pushed over it while it ran is
// never the one closed. Nothing changes when no entry is `key` (a page
// already gone).
fun MutableList<NavKey>.removeOwn(key: NavKey) {
    val at = lastIndexOf(key)
    if (at >= 0) removeAt(at)
}

// Removes every page of a sign-in Octo asked for by itself, and answers
// their asks, the lowest first.
fun MutableList<NavKey>.removeAsked(): List<SignInAsk> {
    val asked = filterIsInstance<ServerFormRoute>().filter { it.asked && it.id != null }
    removeAll { it is ServerFormRoute && it.asked && it.id != null }
    return asked.mapNotNull { route -> route.id?.let { SignInAsk(it, route.note) } }
}

// Where tapping the current tab again stops: its root, or, while a sign-in
// is open anywhere, the sign-in page nearest the top of this tab, which is
// landed on rather than thrown away.
fun reselectTo(stack: List<NavKey>, anyOpen: Boolean): Int {
    if (!anyOpen) return 0
    return stack.indexOfLast(::isSignInRoute).coerceAtLeast(0)
}

// Takes this tab back to where tapping it again stops.
fun MutableList<NavKey>.reselect(anyOpen: Boolean) {
    val keep = reselectTo(this, anyOpen)
    while (size > keep + 1) removeAt(lastIndex)
}

// The charts and new songs, on an Octo server that has them.
@Serializable data object ChartsRoute : NavKey

// The server's shared links, and its internet radio stations.
@Serializable data object SharesRoute : NavKey
@Serializable data object RadioStationsRoute : NavKey

// Live radio stations around a place, from the Radio Browser catalog.
@Serializable data object LiveRadioNearRoute : NavKey

// The equalizer and everything else that shapes the sound.
@Serializable data object SoundRoute : NavKey

// The order of Home's shelves, and which ones show.
@Serializable data object HomeSectionsRoute : NavKey

// What was played: the latest plays by day, or the most played songs.
@Serializable data class HistoryRoute(val mostPlayed: Boolean = false) : NavKey

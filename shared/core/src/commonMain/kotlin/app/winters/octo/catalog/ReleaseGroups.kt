package app.winters.octo.catalog

import app.winters.octo.subsonic.Album

// The shelves an artist page sorts their releases onto, in the order the
// page shows them. The phone's and the desktop's artist pages share them.
enum class ReleaseGroup(val title: String) {
    Albums("Albums"),
    SinglesAndEps("Singles and EPs"),
    Compilations("Compilations"),
    Live("Live"),
    AppearsOn("Appears on"),
}

// The shelf for one of the artist's own releases, from its OpenSubsonic
// release types (MusicBrainz's words, in any case). A compilation comes
// first, then a live release, then a single or an EP; anything else (an
// album, a soundtrack, a broadcast) is an album.
fun releaseGroupOf(types: List<String>, compilation: Boolean = false): ReleaseGroup {
    val words = types.map { it.trim().lowercase() }
    return when {
        compilation || "compilation" in words -> ReleaseGroup.Compilations
        "live" in words -> ReleaseGroup.Live
        "single" in words || "ep" in words -> ReleaseGroup.SinglesAndEps
        else -> ReleaseGroup.Albums
    }
}

// An artist's releases on their shelves, each shelf keeping the order it
// was given, and empty shelves left out. `own` is false for a release by
// someone else that the artist appears on. When the server sent no release
// types for any of the artist's own releases, nothing tells a single from
// an album, so they all go under Albums.
fun <T> groupReleases(
    items: List<T>,
    types: (T) -> List<String>,
    compilation: (T) -> Boolean = { false },
    own: (T) -> Boolean = { true },
): List<Pair<ReleaseGroup, List<T>>> {
    val typed = items.any { own(it) && types(it).isNotEmpty() }
    val byGroup = items.groupBy { item ->
        when {
            !own(item) -> ReleaseGroup.AppearsOn
            !typed -> ReleaseGroup.Albums
            else -> releaseGroupOf(types(item), compilation(item))
        }
    }
    return ReleaseGroup.entries.mapNotNull { group -> byGroup[group]?.let { group to it } }
}

// The same for albums as a server lists them.
fun groupAlbums(albums: List<Album>, own: (Album) -> Boolean = { true }): List<Pair<ReleaseGroup, List<Album>>> =
    groupReleases(albums, { it.releaseTypes }, { it.isCompilation }, own)

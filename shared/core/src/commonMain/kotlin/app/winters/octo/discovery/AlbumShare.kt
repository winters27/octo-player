package app.winters.octo.discovery

import app.winters.octo.subsonic.Album

// How much of an album found online the library already holds, as Octo
// counts it from the album's songs (under whatever album the library filed
// them). Library albums, and albums the server did not count, have no share.
enum class AlbumShare { None, Part, Whole }

fun albumShare(album: Album): AlbumShare? {
    val owned = album.ownedCount ?: return null
    if (!album.isExternal || album.songCount <= 0) return null
    return when {
        owned <= 0 -> AlbumShare.None
        owned >= album.songCount -> AlbumShare.Whole
        else -> AlbumShare.Part
    }
}

// The quiet line on the card of an album found online that the library
// holds in part: "2 of 11 in your library". Nothing otherwise, as the
// heading above the card already says where it stands.
fun albumShareLine(album: Album): String? =
    if (albumShare(album) == AlbumShare.Part) albumShareLine(album.ownedCount, album.songCount) else null

// The same from the counts alone: some of the songs, not none or all.
fun albumShareLine(owned: Int?, songs: Int): String? =
    if (owned != null && owned in 1 until songs) "$owned of $songs in your library" else null

// The heading over albums found online that the library holds in part.
const val PartlyInLibraryText = "Partly in your library"

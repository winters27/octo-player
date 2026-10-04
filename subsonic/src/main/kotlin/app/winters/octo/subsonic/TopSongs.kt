package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

// The OpenSubsonic extension an Octo server lists when it can rank the songs
// people play most: an artist's (getArtistTopSongs) and the chart of the
// moment (getTopChart). It is listed only while the server's search finds
// music online.
const val OCTO_TOP_SONGS = "octoTopSongs"

// Where a ranked list's order came from, as the server names it.
const val TOP_SONGS_LASTFM = "lastfm"
const val TOP_SONGS_DEEZER = "deezer"

// One ranked song. `plays` and `listeners` are Last.fm's counts, when the
// list was ranked by them. A song the library has comes as the library's
// song; any other is one found online, marked `isExternal`, that plays and
// can be added like any search row.
@Serializable
data class TopSong(
    val rank: Int = 0,
    val plays: Long? = null,
    val listeners: Long? = null,
    val inLibrary: Boolean = false,
    val song: Song? = null,
)

// A ranked list: an artist's, named as the server knows them, or the chart,
// which names no artist.
@Serializable
data class TopSongs(
    val artist: String? = null,
    val source: String = "",
    val entry: List<TopSong> = emptyList(),
)

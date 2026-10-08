package app.winters.octo.subsonic

import kotlinx.serialization.Serializable

// The OpenSubsonic extension an Octo server lists when it can rank the songs
// people play most: an artist's (getArtistTopSongs) and the chart of the
// moment (getTopChart). It is listed only while the server's search finds
// music online.
const val OCTO_TOP_SONGS = "octoTopSongs"

// The extension version whose getTopChart takes `chart` (Best New Songs,
// Trending Songs, a genre) and that answers getCharts.
const val OCTO_TOP_SONGS_CHARTS = 2

// Where a ranked list's order came from, as the server names it.
const val TOP_SONGS_LASTFM = "lastfm"
const val TOP_SONGS_DEEZER = "deezer"
const val TOP_SONGS_APPLE = "apple"

// The chart ids getTopChart takes: the overall chart, Best New Songs and
// Trending Songs; any other is an Apple genre's number.
const val CHART_OVERALL = "34"
const val CHART_NEW_SONGS = "new"
const val CHART_TRENDING = "trending"

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

// A ranked list: an artist's, named as the server knows them, or a chart,
// which names no artist but says which chart it is, its name as a playlist
// ("Top Hip-Hop/Rap") and the country it is for.
@Serializable
data class TopSongs(
    val artist: String? = null,
    val source: String = "",
    val chart: String? = null,
    val name: String? = null,
    val country: String? = null,
    val entry: List<TopSong> = emptyList(),
)

// One chart the server's country has. `label` is the chip's word
// ("Top songs", "Hip-Hop/Rap"), `name` the playlist's ("Top Hip-Hop/Rap"),
// `kind` overall, new, trending or genre, `on` whether it is a playlist for
// everyone, and `playlist` this listener's playlist id while it is one.
@Serializable
data class ChartChoice(
    val id: String = "",
    val name: String = "",
    val label: String = "",
    val kind: String = "",
    val playlist: String? = null,
    val on: Boolean = false,
)

// The charts the server's country has, Popular right now first.
@Serializable
data class ChartChoices(
    val country: String? = null,
    val chart: List<ChartChoice> = emptyList(),
)

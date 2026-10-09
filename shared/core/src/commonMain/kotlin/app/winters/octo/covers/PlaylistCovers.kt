package app.winters.octo.covers

import kotlinx.serialization.Serializable

// What a playlist's picture is: artwork designed for it (its name on
// colours from its music), or the covers of its first albums in a square.
@Serializable
enum class PlaylistCoverStyle { Designed, Mosaic }

// The setting's name and choices, the same on the phone and the desktop.
const val PLAYLIST_COVERS_SETTING = "Playlist covers"

fun playlistCoverStyleName(style: PlaylistCoverStyle): String = when (style) {
    PlaylistCoverStyle.Designed -> "Designed"
    PlaylistCoverStyle.Mosaic -> "Album mosaic"
}

// When each is the one to pick.
fun playlistCoverStyleHelp(style: PlaylistCoverStyle): String = when (style) {
    PlaylistCoverStyle.Designed -> "Each playlist's name on painted artwork picked by its music. Easy to tell apart at a glance, even in a long list."
    PlaylistCoverStyle.Mosaic -> "The covers of the first albums on it. Handy when you know a playlist by its music."
}

// The light line under a cover's name: what the list is.
const val PLAYLIST_COVER_LINE = "Playlist"
const val LIVE_LIST_COVER_LINE = "Live list"
const val STATION_COVER_LINE = "Station"
const val CHART_COVER_LINE = "Chart"
const val MIX_COVER_LINE = "Mix"

// The small line at a cover's foot: whose it is when it is someone else's,
// else how many songs it has (nothing while it has none).
fun playlistCoverFooter(songCount: Int, owner: String?, you: String?): String? = when {
    !owner.isNullOrBlank() && !you.isNullOrBlank() && !owner.equals(you, ignoreCase = true) -> "By $owner"
    songCount == 1 -> "1 song"
    songCount > 1 -> "%,d songs".format(songCount)
    else -> null
}

// Bumped whenever the drawing changes, so covers kept on disk are made again.
const val COVER_DESIGN_VERSION = 4

// Bumped whenever the colours are picked differently.
const val COVER_PALETTE_VERSION = 2

// The size a cover shown `px` wide is drawn at: the next step up, fine
// where a pixel shows (every 4 up to 128), coarser above (16 up to 512,
// then 64), so a few sizes serve every box without ever enlarging.
fun coverSide(px: Int): Int {
    val p = px.coerceIn(16, 2048)
    fun up(step: Int) = (p + step - 1) / step * step
    return when {
        p <= 128 -> up(4)
        p <= 512 -> up(16)
        else -> up(64)
    }
}

// What a drawn cover is kept under: the design's version, the book's and
// the library's, the background, the playlist, the size, and its words. A
// change to any of them is a new picture.
fun coverArtKey(spec: CoverSpec, side: Int, book: CoverBook = CoverBook.Default, library: CoverBackgrounds = CoverBackgrounds.Default): String {
    val words = coverHash(listOf(spec.name, spec.line.orEmpty(), spec.footer.orEmpty(), spec.coverTitle.orEmpty(), spec.glyph?.name.orEmpty()).joinToString("\n")).toString(36)
    val background = chooseBackground(spec.id, spec.palette, library, book.background).file.removeSuffix(".webp")
    val look = "${book.version}.${library.version}.$background.${coverOrientation(spec.id, book.background)}"
    return "playlist-art:v$COVER_DESIGN_VERSION:$look:${spec.id}:$side:$words"
}

// What a playlist's colours are kept under: the server, and the covers they
// come from (in order), so new first songs give new colours.
fun coverPaletteKey(server: String, sources: List<String>): String =
    "playlist-palette:v$COVER_PALETTE_VERSION:$server:${sources.joinToString("|")}"

// A name for a file holding something kept under `key`: short, and safe on
// every system.
fun coverFileName(key: String, extension: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())
    return digest.take(16).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } + "." + extension
}

// Up to four covers for a list's colours, one per album in the order its
// songs come: (album, cover) pairs in, cover ids out.
fun coverSources(albumCovers: List<Pair<String?, String?>>, most: Int = 4): List<String> =
    albumCovers
        .mapNotNull { (album, cover) -> cover?.let { (album ?: it) to it } }
        .distinctBy { it.first }
        .take(most)
        .map { it.second }

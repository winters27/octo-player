package app.winters.octo.widget

// What the playback service knows about the music, cut down to what a
// home screen widget can show. Null means nothing is loaded at all.
data class WidgetPlayback(
    val title: String?,
    val artist: String?,
    val isPlaying: Boolean,
)

// The three shapes of the now playing widget.
enum class WidgetSize { Small, Medium, Large }

// Widths and heights, in dp, where each shape starts. Anything narrower than
// Medium is Small, even when tall, since three buttons need the width.
const val MEDIUM_MIN_WIDTH_DP = 200f
const val LARGE_MIN_HEIGHT_DP = 100f
const val SMALL_MIN_WIDTH_DP = 110f
const val SMALL_MIN_HEIGHT_DP = 40f

fun widgetSize(widthDp: Float, heightDp: Float): WidgetSize = when {
    widthDp < MEDIUM_MIN_WIDTH_DP -> WidgetSize.Small
    heightDp < LARGE_MIN_HEIGHT_DP -> WidgetSize.Medium
    else -> WidgetSize.Large
}

// Exactly what one shape of the widget draws.
data class NowPlayingFace(
    val title: String,
    // Hidden when null.
    val artist: String?,
    val titleLines: Int,
    // Shows pause while playing, play otherwise.
    val playing: Boolean,
    val showSkips: Boolean,
    // Nothing loaded: the play button picks up the saved queue.
    val empty: Boolean,
)

const val NOTHING_PLAYING = "Nothing playing"
const val UNKNOWN_SONG = "Unknown song"

// Longer text is cut here before it goes to the launcher; the screen then
// ellipsizes whatever still does not fit.
const val TEXT_LIMIT = 80

fun nowPlayingFace(playback: WidgetPlayback?, size: WidgetSize): NowPlayingFace {
    if (playback == null) {
        return NowPlayingFace(NOTHING_PLAYING, null, 1, playing = false, showSkips = false, empty = true)
    }
    val title = clip(playback.title) ?: UNKNOWN_SONG
    val artist = clip(playback.artist)
    return when (size) {
        // Only room for the title next to the button.
        WidgetSize.Small -> NowPlayingFace(title, null, 1, playback.isPlaying, showSkips = false, empty = false)
        WidgetSize.Medium -> NowPlayingFace(title, artist, 1, playback.isPlaying, showSkips = true, empty = false)
        // Long titles get a second line.
        WidgetSize.Large -> NowPlayingFace(title, artist, 2, playback.isPlaying, showSkips = true, empty = false)
    }
}

// Blank text counts as missing; very long text is cut with an ellipsis.
fun clip(text: String?, limit: Int = TEXT_LIMIT): String? {
    val trimmed = text?.trim()?.replace(Regex("\\s+"), " ")
    if (trimmed.isNullOrEmpty()) return null
    return if (trimmed.length <= limit) trimmed else trimmed.take(limit - 1).trimEnd() + "…"
}

// The four shortcuts on the quick play widget: liked songs shuffled, the
// newest albums, a few random albums shuffled together, and the last queue.
enum class QuickPick { Liked, RecentlyAdded, AlbumMix, Resume }

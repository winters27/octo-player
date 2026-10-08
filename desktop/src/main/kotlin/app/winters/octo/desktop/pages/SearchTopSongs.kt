package app.winters.octo.desktop.pages

import app.winters.octo.desktop.ui.MarkedTitle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.lengthText
import app.winters.octo.desktop.search.SearchTops
import app.winters.octo.desktop.system.shownAlbum
import app.winters.octo.desktop.ui.DOUBLE_CLICK_MS
import app.winters.octo.desktop.ui.LibraryMark
import app.winters.octo.desktop.ui.LocalPointer
import app.winters.octo.desktop.ui.SectionTitle
import app.winters.octo.desktop.ui.SongMenu
import app.winters.octo.desktop.ui.isOutside
import app.winters.octo.desktop.ui.onRightClick
import app.winters.octo.desktop.ui.rememberLibraryCopies
import app.winters.octo.discovery.SEARCH_TOP_SHOWN
import app.winters.octo.discovery.TOP_CHART_SHOWN
import app.winters.octo.discovery.knownLengthMs
import app.winters.octo.discovery.playsText
import app.winters.octo.discovery.rankedBy
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.TopSong
import app.winters.octo.subsonic.TopSongs

// The searched artist's most played songs, ranked, under the artists: the
// first few until "Show all". Songs in the library play from it and have
// its check, a copy found online of one it holds included; the others play
// from the server and have the "+" that adds them. Only from an Octo
// server that ranks them.
internal fun LazyListScope.artistTopSongs(app: AppState, tops: SearchTops) {
    val list = tops.artist ?: return
    rankedSongs(
        app,
        key = "top",
        title = "Top songs",
        detail = listOfNotNull(list.artist, rankedBy(list.source)).joinToString(" · "),
        list = list,
        shown = SEARCH_TOP_SHOWN,
        open = tops.artistOpen,
        onOpen = { tops.artistOpen = it },
        withArtist = false,
    )
}

// The chart of the moment, for a search with nothing typed yet. Asked for
// once it shows.
internal fun LazyListScope.topChart(app: AppState, tops: SearchTops) {
    item(key = "chart-ask") { LaunchedEffect(tops) { tops.loadChart() } }
    val list = tops.chart ?: return
    rankedSongs(
        app,
        key = "chart",
        title = "Popular right now",
        detail = rankedBy(list.source).orEmpty(),
        list = list,
        shown = TOP_CHART_SHOWN,
        open = tops.chartOpen,
        onOpen = { tops.chartOpen = it },
        withArtist = true,
    )
}

private fun LazyListScope.rankedSongs(
    app: AppState,
    key: String,
    title: String,
    detail: String,
    list: TopSongs,
    shown: Int,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    withArtist: Boolean,
) {
    val entries = list.entry.filter { it.song != null }
    if (entries.isEmpty()) return
    val songs = entries.map { it.song!! }
    item(key = "$key-title") {
        Column {
            SectionTitle(
                title,
                action = when {
                    entries.size <= shown -> null
                    open -> "Show fewer"
                    else -> "Show all ${entries.size}"
                },
            ) { onOpen(!open) }
            if (detail.isNotEmpty()) Txt(detail, OctoType.caption, OctoColors.TextMuted, Modifier.padding(bottom = 8.dp))
        }
    }
    itemsIndexed(if (open) entries else entries.take(shown), key = { i, e -> "$key:$i:${e.song!!.id}" }) { index, entry ->
        RankedSong(app, songs, index, entry, withArtist)
    }
}

// One ranked song: its place, cover, title, album and Last.fm plays, length,
// and whether it is in the library. A click plays the list from it, as the
// songs found online do; a right click opens its menu.
@Composable
internal fun RankedSong(app: AppState, ranked: List<Song>, index: Int, entry: TopSong, withArtist: Boolean) {
    val songs = rememberLibraryCopies(app, ranked)
    val song = songs.getOrNull(index) ?: return
    val outside = isOutside(app, song)
    val pointer = LocalPointer.current
    val clicks = remember { longArrayOf(0L) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .hoverLift(RoundedCornerShape(8.dp))
            .onRightClick { app.popups.showAt(pointer.point) { close -> SongMenu(app, listOf(song), close, outside = outside) } }
            .clickable(role = Role.Button) {
                val now = System.currentTimeMillis()
                if (now - clicks[0] >= DOUBLE_CLICK_MS) app.play(songs, index)
                clicks[0] = now
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Txt("${entry.rank.takeIf { it > 0 } ?: (index + 1)}", OctoType.caption, OctoColors.TextMuted, Modifier.width(24.dp), align = TextAlign.End)
        Cover(song.coverArt, Modifier.size(38.dp), shape = RoundedCornerShape(6.dp), online = outside, placeholder = OctoIcons.Songs)
        Column(Modifier.weight(1f)) {
            MarkedTitle(song, OctoType.bodySmall) { Txt(song.title, OctoType.bodySmall, modifier = it) }
            val about = listOfNotNull(
                (song.displayArtist ?: song.artist).takeIf { withArtist && !it.isNullOrBlank() },
                shownAlbum(song).ifBlank { null },
                entry.plays?.let(::playsText),
            )
            if (about.isNotEmpty()) Txt(about.joinToString(" · "), OctoType.caption, OctoColors.TextMuted)
        }
        Txt(lengthText((knownLengthMs(song) / 1000).toInt()), OctoType.caption, OctoColors.TextMuted, Modifier.width(52.dp))
        LibraryMark(app, song, outside)
    }
}

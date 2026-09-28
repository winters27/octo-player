package app.winters.octo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.Cover
import app.winters.octo.desktop.library.formatName
import app.winters.octo.desktop.library.sampleRateText
import app.winters.octo.desktop.library.songFacts
import app.winters.octo.desktop.nav.Page
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.Separator
import app.winters.octo.design.Space
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.subsonic.Song

// The song's details: the one playing, or one picked with Song details,
// with a way back to the one playing. Its cover and names (as links), how
// it is playing now, then everything the server says about it, in the
// phone's words. The words can be selected and copied.
@Composable
fun InfoPanel(app: AppState, modifier: Modifier = Modifier) {
    val state by app.player.state.collectAsState()
    val playing = state.current?.song
    val picked = app.infoSong?.takeIf { it.id != playing?.id }
    val song = picked ?: playing
    if (song == null) {
        Txt("Nothing is playing. Right-click any song and pick Song details to see it here.", DesktopType.body, OctoColors.TextMuted, modifier.padding(Space.Xl), maxLines = 4)
        return
    }
    val server = app.connection?.server?.let { saved ->
        saved.serverType?.replaceFirstChar { it.uppercase() }?.let { "Your server ($it)" } ?: "Your server"
    }
    val facts = songFacts(song, server)
    LazyColumn(modifier, contentPadding = PaddingValues(start = Space.L, end = Space.L, top = Space.M, bottom = Space.Xl)) {
        if (picked != null) {
            item(key = "back") {
                Row(Modifier.fillMaxWidth().padding(bottom = Space.M), verticalAlignment = Alignment.CenterVertically) {
                    Txt("Not the song playing", DesktopType.meta, OctoColors.TextMuted, Modifier.weight(1f))
                    if (playing != null) TextAction("Show the song playing", { app.infoSong = null })
                }
            }
        }
        item(key = "head") { Head(app, song) }
        if (picked == null) {
            item(key = "now") {
                Txt(playingNow(song, state.playingOn?.name), DesktopType.meta, OctoColors.TextSecondary, Modifier.padding(vertical = Space.M), maxLines = 3)
                Separator()
            }
        }
        items(facts, key = { it.label }) { fact ->
            Row(Modifier.fillMaxWidth().padding(vertical = Space.S), horizontalArrangement = Arrangement.spacedBy(Space.L)) {
                Txt(fact.label, DesktopType.meta, OctoColors.TextMuted, Modifier.width(FactLabelWidth))
                SelectionContainer(Modifier.weight(1f)) {
                    Txt(fact.value, DesktopType.table, OctoColors.TextPrimary, maxLines = 6)
                }
            }
        }
    }
}

private val FactLabelWidth = Space.Wide * 2 + Space.Xl

// The cover and the names, each name opening its page.
@Composable
private fun Head(app: AppState, song: Song) {
    Row(Modifier.fillMaxWidth().padding(bottom = Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L)) {
        Cover(song.coverArt, Modifier.size(FrameSize.PlayerCover + Space.Section), shape = Corner.ArtMShape, placeholder = OctoIcons.Songs)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Xxs)) {
            Txt(song.title, DesktopType.emphasis, maxLines = 2)
            LinkText(song.displayArtist ?: song.artist.orEmpty(), song.artistId) { app.navigator.go(Page.Artist(it, song.artist.orEmpty())) }
            LinkText(song.album.orEmpty(), song.albumId) { app.navigator.go(Page.Album(it)) }
        }
    }
}

// How the song is playing, in a sentence: its format and where the sound
// goes, as far as the server and the system say.
fun playingNow(song: Song, device: String?): String {
    val format = listOfNotNull(
        formatName(song.suffix),
        song.bitDepth?.takeIf { it > 0 }?.let { "$it-bit" },
        song.samplingRate?.takeIf { it > 0 }?.let { sampleRateText(it) },
        song.bitRate?.takeIf { it > 0 && formatName(song.suffix)?.endsWith("lossless") != true }?.let { "$it kbps" },
    ).joinToString(", ")
    val where = device?.let { " on $it" }.orEmpty()
    return if (format.isEmpty()) "Playing$where." else "Playing $format$where."
}

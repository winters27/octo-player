package app.winters.octo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.winters.octo.design.DesktopType
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.Space
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.deleteFromDisk
import app.winters.octo.desktop.library.isOutsideSong
import app.winters.octo.desktop.system.isOpenedFile
import app.winters.octo.health.countText
import app.winters.octo.health.deleteBody
import app.winters.octo.health.deleteTitle
import app.winters.octo.subsonic.Song

// Deleting songs from the server's disk, from any song menu: only songs
// the library holds on an Octo server that lets this user do it, and
// always asked first, in the middle of the window.

// Whether every picked song can be taken off the server's disk: none of
// them found online or opened from this computer.
fun canDeleteFromDisk(app: AppState, songs: List<Song>): Boolean =
    app.health.canDelete && songs.isNotEmpty() &&
        songs.none { isOpenedFile(it.id) || isOutsideSong(it, app.library?.index, canFetch = app.fetches != null) }

// Asks before songs leave the server's disk, then deletes them.
fun askToDelete(app: AppState, songs: List<Song>) {
    if (songs.isEmpty()) return
    val names = songs.singleOrNull()?.title ?: countText(songs.size, "song", "songs")
    val keepDays = app.health.actions?.keepDays ?: 0
    app.popups.showCentred { close ->
        MenuTitle(deleteTitle(songs.size))
        PopupPadding {
            Txt(deleteBody(names, keepDays), DesktopType.body, OctoColors.TextPrimary, maxLines = 8)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End)) {
                GlazeCapsule(null, "Cancel", close)
                GlazeCapsule(OctoIcons.Delete, "Delete", {
                    close()
                    app.deleteFromDisk(songs)
                }, lit = true)
            }
        }
    }
}

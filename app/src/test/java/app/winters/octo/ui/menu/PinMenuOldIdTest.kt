package app.winters.octo.ui.menu

import app.winters.octo.catalog.IdsRig
import app.winters.octo.catalog.LikedAlbumEntity
import app.winters.octo.catalog.PinKind
import app.winters.octo.catalog.PinnedItemEntity
import app.winters.octo.catalog.SAVE_GROUP_FLOOR
import app.winters.octo.catalog.guarded
import app.winters.octo.catalog.put
import app.winters.octo.catalog.serverLibraryThenPhone
import app.winters.octo.favourites.PinKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A menu opened on an album's old id while a save is part way reads the
// album's pin: the save has published the album's rename, so Home's row
// shows the pin under the album's new id, and the menu looks for it there.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PinMenuOldIdTest {
    @Test
    fun aMenuOnAnAlbumsOldIdReadsItsPin() = guarded(60) {
        IdsRig(SAVE_GROUP_FLOOR).use { rig ->
            val phone = rig.serverLibraryThenPhone(40)
            val album = rig.db.query("SELECT id, searchKey FROM album ORDER BY id LIMIT 1", null).use { c -> c.moveToFirst(); c.getString(0) to c.getString(1) }
            rig.db.favouritesDao().savePins(listOf(PinnedItemEntity("album", album.first, album.second, 0)))
            rig.db.favouritesDao().likeAlbums(listOf(LikedAlbumEntity(album.first, album.second, 1)))
            rig.db.put(phone)
            var spot: PinSpot? = null
            var shownAt: List<PinKey> = emptyList()
            rig.hooks.between = between@{ _ ->
                if (spot != null) return@between
                val new = rig.db.query("SELECT `new` FROM save_rename WHERE kind = 'album' AND `old` = ?", arrayOf(album.first)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: return@between
                val shown = listOf(PinKey(PinKind.Album, new))
                shownAt = withTimeout(10_000) { rig.pins.shown.first { it == shown } }
                spot = pinSpotOf(rig.pins, PinKey(PinKind.Album, album.first)).first()
            }
            rig.merge.rebuild()
            assertEquals("Home's row while the save was part way", 1, shownAt.size)
            assertEquals(PinSpot.First, spot)
        }
    }
}

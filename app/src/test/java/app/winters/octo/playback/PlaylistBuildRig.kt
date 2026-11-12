package app.winters.octo.playback

import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.PlaylistItemEntity
import app.winters.octo.testing.addSongs
import app.winters.octo.testing.edits

// A queue rig with a playlist "p" of `n` songs and a second one, "q", of
// the same songs, for builds of a playlist while it is edited.
internal class PlaylistBuildRig(n: Int, page: Int = 50) {
    val rig = QueueRig(page = page)
    val tracks = rig.db.songs(n)
    val edits = rig.db.edits()

    init {
        rig.run {
            rig.db.userDao().insertPlaylist(PlaylistEntity("p", "P", 1, 1))
            rig.db.userDao().insertPlaylist(PlaylistEntity("q", "Q", 1, 1))
            rig.db.addSongs("p", tracks, now = 1)
            rig.db.addSongs("q", tracks, now = 1)
        }
    }

    // A playlist's rows as it shows them now, in its order.
    fun rows(id: String = "p"): List<PlaylistItemEntity> = rig.run { rig.db.userDao().playlistItems(id) }

    fun songs(id: String = "p"): List<String> = rows(id).map { it.trackId }

    fun close() = rig.close()
}

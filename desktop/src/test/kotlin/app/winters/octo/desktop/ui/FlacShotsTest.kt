package app.winters.octo.desktop.ui

import androidx.compose.ui.unit.IntOffset
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.nav.Page
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.swing.SwingUtilities

// "Find in FLAC" as it looks: the song menu's row, the album menu's row,
// the question an album asks, and the ring on a row being looked for.
// Only when asked: OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*FlacShotsTest*'.
// Saved under build/shots/polish/flac/.
class FlacShotsTest {
    // Where the menus open in the 1080p window, as the polish pictures do.
    private val menuAt = IntOffset(1920 * 45 / 100, 1080 * 25 / 100)

    @get:Rule val folder = TemporaryFolder()

    @Test
    fun drawFindInFlac() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        FakeServer().use { server ->
            PolishShotsTest.Rig(folder, PolishData.library(100), server).use { rig ->
                // An Octo server that lists library actions at version 2.
                server.answer(
                    "getOpenSubsonicExtensions",
                    """"openSubsonicExtensions":[{"name":"songLyrics","versions":[1]},{"name":"octoLibraryActions","versions":[1,2]}]""",
                    type = "octo",
                )
                server.answer("getLibraryActions", """"libraryActions":{"enabled":true,"allowed":true,"dryRun":false,"actions":["remove","upgrade"],"keepDays":30,"parallel":3}""", type = "octo")
                server.answer("getUpgrades", """"upgrades":[]""", type = "octo")
                server.answer("libraryAction", """"libraryAction":{"id":"dup-2","action":"upgrade","state":"queued","detail":null}""", type = "octo")
                rig.signIn()
                val app = rig.app
                val end = System.currentTimeMillis() + 10_000
                while (app.upgrades?.canUpgrade != true && System.currentTimeMillis() < end) Thread.sleep(50)
                check(app.upgrades?.canUpgrade == true) { "the server's action was never read" }
                val index = app.library!!.index!!
                // The single of Holocene is an MP3, so a FLAC could replace it.
                val song = index.songs.first { it.id == "dup-2" }
                val album = index.albums.first { it.id == song.albumId }
                rig.scene(PolishShotsTest.Size.Hd) { scene ->
                    rig.reset(scene)
                    SwingUtilities.invokeAndWait {
                        app.navigator.go(Page.Album(album.id))
                        app.popups.showAt(menuAt) { close -> SongMenu(app, listOf(song), close) }
                    }
                    rig.shot(scene, "flac/song-menu", 1_500)

                    rig.reset(scene)
                    SwingUtilities.invokeAndWait {
                        app.popups.showAt(menuAt) { close -> AlbumMenu(app, album, close) }
                    }
                    rig.shot(scene, "flac/album-menu", 1_500)

                    rig.clickText(scene, "Find FLAC for 1 song")
                    rig.shot(scene, "flac/album-confirm", 1_500)

                    // Asked for, and on its way down.
                    server.answer(
                        "getUpgrades",
                        """"upgrades":[{"id":"dup-2","title":"Holocene","artist":"Bon Iver","album":"${album.name}","state":"working","detail":null,"progress":0.4,"updatedAt":"2026-10-03T12:00:00Z"}]""",
                        type = "octo",
                    )
                    rig.reset(scene)
                    SwingUtilities.invokeAndWait { app.upgrades!!.request(listOf(song)) }
                    val asked = System.currentTimeMillis() + 10_000
                    while (app.upgrades!!.pending[song.id]?.state != "working" && System.currentTimeMillis() < asked) Thread.sleep(50)
                    // The notice says it is being looked for; the row wears the ring.
                    rig.shot(scene, "flac/row-ring", 1_500)
                }
            }
        }
    }
}

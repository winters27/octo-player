package app.winters.octo.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.songJson
import app.winters.octo.lyrics.OnlineLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.swing.SwingUtilities

// A picture of Brandon's "drake" search (2026-10-04) as a fixed Octo answers
// it: the library albums that hold HABIBTI and "$ome $exy $ongs 4 U" listed
// as his, What A Time To Be Alive under "Partly in your library" with "2 of
// 11 in your library", and only MAID OF HONOUR under "Not in your library".
// Only when asked: OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*SearchAlbumShotsTest*'.
class SearchAlbumShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private val libraryAlbums = listOf(
        Triple("al-takecare", "Take Care", "Drake"),
        Triple("3jmLzEVlwtrlq8XfVjei9k", "\$ome \$exy \$ongs 4 U", "Drake"),
        Triple("al-fomo", "HABIBTI (FOMO)", "Drake"),
        Triple("al-sss-pnd", "\$ome \$exy \$ongs 4 U", "PARTYNEXTDOOR"),
    )

    private fun albumJson(id: String, name: String, artist: String, extra: String = "") =
        """{"id":"$id","name":"$name","artist":"$artist","coverArt":"$id"$extra}"""

    private fun serve(server: FakeServer) {
        server.answer("ping", type = "octo")
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"octoAcquisitions","versions":[1]}]""", type = "octo")
        server.answer("getAlbumList2", """"albumList2":{"album":[${libraryAlbums.joinToString(",") { (id, name, artist) -> albumJson(id, name, artist) }}]}""")
        server.answer("getArtists", """"artists":{"index":[{"name":"D","artist":[{"id":"r1","name":"Drake","albumCount":3}]},{"name":"P","artist":[{"id":"r2","name":"PARTYNEXTDOOR","albumCount":1}]}]}""")
        val songs = listOf("Marvins Room" to "Take Care", "NOKIA" to "\$ome \$exy \$ongs 4 U", "Classic" to "HABIBTI (FOMO)")
            .mapIndexed { i, (title, album) -> songJson("lib$i", title, artist = "Drake", album = album, albumId = "a$i", duration = 200 + i * 17) }
            .joinToString(",")
        server.answerBy("search3") { request ->
            val query = request.url.queryParameter("query").orEmpty()
            if (query.isEmpty()) {
                server.ok(""""searchResult3":{"song":[$songs]}""", type = "octo")
            } else {
                val outside = listOf(
                    albumJson("e-wattba", "What A Time To Be Alive", "Drake", ""","songCount":11,"isExternal":true,"ownedCount":2"""),
                    albumJson("e-maid", "MAID OF HONOUR", "Drake", ""","songCount":14,"isExternal":true,"ownedCount":0"""),
                )
                val albums = (libraryAlbums.map { (id, name, artist) -> albumJson(id, name, artist, ""","isExternal":false""") } + outside).joinToString(",")
                server.ok(
                    """"searchResult3":{"artist":[{"id":"r1","name":"Drake","albumCount":3}],"album":[$albums],"song":[$songs]}""",
                    type = "octo",
                )
            }
        }
        server.answer("getPlaylists", """"playlists":{"playlist":[]}""")
        server.answer("getAcquisitions", """"acquisitions":{"acquisition":[]}""", type = "octo")
        server.fileBy("getCoverArt") { madeUpCover(it.url.queryParameter("id").orEmpty()) }
    }

    @Test
    fun drawSearchsAlbumsByWhatTheLibraryHolds() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val out = File("build/shots").apply { mkdirs() }
        FakeServer().use { server ->
            serve(server)
            val settings = SettingsStore(File(folder.root, "settings.json"))
            settings.update { it.copy(lyrics = it.lyrics.copy(online = false)) }
            val http = OkHttpClient()
            val accounts = Accounts(settings, SessionOnlySecrets(), http)
            lateinit var app: AppState
            runBlocking { accounts.signIn(SignInRequest(server.address, "winters", "pw")) } as SignInOutcome.Done
            SwingUtilities.invokeAndWait {
                app = AppState(settings, accounts, http, CoroutineScope(SupervisorJob() + Dispatchers.Main), DesktopOs.Windows, SilentPlayer(), OnlineLyrics(http, server.address.toHttpUrl()), restored = accounts.restore(), listeningRoot = File(folder.root, "listening"))
            }
            val scene = ImageComposeScene(1440, 1100, Density(1f)) {
                CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
            }
            fun shot(name: String, settleMs: Long = 2_500) {
                val begin = System.currentTimeMillis()
                var t = 0L
                while (System.currentTimeMillis() < begin + settleMs) {
                    SwingUtilities.invokeAndWait { scene.render(t) }
                    Thread.sleep(30)
                    t = (System.currentTimeMillis() - begin) * 1_000_000
                }
                File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            shot("search-albums-start", 3_000)
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Search) }
            SwingUtilities.invokeAndWait { app.search?.type("drake") }
            shot("search-albums-owned")
            scene.close()
        }
    }

    // A cover of soft coloured shapes, in colours of its own for each id.
    private fun madeUpCover(id: String): ByteArray {
        val palette = listOf(0xFF1B2A4A, 0xFFE0703A, 0xFF3AA6A0, 0xFFF2D06B, 0xFF6B3A7A, 0xFFB8C4C9, 0xFF2F5D3A, 0xFFC0463F).map { it.toInt() }
        val seed = id.hashCode() and 0x7fffffff
        fun colour(n: Int) = palette[(seed / (n + 1) + n) % palette.size]
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(300, 300)
        val canvas = surface.canvas
        canvas.clear(colour(0))
        val paint = org.jetbrains.skia.Paint()
        paint.color = colour(1)
        canvas.drawCircle(60f + seed % 90, 100f, 90f, paint)
        paint.color = colour(2)
        canvas.drawCircle(220f, 140f + seed % 80, 110f, paint)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}

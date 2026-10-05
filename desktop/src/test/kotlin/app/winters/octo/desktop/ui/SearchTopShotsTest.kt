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

// Pictures of search's ranked lists against a pretend Octo server: an
// artist's top songs under the artists, all of them, and the chart on an
// empty search. Only when asked: OCTO_SHOTS=1 ./gradlew :desktop:test
// --tests '*SearchTopShotsTest*'. The player is the silent one.
class SearchTopShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private val owned = listOf("One More Time" to "Discovery", "Digital Love" to "Discovery", "Get Lucky" to "Random Access Memories")

    private fun ownedJson(index: Int): String {
        val (title, album) = owned[index]
        val albumId = if (album == "Discovery") "a1" else "a2"
        return songJson("lib$index", title, artist = "Daft Punk", album = album, albumId = albumId, duration = 240 + index * 31).dropLast(1) +
            ""","artistId":"r1","coverArt":"al-$albumId","isExternal":false}"""
    }

    // `explicit` songs carry the server's "explicit" mark, so their rows show the small "E".
    private fun outsideJson(id: String, title: String, artist: String, album: String, seconds: Int, explicit: Boolean = false) =
        """{"id":"$id","title":"$title","artist":"$artist","album":"$album","duration":$seconds,"coverArt":"$id","isExternal":true,"suffix":"m4a"${if (explicit) ""","explicitStatus":"explicit"""" else ""}}"""

    private fun serve(server: FakeServer) {
        server.answer("ping", type = "octo")
        server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[{"name":"octoAcquisitions","versions":[1]},{"name":"octoTopSongs","versions":[1]}]""", type = "octo")
        server.answer("getAlbumList2", """"albumList2":{"album":[{"id":"a1","name":"Discovery","artist":"Daft Punk","artistId":"r1","coverArt":"al-a1"},{"id":"a2","name":"Random Access Memories","artist":"Daft Punk","artistId":"r1","coverArt":"al-a2"}]}""")
        server.answer("getArtists", """"artists":{"index":[{"name":"D","artist":[{"id":"r1","name":"Daft Punk","albumCount":2}]}]}""")
        server.answerBy("search3") { request ->
            val query = request.url.queryParameter("query").orEmpty()
            val songs = (owned.indices).joinToString(",") { ownedJson(it) }
            if (query.isEmpty()) {
                server.ok(""""searchResult3":{"song":[$songs]}""", type = "octo")
            } else {
                server.ok(
                    """"searchResult3":{"artist":[{"id":"r1","name":"Daft Punk","albumCount":2}],"album":[{"id":"a1","name":"Discovery","artist":"Daft Punk","coverArt":"al-a1"},{"id":"a2","name":"Random Access Memories","artist":"Daft Punk","coverArt":"al-a2"}],"song":[$songs,${outsideJson("o9", "Robot Rock", "Daft Punk", "Human After All", 287, explicit = true)}]}""",
                    type = "octo",
                )
            }
        }
        server.answer("getPlaylists", """"playlists":{"playlist":[]}""")
        val top = listOf(
            Triple("1", "lib1", 9_012_345L), Triple("2", "out:Harder, Better, Faster, Stronger|Discovery|224", 7_812_000L),
            Triple("3", "lib0", 6_240_000L), Triple("4", "out:Around the World|Homework|429", 5_480_000L),
            Triple("5", "out:Instant Crush|Random Access Memories|337", 4_950_000L), Triple("6", "lib2", 4_100_000L),
            Triple("7", "out:Aerodynamic|Discovery|212", 3_400_000L), Triple("8", "out:Something About Us|Discovery|231", 2_980_000L),
            Triple("9", "out:Veridis Quo|Discovery|345", 1_250_000L), Triple("10", "out:Lose Yourself to Dance|Random Access Memories|353", 960_000L),
        ).joinToString(",") { (rank, what, plays) ->
            val song = if (what.startsWith("lib")) ownedJson(what.removePrefix("lib").toInt()) else {
                val (title, album, seconds) = what.removePrefix("out:").split("|")
                outsideJson("t$rank", title, "Daft Punk", album, seconds.toInt(), explicit = rank == "4")
            }
            """{"rank":$rank,"plays":$plays,"listeners":${plays / 9},"inLibrary":${what.startsWith("lib")},"song":$song}"""
        }
        server.answer("getArtistTopSongs", """"topSongs":{"artist":"Daft Punk","source":"lastfm","entry":[$top]}""", type = "octo")
        val chart = listOf(
            "Dracula|Tame Impala|Deadbeat", "Golden|HUNTR/X|KPop Demon Hunters", "The Fate of Ophelia|Taylor Swift|The Life of a Showgirl",
            "Manchild|Sabrina Carpenter|Man's Best Friend", "Ordinary|Alex Warren|You'll Be Alright, Kid", "Die With A Smile|Lady Gaga|Mayhem",
            "Birds of a Feather|Billie Eilish|Hit Me Hard and Soft", "Espresso|Sabrina Carpenter|Short n' Sweet", "Opalite|Taylor Swift|The Life of a Showgirl",
            "Soda Pop|Saja Boys|KPop Demon Hunters", "Abracadabra|Lady Gaga|Mayhem", "APT.|ROSÉ|rosie",
        ).mapIndexed { index, line ->
            val (title, artist, album) = line.split("|")
            val song = if (index == 3) ownedJson(0).replace("One More Time", title).replace("Daft Punk", artist).replace("Discovery", album) else outsideJson("c$index", title, artist, album, 180 + index * 7, explicit = index == 0 || index == 6)
            """{"rank":${index + 1},"inLibrary":${index == 3},"song":$song}"""
        }.joinToString(",")
        server.answer("getTopChart", """"topSongs":{"source":"deezer","entry":[$chart]}""", type = "octo")
        server.answer("getAcquisitions", """"acquisitions":{"acquisition":[]}""", type = "octo")
        server.fileBy("getCoverArt") { madeUpCover(it.url.queryParameter("id").orEmpty()) }
    }

    @Test
    fun drawSearchsRankedLists() {
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
            val scene = ImageComposeScene(1440, 900, Density(1f)) {
                CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
            }
            fun shot(name: String, settleMs: Long = 2_000) {
                val begin = System.currentTimeMillis()
                var t = 0L
                while (System.currentTimeMillis() < begin + settleMs) {
                    SwingUtilities.invokeAndWait { scene.render(t) }
                    Thread.sleep(30)
                    t = (System.currentTimeMillis() - begin) * 1_000_000
                }
                File(out, "$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            shot("search-top-start", 3_000)
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Search) }
            shot("search-chart", 2_500)
            SwingUtilities.invokeAndWait { app.search?.type("daft punk") }
            shot("search-top-songs", 2_500)
            SwingUtilities.invokeAndWait { app.search?.tops?.artistOpen = true }
            shot("search-top-songs-all")
            // The songs found online, under what the library has: "Robot Rock" is explicit.
            SwingUtilities.invokeAndWait { app.search?.tops?.artistOpen = false }
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll, androidx.compose.ui.geometry.Offset(900f, 500f), scrollDelta = androidx.compose.ui.geometry.Offset(0f, 30f))
            shot("search-online-explicit")
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

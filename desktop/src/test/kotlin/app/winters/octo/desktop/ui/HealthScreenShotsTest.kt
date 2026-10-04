package app.winters.octo.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.pages.askToFill
import app.winters.octo.desktop.pages.askToFixCopies
import app.winters.octo.desktop.pages.askToFixDuplicates
import app.winters.octo.desktop.pages.askToJoinAlbums
import app.winters.octo.desktop.pages.lookUpSong
import app.winters.octo.desktop.pages.showTrash
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.HealthTag
import app.winters.octo.health.SubsonicHealth
import app.winters.octo.health.fillsFromAlbum
import app.winters.octo.lyrics.OnlineLyrics
import java.io.File
import javax.swing.SwingUtilities
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

// Pictures of the Library health page, with findings and with none, its
// fixes shown before they run, the trash, and deleting from disk. Only
// when asked:
// OCTO_SHOTS=1 ./gradlew :desktop:test --tests '*ScreenShotsTest*'.
// Saved as build/shots/health-*.png.
class HealthScreenShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private var n = 0

    // A song as a server lists it, with the tags the checks read.
    private fun song(
        title: String,
        artist: String,
        album: String,
        albumId: String,
        seconds: Int,
        suffix: String = "flac",
        bitRate: Int = 920,
        bitDepth: Int? = 16,
        rate: Int = 44_100,
        year: Int? = 2019,
        genre: String? = "Indie",
        track: Int? = null,
        albumArtist: String = artist,
    ): String {
        n++
        val fields = buildList {
            add(""""id":"s$n","title":"$title","artist":"$artist","album":"$album","albumId":"$albumId","duration":$seconds""")
            add(""""suffix":"$suffix","bitRate":$bitRate,"samplingRate":$rate,"size":${seconds * bitRate * 125L}""")
            bitDepth?.let { add(""""bitDepth":$it""") }
            year?.let { add(""""year":$it""") }
            genre?.let { add(""""genre":"$it"""") }
            add(""""track":${track ?: 0}""")
            add(""""displayAlbumArtist":"$albumArtist","coverArt":"mf-s$n","parent":"d-$albumId","path":"$artist/$album/$title.$suffix"""")
        }
        return "{" + fields.joinToString(",") + "}"
    }

    private fun sickLibrary(): String = listOf(
        // A song twice: a FLAC and an MP3 of one recording.
        song("Holocene", "Bon Iver", "Bon Iver", "a-bi", 337, track = 3),
        song("Holocene", "Bon Iver", "Holocene", "a-hol", 336, suffix = "mp3", bitRate = 320, bitDepth = null, track = 1),
        song("Towers", "Bon Iver", "Bon Iver", "a-bi", 188, track = 4),
        song("Perth", "Bon Iver", "Bon Iver", "a-bi", 262, track = 1),
        // Three copies, one of them a hi-res master.
        song("The Less I Know the Better", "Tame Impala", "Currents", "a-cur", 216, track = 7, year = 2015),
        song("The Less I Know the Better", "Tame Impala", "Currents", "a-cur2", 218, bitDepth = 24, rate = 96_000, bitRate = 2800, year = 2015, track = 7),
        song("The Less I Know the Better", "Tame Impala", "Currents", "a-cur3", 216, year = null, track = 7),
        song("Let It Happen", "Tame Impala", "Currents", "a-cur", 467, track = 1, year = 2015),
        // An album split by an album artist spelt two ways.
        song("Sticks and Stones", "SueCo", "It Was Fun While It Lasted", "a-if1", 181, track = 1, year = 2022),
        song("Paralyzed", "SueCo", "It Was Fun While It Lasted", "a-if1", 175, track = 2, year = 2022),
        song("Drunk Text", "Sueco", "It Was Fun While It Lasted", "a-if2", 163, track = 3, year = 2022, albumArtist = "Sueco"),
        // Missing tags.
        song("Teardrop", "Massive Attack", "Mezzanine", "a-mez", 330, genre = null, track = 3, year = 1998),
        song("Angel", "Massive Attack", "Mezzanine", "a-mez", 379, genre = null, track = null, year = null),
        song("Inertia Creeps", "Massive Attack", "Mezzanine", "a-mez", 356, genre = null, track = 4, year = 1998),
        song("Glory Box", "Portishead", "Dummy", "a-dum", 301, genre = null, year = null, track = 11),
        song("Roads", "Portishead", "Dummy", "a-dum", 305, genre = "Trip Hop", year = null, track = 5),
    ).joinToString(",")

    private fun tidyLibrary(): String = listOf(
        song("Airbag", "Radiohead", "OK Computer", "a-ok", 284, track = 1, year = 1997, genre = "Rock"),
        song("Paranoid Android", "Radiohead", "OK Computer", "a-ok", 387, track = 2, year = 1997, genre = "Rock"),
        song("Karma Police", "Radiohead", "OK Computer", "a-ok", 264, track = 6, year = 1997, genre = "Rock"),
    ).joinToString(",")

    @Test
    fun drawLibraryHealth() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val out = File("build/shots").apply { mkdirs() }
        draw(out, sickLibrary(), removable = true) { app, shot, click ->
            fun report() = app.health.report!!
            fun popup(open: () -> Unit) = SwingUtilities.invokeAndWait {
                app.popups.close()
                open()
            }
            shot("health-duplicates", 2_500)
            popup { askToFixDuplicates(app, report().duplicates) }
            shot("health-fix-all", 1_000)
            // The three copies of one song, keeping a plain FLAC instead of the master.
            popup { report().duplicates.first { it.copies.size == 3 }.let { askToFixCopies(app, it, it.copies.last()) } }
            shot("health-fix-copies", 1_000)
            popup { app.health.picked = HealthCheck.SplitAlbums }
            shot("health-split", 1_000)
            popup { askToJoinAlbums(app, report().splitAlbums) }
            shot("health-join", 1_000)
            popup { app.health.picked = HealthCheck.NoGenre }
            shot("health-no-genre", 1_000)
            popup { askToFill(app, HealthCheck.NoYear, fillsFromAlbum(report().songs(HealthCheck.NoYear), app.library!!.index!!.songs, HealthTag.Year, SubsonicHealth)) }
            shot("health-fill-year", 1_000)
            popup { lookUpSong(app, report().songs(HealthCheck.NoGenre).first { it.title == "Angel" }, HealthCheck.NoGenre) }
            shot("health-lookup", 2_000)
            popup { showTrash(app) }
            shot("health-trash", 1_500)
            popup { askToDelete(app, report().songs(HealthCheck.NoGenre).take(1)) }
            shot("delete-from-disk", 1_000)
            // The second copy of Holocene: its menu.
            popup { app.health.picked = HealthCheck.Duplicates }
            shot(null, 1_000)
            click(ROW_X, SECOND_ROW_Y, true)
            shot("health-menu", 800)
        }
        draw(out, tidyLibrary(), removable = false) { _, shot, _ -> shot("health-clean", 2_500) }
    }

    private fun draw(
        out: File,
        songs: String,
        removable: Boolean,
        steps: (AppState, shot: (String?, Long) -> Unit, click: (Float, Float, Boolean) -> Unit) -> Unit,
    ) {
        FakeServer().use { server ->
            val extensions = if (removable) """{"name":"octoLibraryActions","versions":[1,2,3]}""" else """{"name":"songLyrics","versions":[1]}"""
            server.answer("ping", type = "octo")
            server.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[$extensions]""", type = "octo")
            server.answer(
                "getLibraryActions",
                """"libraryActions":{"enabled":true,"allowed":true,"dryRun":false,"admin":true,"actions":["remove","retag","joinAlbum","lookup","undo","restore","cover"],"keepDays":30}""",
                type = "octo",
            )
            server.answer(
                "getLibraryTrash",
                """"libraryTrash":{"keepDays":30,"songs":[{"id":"t1","title":"Holocene","artist":"Bon Iver","album":"Holocene","goneAt":"${java.time.Instant.now().plus(java.time.Duration.ofDays(27))}"},""" +
                    """{"id":"t2","title":"Nightcall","artist":"Kavinsky","album":"OutRun","goneAt":"${java.time.Instant.now().plus(java.time.Duration.ofDays(3))}"}]}""",
                type = "octo",
            )
            server.answerBy("libraryAction") { request ->
                server.ok(
                    """"libraryAction":{"id":"${request.url.queryParameter("id")}","action":"lookup","state":"found",""" +
                        """"current":{"title":"Angel","artist":"Massive Attack","album":"Mezzanine","year":null,"genre":null,"track":null},""" +
                        """"suggested":{"title":"Angel","artist":"Massive Attack","album":"Mezzanine","albumArtist":"Massive Attack","year":"1998","genre":"Trip Hop","track":"1"},""" +
                        """"confidence":"Strong","source":"Fingerprint","release":"'Mezzanine' (Album) 1998"}""",
                    type = "octo",
                )
            }
            server.answer("getAlbumList2", """"albumList2":{"album":[]}""")
            server.answer("getArtists", """"artists":{"index":[]}""")
            server.answer("search3", """"searchResult3":{"song":[$songs]}""")
            server.answer("getStarred2", """"starred2":{}""")
            server.answer("getPlaylists", """"playlists":{"playlist":[]}""")
            val settings = SettingsStore(File(folder.newFolder(), "settings.json"))
            settings.update { it.copy(lyrics = it.lyrics.copy(online = false)) }
            val http = OkHttpClient()
            val accounts = Accounts(settings, SessionOnlySecrets(), http)
            lateinit var app: AppState
            SwingUtilities.invokeAndWait {
                app = AppState(settings, accounts, http, CoroutineScope(SupervisorJob() + Dispatchers.Main), DesktopOs.Windows, lyricsLibrary = OnlineLyrics(http, server.address.toHttpUrl()), restored = null)
            }
            val scene = ImageComposeScene(1440, 900, Density(1f)) {
                CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
            }
            fun shot(name: String?, settleMs: Long) {
                val begin = System.currentTimeMillis()
                var t = 0L
                while (System.currentTimeMillis() < begin + settleMs) {
                    SwingUtilities.invokeAndWait { scene.render(t).close() }
                    Thread.sleep(30)
                    t = (System.currentTimeMillis() - begin) * 1_000_000
                }
                val image = scene.render(t)
                if (name != null) File(out, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            fun click(x: Float, y: Float, right: Boolean) {
                val at = Offset(x, y)
                val button = if (right) PointerButton.Secondary else PointerButton.Primary
                listOf(
                    { scene.sendPointerEvent(PointerEventType.Move, at) },
                    { scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = !right, isSecondaryPressed = right), button = button) },
                    { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = button) },
                ).forEach { step ->
                    SwingUtilities.invokeAndWait {
                        step()
                        scene.render()
                    }
                }
            }
            val done = runBlocking { accounts.signIn(server.address, "winters", "pw") } as SignInOutcome.Done
            SwingUtilities.invokeAndWait {
                app.signedIn(done.connection)
                app.navigator.go(Page.LibraryHealth)
            }
            steps(app, ::shot, ::click)
            scene.close()
        }
    }

    private companion object {
        // Where the second copy's row is in the 1440 by 900 window; found by
        // looking at the pictures.
        const val ROW_X = 700f
        const val SECOND_ROW_Y = 761f
    }
}

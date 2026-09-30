package app.winters.octo.desktop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import app.winters.octo.design.LocalTyping
import app.winters.octo.design.TypingState
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.SidePanel
import app.winters.octo.desktop.library.coverLoader
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.player.SilentPlayer
import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.secrets.SecretStoreException
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.ServerSecurity
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.lyrics.OnlineLyrics
import app.winters.octo.sort.AlbumSort
import app.winters.octo.sort.SortOrder
import coil3.PlatformContext
import coil3.SingletonImageLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// The README's pictures, drawn off screen from a real library: the saved
// server of an Octo in use, its password read from the system's store.
// Only when asked, with a copy of that Octo's settings.json:
//   OCTO_SHOTS=1 OCTO_README_SETTINGS=<copy> ./gradlew :desktop:test --tests '*ReadmeShotsTest*'
// scripts/readme-shots.ps1 makes the copy and runs this.
//
// Read only, by construction: every request goes through a guard that lets
// through only the server's own reads (get..., search..., ping), so no play
// report, queue, star, rating or playlist change can reach the server, and
// no song is streamed. The player is the silent one, paused. The password
// store is only read: signing out (which deletes the password) cannot
// happen. Nothing private is printed; the log names blocked endpoints only.
//
// First run with OCTO_README_SURVEY=1 to draw all of Home and Albums and to
// list the albums (most colourful covers first) and playlists in
// build/shots/readme/survey.txt, then pick:
//   OCTO_README_ALBUM     album id for the album page
//   OCTO_README_PLAYING   songs for the full player: "album id[:track from 0]",
//                         split by commas; the first also sits in the player bar
//   OCTO_README_ALBUM_SORT the Albums page's order (AlbumSort name, MostPlayed...)
//   OCTO_README_PLAYLIST  playlist ids for the playlist page, split by commas
//   OCTO_README_PAGES     draw only the pages whose names start with these
//   OCTO_README_SCALE     screen scale (default 2), window 1600x1000
class ReadmeShotsTest {
    @get:Rule val folder = TemporaryFolder()

    private val out = File("build/shots/readme").apply { mkdirs() }
    private val blocked = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Test
    fun drawTheReadmeShots() {
        assumeTrue(System.getenv("OCTO_SHOTS") == "1")
        val given = System.getenv("OCTO_README_SETTINGS")?.let(::File)
        assumeTrue(given != null && given.isFile)
        val root = folder.newFolder()
        try {
            draw(given!!, root)
        } finally {
            // The copy of the settings goes with the covers; TemporaryFolder
            // gives up in silence on Windows while a file is still open.
            SingletonImageLoader.reset()
            for (attempt in 1..20) {
                if (root.deleteRecursively()) break
                Thread.sleep(250)
            }
            check(!File(root, "settings.json").exists()) { "The copy of the settings could not be deleted: $root" }
        }
    }

    private fun draw(given: File, root: File) {
        val settingsFile = File(root, "settings.json")
        given.copyTo(settingsFile)
        val settings = SettingsStore(settingsFile)
        // Nothing reaches the server or other apps by itself.
        settings.update {
            it.copy(
                listening = it.listening.copy(reportPlays = false, syncQueue = false),
                lyrics = it.lyrics.copy(online = false),
                discord = it.discord.copy(on = false),
                updates = it.updates.copy(checkAutomatically = false),
            )
        }
        val saved = settings.current.servers.firstOrNull { it.id == settings.current.activeServer }
        check(saved != null && !saved.signedOut) { "The settings have no signed-in server" }
        val host = saved.address.toHttpUrlOrNull()?.host
        check(host != null) { "The saved server address does not read as an address" }

        val security = ServerSecurity(settings)
        val http = security.install(OkHttpClient.Builder())
            .addInterceptor(ReadOnlyGuard(host) { blocked += it })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val accounts = Accounts(settings, ReadOnlySecrets(SecretStore.forSystem(DesktopOs.Windows)), http, security)
        val connection = accounts.restore()
        check(connection != null) { "No saved password for the server in the system's store" }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val player = SilentPlayer(scope = scope, volume = 0f)
        lateinit var app: AppState
        SwingUtilities.invokeAndWait {
            app = AppState(
                settings, accounts, http, scope, DesktopOs.Windows, player,
                // Online lyrics off; an address that answers nothing all the same.
                lyricsLibrary = OnlineLyrics(http, "http://127.0.0.1:9/".toHttpUrl()),
                restored = connection,
            )
            app.playlistArt.folder = File(root, "playlist-art")
        }
        val covers = coverLoader(PlatformContext.INSTANCE, http, File(root, "cache"))
        SingletonImageLoader.setUnsafe(covers)

        // Until the library and the playlists are read.
        val end = System.currentTimeMillis() + 300_000
        while ((app.library?.index == null || app.playlists.isEmpty()) && System.currentTimeMillis() < end) Thread.sleep(200)
        val index = checkNotNull(app.library?.index) { "The library did not load" }
        println("README shots: ${index.songs.size} songs, ${index.albums.size} albums, ${app.playlists.size} playlists")

        try {
            if (System.getenv("OCTO_README_SURVEY") != null) survey(app, http) else shots(app, player)
        } finally {
            println("README shots: blocked ${blocked.size} requests: ${blocked.groupingBy { it }.eachCount()}")
            player.close()
            scope.cancel()
            covers.shutdown()
            covers.diskCache?.shutdown()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    // Home and Albums drawn tall, then the albums by how colourful their
    // covers are, and the playlists.
    private fun survey(app: AppState, http: OkHttpClient) {
        val client = app.connection!!.client
        val index = app.library!!.index!!
        // Every shelf of Home and a long run of Albums, to judge by eye.
        scene(app, Density(1f), 1600, 3200) { scene ->
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Home) }
            shot(scene, "survey-home", 12_000)
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Albums) }
            shot(scene, "survey-albums", 10_000)
        }
        // OCTO_README_SURVEY=pages draws only those.
        if (System.getenv("OCTO_README_SURVEY") == "pages") return
        val pool = Executors.newFixedThreadPool(8)
        val scored = index.albums.filter { it.coverArt != null }.map { album ->
            pool.submit<Pair<app.winters.octo.subsonic.Album, DoubleArray?>> {
                val bytes = runCatching {
                    http.newCall(Request.Builder().url(client.coverArtUrl(album.coverArt!!, 64)).build()).execute().use { it.body.bytes() }
                }.getOrNull()
                album to bytes?.let(::colourOf)
            }
        }.map { it.get() }
        pool.shutdown()
        val lines = buildList {
            add("# albums: id | colourful | light | explicit | songs | year | artist | name")
            scored.sortedByDescending { it.second?.get(0) ?: -1.0 }.forEach { (a, c) ->
                add("${a.id} | ${c?.get(0)?.let { "%.0f".format(it) } ?: "-"} | ${c?.get(1)?.let { "%.0f".format(it) } ?: "-"} | ${a.explicitStatus.orEmpty()} | ${a.songCount} | ${a.year ?: ""} | ${a.artist} | ${a.name}")
            }
            add("# playlists: id | songs | yours | name")
            app.playlists.forEach { add("${it.id} | ${it.songCount} | ${it.owner == null || it.owner == client.username} | ${it.name}") }
        }
        File(out, "survey.txt").writeText(lines.joinToString("\n"))
        // Home and Albums at a glance, to judge by eye.
        scene(app, Density(1f), 1600, 1000) { scene ->
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Home) }
            shot(scene, "survey-home", 10_000)
            SwingUtilities.invokeAndWait { app.navigator.go(Page.Albums) }
            shot(scene, "survey-albums", 8_000)
        }
    }

    private fun shots(app: AppState, player: SilentPlayer) {
        val index = app.library!!.index!!
        val scale = System.getenv("OCTO_README_SCALE")?.toFloatOrNull() ?: 2f
        val album = System.getenv("OCTO_README_ALBUM")
        // Songs for the full player, each "album id" or "album id:track from 0";
        // the first is in the player bar on the other pages.
        val playing = (System.getenv("OCTO_README_PLAYING") ?: album).orEmpty().split(",").map(String::trim).filter(String::isNotEmpty)
        val playlists = System.getenv("OCTO_README_PLAYLIST")?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
            ?: listOfNotNull(app.playlists.firstOrNull { it.songCount > 8 }?.id)
        val only = System.getenv("OCTO_README_PAGES")?.split(",")?.map(String::trim)?.toSet()
        val albumSort = System.getenv("OCTO_README_ALBUM_SORT")?.let { name -> AlbumSort.entries.firstOrNull { it.name == name } }

        // Plays a song, then pauses it a little way in: no sound, no clock.
        fun cue(spec: String) {
            val id = spec.substringBefore(":")
            val track = spec.substringAfter(":", "0").toIntOrNull() ?: 0
            val songs = index.songs.filter { it.albumId == id }.sortedWith(compareBy({ it.discNumber }, { it.track }))
            if (songs.isEmpty()) return
            val at = track.coerceIn(0, songs.lastIndex)
            SwingUtilities.invokeAndWait {
                app.play(songs, at)
                player.pause()
                player.seekTo(songs[at].duration.coerceAtLeast(60) * 1000L * 38 / 100)
            }
        }
        playing.firstOrNull()?.let(::cue)
        albumSort?.let { sort -> SwingUtilities.invokeAndWait { app.sortAlbums(SortOrder(sort, sort.startsDescending)) } }
        val w = (1600 * scale).toInt()
        val h = (1000 * scale).toInt()
        scene(app, Density(scale), w, h) { scene ->
            fun page(name: String, settle: Long = 9_000, setUp: () -> Unit) {
                if (only != null && only.none { name.startsWith(it) }) return
                SwingUtilities.invokeAndWait {
                    app.fullPlayer = false
                    app.showSidePanel(null)
                    setUp()
                    scene.sendPointerEvent(PointerEventType.Move, Offset(-10f, -10f))
                }
                // A first pass loads the covers; the second is the picture.
                shot(scene, null, settle)
                shot(scene, "desktop-$name", 3_000)
            }
            page("home", 12_000) { app.navigator.go(Page.Home) }
            album?.let { page("album") { app.navigator.go(Page.Album(it)) } }
            playlists.forEachIndexed { n, id -> page(if (n == 0) "playlists" else "playlists-$n") { app.navigator.go(Page.Playlist(id)) } }
            page("albums") { app.navigator.go(Page.Albums) }
            playing.forEachIndexed { n, spec ->
                if (only != null && only.none { it.startsWith("player") }) return@forEachIndexed
                cue(spec)
                for (style in listOf(AmbienceStyle.Immersive, AmbienceStyle.Glow)) {
                    SwingUtilities.invokeAndWait { app.settings.update { it.copy(appearance = it.appearance.copy(ambience = style)) } }
                    val tag = "$n-${style.name.lowercase()}"
                    page("player-$tag-lyrics") {
                        app.fullPlayer = true
                        if (app.playerPanel != SidePanel.Lyrics) app.togglePlayerPanel(SidePanel.Lyrics)
                    }
                    page("player-$tag-queue") {
                        app.fullPlayer = true
                        if (app.playerPanel != SidePanel.Queue) app.togglePlayerPanel(SidePanel.Queue)
                    }
                    page("player-$tag-plain") {
                        app.fullPlayer = true
                        app.playerPanel?.let(app::togglePlayerPanel)
                    }
                }
            }
        }
    }

    private fun scene(app: AppState, density: Density, width: Int, height: Int, draw: (ImageComposeScene) -> Unit) {
        val scene = ImageComposeScene(width, height, density) {
            CompositionLocalProvider(LocalTyping provides TypingState()) { Shell(app, null) {} }
        }
        try {
            draw(scene)
        } finally {
            scene.close()
        }
    }

    // Draws for a while, the scene's clock following real time, and saves
    // the picture; with no name, only draws.
    private fun shot(scene: ImageComposeScene, name: String?, settleMs: Long) {
        val begin = System.currentTimeMillis()
        var t = 0L
        while (System.currentTimeMillis() < begin + settleMs) {
            SwingUtilities.invokeAndWait { scene.render(t).close() }
            Thread.sleep(40)
            t = (System.currentTimeMillis() - begin) * 1_000_000
        }
        if (name == null) return
        var bytes: ByteArray? = null
        SwingUtilities.invokeAndWait { scene.render(t).use { bytes = it.encodeToData(EncodedImageFormat.PNG)!!.bytes } }
        File(out, "$name.png").writeBytes(bytes!!)
    }
}

// Lets through only the server's own reads; anything else is answered 403
// here and noted by endpoint name (never the address or its query, which
// carries the sign-in token). Playlists come back without their owner, so
// no user name shows.
private class ReadOnlyGuard(private val host: String, private val note: (String) -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val name = url.pathSegments.lastOrNull().orEmpty().removeSuffix(".view")
        val read = name.startsWith("get") || name.startsWith("search") || name == "ping" || name == "tokenInfo"
        if (url.host == host && read) {
            val response = chain.proceed(request)
            if (name != "getPlaylist" && name != "getPlaylists") return response
            // A playlist page says whose list it is, by user name; with no
            // owner it says nothing, and the list still counts as yours.
            val body = response.body
            val type = body.contentType()
            val text = body.string().replace(OWNER, "\"owner\":null")
            return response.newBuilder().body(text.toResponseBody(type)).build()
        }
        note(if (url.host == host) name else "another host")
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Blocked for the README shots")
            .body(ByteArray(0).toResponseBody())
            .build()
    }
}

private val OWNER = Regex(""""owner"\s*:\s*"(?:[^"\\]|\\.)*"""")

// The system's store, read only: nothing is written or deleted.
private class ReadOnlySecrets(private val real: SecretStore) : SecretStore {
    override val label: String get() = real.label

    override fun read(account: String): String? = real.read(account)

    override fun write(account: String, secret: String) = throw SecretStoreException("Read only for the README shots")

    override fun delete(account: String) {}
}

// How colourful a cover is (Hasler and Suesstrunk) and how light, 0 to 255.
private fun colourOf(bytes: ByteArray): DoubleArray? {
    val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
    var n = 0
    var rgSum = 0.0; var ybSum = 0.0; var rgSq = 0.0; var ybSq = 0.0; var light = 0.0
    for (y in 0 until image.height) for (x in 0 until image.width) {
        val p = image.getRGB(x, y)
        val r = (p shr 16 and 255).toDouble(); val g = (p shr 8 and 255).toDouble(); val b = (p and 255).toDouble()
        val rg = r - g
        val yb = (r + g) / 2 - b
        rgSum += rg; ybSum += yb; rgSq += rg * rg; ybSq += yb * yb
        light += 0.2126 * r + 0.7152 * g + 0.0722 * b
        n++
    }
    if (n == 0) return null
    val rgMean = rgSum / n; val ybMean = ybSum / n
    val sd = sqrt((rgSq / n - rgMean * rgMean) + (ybSq / n - ybMean * ybMean))
    val mean = sqrt(rgMean * rgMean + ybMean * ybMean)
    return doubleArrayOf(sd + 0.3 * mean, light / n)
}

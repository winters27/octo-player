package app.winters.octo.desktop.perf

import app.winters.octo.desktop.library.LibraryIndex
import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.ServerSecurity
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.desktop.system.useAppNatives
import app.winters.octo.subsonic.readLibrary
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

// How long the saved server takes to sign in, to answer one page of each
// kind, to read its whole library and to index it (scripts/startup-probe.py
// --steps --probe LibraryProbeKt). `octo.probe.settings` names a signed-in
// profile's settings (copied; the password comes from the system's store,
// read only). Reads only; changes nothing on the server.
fun main() {
    useAppNatives()
    val folder = Files.createTempDirectory("octo-library").toFile()
    try {
        val file = File(folder, SettingsStore.FILE_NAME)
        File(System.getProperty("octo.probe.settings")).copyTo(file)
        val settings = SettingsStore(file)
        val security = ServerSecurity(settings)
        val http = security.install(OkHttpClient.Builder()).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val accounts = Accounts(settings, SecretStore.forSystem(), http, security)
        var at = System.nanoTime()
        fun lap(): Long = ((System.nanoTime() - at) / 1_000_000).also { at = System.nanoTime() }
        val connection = accounts.restore() ?: error("not signed in")
        println("step restore ${lap()} ms")
        runBlocking {
            val songs = connection.client.songPage(500, 0)
            println("step songPage ${lap()} ms")
            println("library song page holds ${songs.size}")
            connection.client.albumList(app.winters.octo.subsonic.AlbumListType.ALPHABETICAL, 500, 0)
            println("step albumPage ${lap()} ms")
            connection.client.artists()
            println("step artists ${lap()} ms")
            repeat(2) { round ->
                val library = connection.client.readLibrary()
                val read = lap()
                val index = LibraryIndex.of(library)
                val indexed = lap()
                println("step read$round $read ms")
                println("step index$round $indexed ms")
                println("library ${library.songs.size} songs, ${library.albums.size} albums, ${library.artists.size} artists, genres ${index.genres.size}")
            }
        }
    } catch (e: Throwable) {
        e.printStackTrace()
        exitProcess(1)
    } finally {
        folder.deleteRecursively()
    }
    exitProcess(0)
}

package app.winters.octo.playlists

import androidx.room.withTransaction
import app.winters.octo.catalog.FreeSpace
import app.winters.octo.catalog.PlaylistEntity
import app.winters.octo.catalog.WorkOwners
import app.winters.octo.data.Drains
import app.winters.octo.discovery.ListServer
import app.winters.octo.discovery.SERVER_SOURCE
import app.winters.octo.discovery.ServerListKind
import app.winters.octo.discovery.ServerReads
import app.winters.octo.discovery.ServerSide
import app.winters.octo.discovery.libraryTrack
import app.winters.octo.discovery.putLibrary
import app.winters.octo.listening.timed
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

// Labeled guard (today's sync reads the whole answer before writing, so it
// passes at base too): a sync step's read never leaves the server's answer
// unread while it waits to write. Behind a proxy that cuts a response left
// idle for 2 seconds, a 3,000-entry pull whose first page arrives while a
// swap holds the database for 5 seconds still completes: the answer is
// drained to its file at network speed, then written once the database is
// free. Its rows are the whole playlist, and no drain file is left.
@RunWith(RobolectricTestRunner::class)
class ProxyIdleDrainTest {
    // A client socket with a small receive buffer, so a reader that stops
    // reading holds the server up within a few pieces.
    private val small = object : SocketFactory() {
        override fun createSocket(): Socket = Socket().apply { receiveBufferSize = 16 * 1024 }
        override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket(host, port)
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = createSocket(host, port)
    }

    @Test
    fun aPullSurvivesAHeldDatabaseBehindAnIdleLimit() = timed(60) {
        SyncRig(PlaylistWorld(31, 200)).use { rig ->
            rig.library()
            val entries = rig.world.entries(3_000)
            rig.server.add("srv", "Mix", entries)
            rig.phonePlaylist(PlaylistEntity("p", "Mix", 1, 5, serverId = "srv", sourceId = SOURCE, syncedAt = 5, syncedName = "Mix", serverStamp = "old"), emptyList(), null)
            val started = CompletableDeferred<Unit>()
            val cut = AtomicBoolean(false)
            rig.server.playlistBody = { held, prefix ->
                val whole = Buffer().writeUtf8(prefix).writeUtf8(
                    """{"id":"${held.id}","name":"${held.name}","owner":"${held.owner}","songCount":${held.entries.size},"entry":[""" +
                        held.entries.joinToString(",") { rig.server.songJson(it) } + "]}}}",
                )
                PieceBody(whole.size) { sink ->
                    val body = whole.copy()
                    while (!body.exhausted()) {
                        val piece = Buffer().apply { write(body, minOf(16_384L, body.size)) }
                        val at = System.nanoTime()
                        sink.write(piece, piece.size)
                        sink.flush()
                        started.complete(Unit)
                        if (System.nanoTime() - at > 2_000_000_000L) {
                            cut.set(true)
                            throw IOException("the proxy cut an idle response")
                        }
                    }
                }
            }
            val client = rig.server.client(OkHttpClient.Builder().retryOnConnectionFailure(false).socketFactory(small).build())
            val server = SyncServer(client, SOURCE, "account", formPost = false, familyRules = false)
            coroutineScope {
                val pull = async { rig.steps.run(server, PlaylistStep.Pull("p", "srv", "new", keepName = false)) }
                started.await()
                launch(Dispatchers.IO) { rig.db.withTransaction { delay(5_000) } }
                pull.await()
            }
            assertFalse("the answer was never left idle", cut.get())
            assertEquals(3_000L, rig.db.count("SELECT COUNT(*) FROM playlist_item i JOIN playlist p ON p.id = i.playlistId AND i.itemSet = p.itemSet"))
            assertTrue(rig.drains.folder.listFiles().isNullOrEmpty())
        }
    }

    // A server list's reads (a page's, a car's and a play's copy) behind the
    // same proxy, while a swap holds the database for 5 seconds, each drain
    // their answer at network speed and complete, and leave no file.
    @Test
    fun serverListReadsSurviveAHeldDatabaseBehindAnIdleLimit() = timed(60) {
        val songs = (0 until 3_000).associate { "srv$it" to Song("srv$it", title = "Title $it", artist = "Artist") }
        val server = ListServer(songs)
        val db = openTestCatalog()
        val drains = Files.createTempDirectory("drain").toFile()
        try {
            db.putLibrary((0 until 3_000 step 3).map { libraryTrack("t$it", "Title $it") }) { "srv${it.id.removePrefix("t")}" }
            server.lists["L"] = (0 until 3_000).map { "srv$it" }
            val started = CompletableDeferred<Unit>()
            val cut = AtomicBoolean(false)
            server.listBody = { answer ->
                val whole = Buffer().writeUtf8(answer)
                PieceBody(whole.size) { sink ->
                    val body = whole.copy()
                    while (!body.exhausted()) {
                        val piece = Buffer().apply { write(body, minOf(16_384L, body.size)) }
                        val at = System.nanoTime()
                        sink.write(piece, piece.size)
                        sink.flush()
                        started.complete(Unit)
                        if (System.nanoTime() - at > 2_000_000_000L) {
                            cut.set(true)
                            throw IOException("the proxy cut an idle response")
                        }
                    }
                }
            }
            val client = SubsonicClient(server.server.url("/"), Credentials("winters", "secret"), OkHttpClient.Builder().retryOnConnectionFailure(false).socketFactory(small).build())
            val merge = db.testMerge(SERVER_SOURCE)
            val reads = ServerReads(db, Drains(drains), FreeSpace { Long.MAX_VALUE }, WorkOwners(), merge, server = { ServerSide(client, SERVER_SOURCE, false) }, page = 50)
            coroutineScope {
                val read = listOf(WorkOwners.marked("vm:1"), WorkOwners.marked("car:2"), WorkOwners.marked("queue:3")).map { owner ->
                    async(Dispatchers.IO) { reads.read(ServerListKind.List, "L", owner) }
                }
                started.await()
                launch(Dispatchers.IO) { db.withTransaction { delay(5_000) } }
                read.awaitAll().forEach { assertTrue(it!!.complete) }
            }
            assertFalse("no answer was left idle", cut.get())
            listOf(WorkOwners.marked("vm:1"), WorkOwners.marked("car:2"), WorkOwners.marked("queue:3")).forEach { owner ->
                assertEquals(owner, 3_000L, db.query("SELECT COUNT(*) FROM server_entry WHERE owner = ?", arrayOf(owner)).use { it.moveToFirst(); it.getLong(0) })
            }
            assertTrue(drains.listFiles().isNullOrEmpty())
        } finally {
            server.close()
            db.close()
            drains.deleteRecursively()
        }
    }
}

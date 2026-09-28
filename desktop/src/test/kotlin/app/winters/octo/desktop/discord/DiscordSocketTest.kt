package app.winters.octo.desktop.discord

import app.winters.octo.desktop.settings.DesktopOs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// The socket-file way to Discord (macOS and Linux), against a pretend
// Discord listening on a socket file in a temporary folder: never the real
// one, which is never looked for here.
class DiscordSocketTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun octoFindsASocketFileAndShowsTheSong() {
        val socket = File(folder.root, "discord-ipc-3")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket.toPath())) }
        val fake = FakeDiscord()
        val done = CountDownLatch(1)
        // The pretend Discord: whatever arrives goes through FakeDiscord,
        // and its answers go back down the socket.
        thread(isDaemon = true) {
            server.accept().use { client ->
                val bytes = ByteBuffer.allocate(8192)
                val out = ByteArray(8192)
                while (fake.statuses.isEmpty()) {
                    bytes.clear()
                    val read = client.read(bytes)
                    if (read < 0) break
                    fake.write(bytes.array().copyOf(read))
                    val count = fake.readNow(out)
                    if (count > 0) client.write(ByteBuffer.wrap(out, 0, count))
                }
                done.countDown()
            }
        }
        val opener = discordPipes(DesktopOs.Linux) { name -> if (name == "XDG_RUNTIME_DIR") folder.root.path else null }
        val sync = DiscordSync("1234567890123456789", opener, pid = 7)
        sync.step(true, DiscordActivity("Teardrop", "Massive Attack", "Mezzanine", 1_000, 331_000), System.currentTimeMillis())
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(sync.connected)
        assertEquals("Teardrop", fake.statuses.single()!!["details"].toString().trim('"'))
        sync.letGo()
        server.close()
    }

    @Test
    fun noSocketFileMeansDiscordIsNotRunning() {
        val opener = discordPipes(DesktopOs.Linux) { name -> if (name == "XDG_RUNTIME_DIR") folder.root.path else null }
        // Only the temporary folder and /tmp are looked in; on this test
        // machine neither holds a Discord socket.
        if (File("/tmp/discord-ipc-0").exists()) return
        assertNull(opener.open())
    }

    @Test
    fun theUsualFoldersAreLookedIn() {
        val folders = socketFolders(mapOf("XDG_RUNTIME_DIR" to "/run/user/1000", "TMPDIR" to "/var/tmp")::get).map { it.path.replace('\\', '/') }
        assertEquals("/run/user/1000", folders.first())
        assertTrue("/run/user/1000/app/com.discordapp.Discord" in folders)
        assertTrue("/run/user/1000/snap.discord" in folders)
        assertTrue("/var/tmp" in folders)
        assertTrue("/tmp" in folders)
    }
}

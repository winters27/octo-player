package app.winters.octo.desktop.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SingleInstanceTest {
    @get:Rule val folder = TemporaryFolder()

    private fun first(dir: File): SingleInstance {
        val claim = SingleInstance.claim(dir, emptyList())
        assertTrue("the first start is the one Octo, not $claim", claim is SingleInstance.Claim.First)
        return (claim as SingleInstance.Claim.First).instance
    }

    @Test
    fun aSecondLaunchHandsItsFilesToTheFirst() {
        val dir = folder.newFolder("octo")
        first(dir).use { running ->
            val heard = LinkedBlockingQueue<List<String>>()
            running.onLaunch { heard += it }
            var allowed = false
            val song = File(folder.root, "Ünïcode song.flac").path
            val second = SingleInstance.claim(dir, listOf(song, "octo://album/42"), beforeHandover = { allowed = true })
            assertEquals(SingleInstance.Claim.HandedOver, second)
            assertTrue("the second launch let the first come forward", allowed)
            assertEquals(listOf(song, "octo://album/42"), heard.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun launchesBeforeAListenerAreKept() {
        val dir = folder.newFolder("octo")
        first(dir).use { running ->
            assertEquals(SingleInstance.Claim.HandedOver, SingleInstance.claim(dir, listOf("a.mp3")))
            assertEquals(SingleInstance.Claim.HandedOver, SingleInstance.claim(dir, emptyList()))
            val heard = LinkedBlockingQueue<List<String>>()
            running.onLaunch { heard += it }
            assertEquals("named in full from where it started", listOf(File("a.mp3").absolutePath), heard.poll(5, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), heard.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun aConnectionThatSaysNothingIsDroppedWithinASecond() {
        val dir = folder.newFolder("octo")
        first(dir).use {
            val (port, _) = SingleInstance.readAddress(File(dir, SingleInstance.ADDRESS_FILE))!!
            Socket(InetAddress.getLoopbackAddress(), port).use { idle ->
                idle.soTimeout = 5_000
                val output = idle.getOutputStream()
                val started = System.currentTimeMillis()
                // A proper start, then a byte now and then, never buys more time.
                val bytes = byteArrayOf(0x4F, 0x43, 0x54, 0x4F, 0, 0, 0, 1) + ByteArray(12)
                val drip = Thread {
                    runCatching {
                        bytes.forEach { byte ->
                            output.write(byte.toInt())
                            output.flush()
                            Thread.sleep(200)
                        }
                    }
                }.apply { isDaemon = true; start() }
                assertEquals("closed without an answer", -1, runCatching { idle.getInputStream().read() }.getOrDefault(-1))
                assertTrue("dropped after ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 2_500)
                drip.join(5_000)
            }
        }
    }

    @Test
    fun strangersHoldingConnectionsOpenNeverKeepALaunchOut() {
        val dir = folder.newFolder("octo")
        first(dir).use { running ->
            val heard = LinkedBlockingQueue<List<String>>()
            running.onLaunch { heard += it }
            val (port, _) = SingleInstance.readAddress(File(dir, SingleInstance.ADDRESS_FILE))!!
            val idle = List(12) { Socket(InetAddress.getLoopbackAddress(), port) }
            try {
                assertEquals(SingleInstance.Claim.HandedOver, SingleInstance.claim(dir, listOf("octo://home")))
                assertEquals(listOf("octo://home"), heard.poll(5, TimeUnit.SECONDS))
            } finally {
                idle.forEach { runCatching { it.close() } }
            }
        }
    }

    @Test
    fun onceClosedALaunchStartsOnItsOwn() {
        val dir = folder.newFolder("octo")
        val running = first(dir)
        val heard = LinkedBlockingQueue<List<String>>()
        running.onLaunch { heard += it }
        running.close()
        val later = SingleInstance.claim(dir, listOf("octo://home"), waitMs = 300)
        assertTrue("the launch runs on its own, not $later", later is SingleInstance.Claim.First)
        (later as SingleInstance.Claim.First).instance.close()
        assertTrue(heard.isEmpty())
    }

    @Test
    fun fileArgumentsAreNamedInFullBeforeTheyGo() {
        val here = folder.newFolder("here")
        val sent = absoluteLaunchArgs(listOf("a.mp3", "sub/b.flac", "-psn_0_1", "octo://album/1", "file:///x.mp3", "https://example.com/c.mp3", ""), here)
        assertEquals(
            listOf(File(here, "a.mp3").absolutePath, File(here, "sub/b.flac").absolutePath, "-psn_0_1", "octo://album/1", "file:///x.mp3", "https://example.com/c.mp3", ""),
            sent,
        )
        val whole = File(folder.root, "whole.mp3").absolutePath
        assertEquals("a full path stays as it is", listOf(whole), absoluteLaunchArgs(listOf(whole), here))
    }

    @Test
    fun afterTheFirstEndsTheNextStartIsFirstAgain() {
        val dir = folder.newFolder("octo")
        first(dir).close()
        assertFalse("the address is cleaned up", File(dir, SingleInstance.ADDRESS_FILE).exists())
        first(dir).close()
    }

    @Test
    fun anAddressLeftByACrashIsReplaced() {
        val dir = folder.newFolder("octo")
        File(dir, SingleInstance.ADDRESS_FILE).writeText("1 " + "00".repeat(32))
        first(dir).use { running ->
            val heard = LinkedBlockingQueue<List<String>>()
            running.onLaunch { heard += it }
            assertEquals(SingleInstance.Claim.HandedOver, SingleInstance.claim(dir, listOf("octo://home")))
            assertEquals(listOf("octo://home"), heard.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun aLockHeldWithNoOneListeningLetsThisLaunchRunAlone() {
        val dir = folder.newFolder("octo")
        FileChannel.open(File(dir, SingleInstance.LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val claim = SingleInstance.claim(dir, listOf("a.mp3"), waitMs = 300)
                assertTrue(claim is SingleInstance.Claim.Alone)
            }
        }
    }

    @Test
    fun theAddressHoldsAPortAndALongSecret() {
        val dir = folder.newFolder("octo")
        first(dir).use {
            val address = SingleInstance.readAddress(File(dir, SingleInstance.ADDRESS_FILE))
            assertNotNull(address)
            val (port, secret) = address!!
            assertTrue(port in 1..65_535)
            assertEquals(32, secret.size)
        }
        assertNull(SingleInstance.readAddress(File(dir, "missing")))
        File(dir, "bad").writeText("port secret")
        assertNull(SingleInstance.readAddress(File(dir, "bad")))
    }

    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun messagesSurviveTheTrip() {
        val args = listOf("", "plain", "with\nnewline", "日本語.flac", "x".repeat(5000))
        val read = SingleInstance.readMessage(DataInputStream(ByteArrayInputStream(SingleInstance.encodeMessage(args, secret))), secret)
        assertEquals(args, read)
    }

    @Test
    fun theWrongSecretIsRefused() {
        val wrong = ByteArray(32) { 7 }
        assertNull(SingleInstance.readMessage(DataInputStream(ByteArrayInputStream(SingleInstance.encodeMessage(listOf("a.mp3"), wrong))), secret))
    }

    @Test
    fun strayConnectionsAreIgnored() {
        assertNull(SingleInstance.readMessage(DataInputStream(ByteArrayInputStream("GET / HTTP/1.1\r\n\r\n".toByteArray())), secret))
        assertNull(SingleInstance.readMessage(DataInputStream(ByteArrayInputStream(ByteArray(0))), secret))
    }
}

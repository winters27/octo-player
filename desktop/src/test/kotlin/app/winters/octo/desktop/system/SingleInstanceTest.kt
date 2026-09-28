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
            val second = SingleInstance.claim(dir, listOf("C:\\Music\\Ünïcode song.flac", "octo://album/42"), beforeHandover = { allowed = true })
            assertEquals(SingleInstance.Claim.HandedOver, second)
            assertTrue("the second launch let the first come forward", allowed)
            assertEquals(listOf("C:\\Music\\Ünïcode song.flac", "octo://album/42"), heard.poll(5, TimeUnit.SECONDS))
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
            assertEquals(listOf("a.mp3"), heard.poll(5, TimeUnit.SECONDS))
            assertEquals(emptyList<String>(), heard.poll(5, TimeUnit.SECONDS))
        }
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
            assertEquals(SingleInstance.Claim.HandedOver, SingleInstance.claim(dir, listOf("x")))
            assertEquals(listOf("x"), heard.poll(5, TimeUnit.SECONDS))
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

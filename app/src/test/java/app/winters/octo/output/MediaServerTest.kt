package app.winters.octo.output

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URI

class MediaServerTest {
    private val song = ByteArray(1000) { (it % 251).toByte() }
    private lateinit var server: MediaServer

    // A reply as the device reads it.
    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun ask(url: String, method: String = "GET", extra: List<String> = emptyList()): Reply {
        val uri = URI(url)
        Socket(uri.host, uri.port).use { socket ->
            val request = buildString {
                append("$method ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}\r\n")
                extra.forEach { append(it).append("\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray())
            return read(socket.getInputStream())
        }
    }

    private fun read(input: InputStream): Reply {
        val all = ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
        val split = (0..all.size - 4).first { all[it] == '\r'.code.toByte() && all[it + 1] == '\n'.code.toByte() && all[it + 2] == '\r'.code.toByte() && all[it + 3] == '\n'.code.toByte() }
        val head = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
        val status = head.first().split(' ')[1].toInt()
        val headers = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return Reply(status, headers, all.copyOfRange(split + 4, all.size))
    }

    @Before
    fun start() {
        server = MediaServer(InetAddress.getLoopbackAddress())
        server.start()
    }

    @After
    fun stop() = server.stop()

    @Test
    fun aSongInTheQueueIsServedWholeWithItsType() {
        val url = server.offer("q:1", ServedBytes("audio/flac", song), "flac")!!
        assertTrue(url.endsWith(".flac"))
        val reply = ask(url)
        assertEquals(200, reply.status)
        assertEquals("audio/flac", reply.headers["content-type"])
        assertEquals("1000", reply.headers["content-length"])
        assertEquals("bytes", reply.headers["accept-ranges"])
        assertEquals("Streaming", reply.headers["transfermode.dlna.org"])
        assertTrue(reply.headers["contentfeatures.dlna.org"]!!.startsWith("DLNA.ORG_OP=01"))
        assertArrayEquals(song, reply.body)
    }

    @Test
    fun aRangeIsServedAsAPart() {
        val url = server.offer("q:1", ServedBytes("audio/mpeg", song))!!
        val reply = ask(url, extra = listOf("Range: bytes=100-199"))
        assertEquals(206, reply.status)
        assertEquals("bytes 100-199/1000", reply.headers["content-range"])
        assertEquals("100", reply.headers["content-length"])
        assertArrayEquals(song.copyOfRange(100, 200), reply.body)

        val tail = ask(url, extra = listOf("Range: bytes=-10"))
        assertEquals(206, tail.status)
        assertEquals("bytes 990-999/1000", tail.headers["content-range"])
        assertArrayEquals(song.copyOfRange(990, 1000), tail.body)

        val rest = ask(url, extra = listOf("Range: bytes=900-"))
        assertEquals("bytes 900-999/1000", rest.headers["content-range"])
        assertEquals(100, rest.body.size)
    }

    @Test
    fun aRangePastTheEndIsRefused() {
        val url = server.offer("q:1", ServedBytes("audio/mpeg", song))!!
        val reply = ask(url, extra = listOf("Range: bytes=1000-"))
        assertEquals(416, reply.status)
        assertEquals("bytes */1000", reply.headers["content-range"])
        assertEquals(0, reply.body.size)
    }

    @Test
    fun headSendsTheHeadersOnly() {
        val url = server.offer("q:1", ServedBytes("audio/mpeg", song))!!
        val reply = ask(url, method = "HEAD")
        assertEquals(200, reply.status)
        assertEquals("1000", reply.headers["content-length"])
        assertEquals(0, reply.body.size)
    }

    @Test
    fun onlyGetAndHeadAreAnswered() {
        val url = server.offer("q:1", ServedBytes("audio/mpeg", song))!!
        assertEquals(405, ask(url, method = "POST").status)
    }

    @Test
    fun anUnknownAddressIsRefused() {
        val url = server.offer("q:1", ServedBytes("audio/mpeg", song))!!
        val base = url.substringBeforeLast('/')
        assertEquals(404, ask("$base/made-up-token").status)
        assertEquals(404, ask(server.base + "/").status)
        assertEquals(404, ask(server.base + "/etc/passwd").status)
        // A guess one character off is still a stranger.
        val token = url.substringAfterLast('/')
        val near = token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'
        assertEquals(404, ask("$base/$near").status)
    }

    @Test
    fun aSongThatLeftTheQueueStopsBeingServed() {
        val kept = server.offer("q:1", ServedBytes("audio/mpeg", song))!!
        val gone = server.offer("q:2", ServedBytes("audio/mpeg", song))!!
        server.keepOnly(setOf("q:1"))
        assertEquals(200, ask(kept).status)
        assertEquals(404, ask(gone).status)
        assertFalse(server.isServing("q:2"))
    }

    @Test
    fun eachSongHasItsOwnUnguessableAddressThatStaysPut() {
        val first = server.offer("q:1", ServedBytes("audio/mpeg", song))!!
        val second = server.offer("q:2", ServedBytes("audio/mpeg", song))!!
        assertNotEquals(first, second)
        // The same entry offered again keeps its address.
        assertEquals(first, server.offer("q:1", ServedBytes("audio/mpeg", song)))
        // 128 random bits, written in 22 characters.
        assertEquals(22, first.substringAfterLast('/').length)
    }

    @Test
    fun stoppingForgetsEveryAddress() {
        val url = server.offer("q:1", ServedBytes("audio/mpeg", song))!!
        server.stop()
        assertNull(server.base)
        assertNull(server.offer("q:1", ServedBytes("audio/mpeg", song)))
        server.start()
        // Served again after a restart, it gets a new address.
        val again = server.offer("q:1", ServedBytes("audio/mpeg", song))!!
        assertNotEquals(url.substringAfterLast('/'), again.substringAfterLast('/'))
        assertEquals(200, ask(again).status)
    }

    @Test
    fun contentOfUnknownLengthIsSentWholeIgnoringRanges() {
        val unknown = object : ServedContent {
            override val mimeType = "audio/mpeg"
            override fun open(offset: Long) = Opened(song.inputStream(offset.toInt(), song.size), null)
        }
        val url = server.offer("q:1", unknown)!!
        val reply = ask(url, extra = listOf("Range: bytes=500-"))
        assertEquals(200, reply.status)
        assertNull(reply.headers["content-length"])
        assertTrue(reply.headers["contentfeatures.dlna.org"]!!.startsWith("DLNA.ORG_OP=00"))
        assertArrayEquals(song, reply.body)
    }

    @Test
    fun aGarbledRequestIsRefused() {
        val uri = URI(server.base!!)
        Socket(uri.host, uri.port).use { socket ->
            socket.getOutputStream().write("NONSENSE\r\n\r\n".toByteArray())
            assertEquals(400, read(socket.getInputStream()).status)
        }
    }
}

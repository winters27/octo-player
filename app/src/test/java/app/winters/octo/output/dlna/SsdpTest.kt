package app.winters.octo.output.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpTest {
    @Test
    fun theQuestionAsksForMediaRenderers() {
        val message = searchMessage()
        assertTrue(message.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue("HOST: 239.255.255.250:1900\r\n" in message)
        assertTrue("MAN: \"ssdp:discover\"\r\n" in message)
        assertTrue("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" in message)
        assertTrue(message.endsWith("\r\n\r\n"))
    }

    @Test
    fun aRenderersAnswerIsRead() {
        val reply = parseSsdpReply(
            "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "EXT:\r\n" +
                "location: http://192.168.50.60:9197/dmr\r\n" +
                "SERVER: SHP, UPnP/1.0, Samsung UPnP SDK/1.0\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "USN: uuid:1234-abcd::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "\r\n",
        )!!
        assertEquals("http://192.168.50.60:9197/dmr", reply.location)
        assertEquals("uuid:1234-abcd", reply.deviceId)
        assertEquals("SHP, UPnP/1.0, Samsung UPnP SDK/1.0", reply.server)
    }

    @Test
    fun bareNewlinesAreReadToo() {
        val reply = parseSsdpReply("HTTP/1.1 200 OK\nST: urn:schemas-upnp-org:device:MediaRenderer:2\nLOCATION: http://10.0.0.5/desc.xml\n\n")
        assertEquals("http://10.0.0.5/desc.xml", reply?.location)
        // With no USN, the location stands in as the device's id.
        assertEquals("http://10.0.0.5/desc.xml", reply?.deviceId)
    }

    @Test
    fun otherAnswersAreIgnored() {
        // Not a renderer.
        assertNull(parseSsdpReply("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\nLOCATION: http://10.0.0.5/\r\nUSN: uuid:1\r\n\r\n"))
        // No web address for the description.
        assertNull(parseSsdpReply("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nLOCATION: file:///etc/x\r\n\r\n"))
        assertNull(parseSsdpReply("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"))
        // Another device's own question, or an announcement.
        assertNull(parseSsdpReply(searchMessage()))
        assertNull(parseSsdpReply("NOTIFY * HTTP/1.1\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1\r\nLOCATION: http://10.0.0.5/\r\n\r\n"))
        assertNull(parseSsdpReply(""))
    }
}

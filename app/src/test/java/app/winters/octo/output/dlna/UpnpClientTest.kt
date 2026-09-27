package app.winters.octo.output.dlna

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class UpnpClientTest {
    private val server = MockWebServer()
    private val client = UpnpClient(OkHttpClient())

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun transport() = ServiceEndpoint(AV_TRANSPORT, server.url("/upnp/control/AVTransport1").toString(), null)

    @Test
    fun aRequestGoesAsASoapPostNamingItsAction() {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                    "<u:GetTransportInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                    "<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState><CurrentSpeed>1</CurrentSpeed>" +
                    "</u:GetTransportInfoResponse></s:Body></s:Envelope>",
            ).build(),
        )
        val answer = client.call(transport(), "GetTransportInfo", listOf("InstanceID" to "0"))
        assertEquals("PAUSED_PLAYBACK", answer["CurrentTransportState"])

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/upnp/control/AVTransport1", request.url.encodedPath)
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\"", request.headers["SOAPACTION"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("text/xml"))
        assertTrue("<InstanceID>0</InstanceID>" in request.body!!.utf8())
    }

    @Test
    fun aRefusalOverHttp500IsReadForItsNumber() {
        server.enqueue(
            MockResponse.Builder().code(500).body(
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode>" +
                    "<faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
                    "<errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail>" +
                    "</s:Fault></s:Body></s:Envelope>",
            ).build(),
        )
        try {
            client.call(transport(), "SetAVTransportURI", listOf("InstanceID" to "0", "CurrentURI" to "http://x/a.ape"))
            fail("A refusal must throw")
        } catch (e: UpnpError) {
            assertEquals(714, e.code)
        }
    }

    @Test
    fun otherHttpFailuresAreErrorsWithoutANumber() {
        server.enqueue(MockResponse.Builder().code(404).build())
        try {
            client.call(transport(), "Play", listOf("InstanceID" to "0", "Speed" to "1"))
            fail("A 404 must throw")
        } catch (e: UpnpError) {
            assertEquals(null, e.code)
        }
    }

    @Test
    fun aDescriptionIsFetched() {
        server.enqueue(MockResponse.Builder().code(200).body("<root/>").build())
        assertEquals("<root/>", client.get(server.url("/desc.xml").toString()))
    }
}

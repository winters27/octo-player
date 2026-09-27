package app.winters.octo.output.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpnpTest {
    @Test
    fun aRequestIsASoapEnvelopeWithItsValuesEscaped() {
        val body = soapRequest(AV_TRANSPORT, "Seek", listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "0:01:05"))
        assertTrue(body.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope"))
        assertTrue("<u:Seek xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" in body)
        assertTrue("<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:01:05</Target>" in body)
        assertTrue(body.endsWith("</u:Seek></s:Body></s:Envelope>"))
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Seek\"", soapAction(AV_TRANSPORT, "Seek"))
    }

    @Test
    fun songDetailsAreEscapedTwiceInsideTheRequest() {
        val didl = didlLite(DidlTrack(url = "http://p/m/t.mp3?a=1&b=2", mimeType = "audio/mpeg", title = "Rock & <Roll>"))
        val body = soapRequest(AV_TRANSPORT, "SetAVTransportURI", listOf("InstanceID" to "0", "CurrentURI" to "http://p/m/t.mp3?a=1&b=2", "CurrentURIMetaData" to didl))
        // The address once, the song's details twice: once in DIDL, once in SOAP.
        assertTrue("<CurrentURI>http://p/m/t.mp3?a=1&amp;b=2</CurrentURI>" in body)
        assertTrue("Rock &amp;amp; &amp;lt;Roll&amp;gt;" in body)
        assertFalse("<dc:title>" in body)
        // Read back as a renderer would, it is the DIDL document again.
        val sent = parseXml(body)!!.documentElement.descendants("CurrentURIMetaData").single().textContent
        assertEquals(didl, sent)
    }

    @Test
    fun anAnswerIsReadIntoItsValues() {
        val answer = """<?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <Track>1</Track>
                  <TrackDuration>0:04:12.500</TrackDuration>
                  <TrackMetaData>&lt;DIDL-Lite/&gt;</TrackMetaData>
                  <TrackURI>http://192.168.1.2:4000/m/abc.flac</TrackURI>
                  <RelTime>0:01:02</RelTime>
                  <AbsTime>NOT_IMPLEMENTED</AbsTime>
                </u:GetPositionInfoResponse>
              </s:Body>
            </s:Envelope>"""
        val values = parseSoapResponse(answer, "GetPositionInfo")
        assertEquals("http://192.168.1.2:4000/m/abc.flac", values["TrackURI"])
        assertEquals(252_500L, parseUpnpTime(values["TrackDuration"]))
        assertEquals(62_000L, parseUpnpTime(values["RelTime"]))
        assertNull(parseUpnpTime(values["AbsTime"]))
        assertEquals("<DIDL-Lite/>", values["TrackMetaData"])
    }

    @Test
    fun anAnswerWithoutPrefixesIsReadToo() {
        val answer = "<Envelope><Body><GetTransportInfoResponse><CurrentTransportState>PLAYING</CurrentTransportState></GetTransportInfoResponse></Body></Envelope>"
        assertEquals("PLAYING", parseSoapResponse(answer, "GetTransportInfo")["CurrentTransportState"])
    }

    @Test
    fun aRefusalCarriesItsNumber() {
        val fault = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
            <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
            <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>602</errorCode><errorDescription>Optional Action Not Implemented</errorDescription></UPnPError></detail>
            </s:Fault></s:Body></s:Envelope>"""
        try {
            parseSoapResponse(fault, "SetNextAVTransportURI")
            fail("A refusal must throw")
        } catch (e: UpnpError) {
            assertEquals(602, e.code)
            assertTrue(e.message!!.contains("Optional Action Not Implemented"))
        }
    }

    @Test
    fun garbageIsAnError() {
        try {
            parseSoapResponse("not xml at all", "Play")
            fail("Garbage must throw")
        } catch (e: UpnpError) {
            assertNull(e.code)
        }
    }

    @Test
    fun timesAreReadAndWritten() {
        assertEquals(3_723_000L, parseUpnpTime("1:02:03"))
        assertEquals(3_723_250L, parseUpnpTime("01:02:03.25"))
        assertNull(parseUpnpTime("NOT_IMPLEMENTED"))
        assertNull(parseUpnpTime("1:75:00"))
        assertNull(parseUpnpTime(""))
        assertNull(parseUpnpTime(null))
        assertEquals("0:01:05", formatUpnpTime(65_999))
        assertEquals("1:02:03", formatUpnpTime(3_723_000))
        assertEquals("0:04:12.500", formatUpnpDuration(252_500))
    }

    @Test
    fun aDescriptionGivesTheRenderersNameAndServices() {
        val xml = """<?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Living Room TV</friendlyName>
                <manufacturer>Samsung</manufacturer>
                <modelName>QN90</modelName>
                <UDN>uuid:1234</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                    <controlURL>/upnp/control/RenderingControl1</controlURL>
                    <SCPDURL>/RenderingControl_1.xml</SCPDURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>upnp/control/AVTransport1</controlURL>
                    <SCPDURL>AVTransport_1.xml</SCPDURL>
                  </service>
                </serviceList>
              </device>
            </root>"""
        val renderer = parseDescription(xml, "http://192.168.50.60:9197/dmr/desc.xml")!!
        assertEquals("Living Room TV", renderer.name)
        assertEquals("uuid:1234", renderer.id)
        assertEquals("Samsung", renderer.manufacturer)
        assertEquals("http://192.168.50.60:9197/dmr/upnp/control/AVTransport1", renderer.transport.controlUrl)
        assertEquals("http://192.168.50.60:9197/dmr/AVTransport_1.xml", renderer.transport.actionsUrl)
        assertEquals("http://192.168.50.60:9197/upnp/control/RenderingControl1", renderer.rendering?.controlUrl)
        assertNull(renderer.connections)
    }

    @Test
    fun aRendererInsideAnotherDeviceIsFound() {
        val xml = """<root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>http://10.0.0.9:8080/</URLBase>
            <device><deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType><friendlyName>Receiver</friendlyName>
              <deviceList><device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Receiver (music)</friendlyName><UDN>uuid:inner</UDN>
                <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType>
                  <controlURL>/av</controlURL></service></serviceList>
              </device></deviceList>
            </device></root>"""
        val renderer = parseDescription(xml, "http://10.0.0.9:49152/desc.xml")!!
        assertEquals("Receiver (music)", renderer.name)
        // The newer service type is kept, so requests name it exactly.
        assertEquals("urn:schemas-upnp-org:service:AVTransport:2", renderer.transport.type)
        assertEquals("http://10.0.0.9:8080/av", renderer.transport.controlUrl)
    }

    @Test
    fun aDeviceWithNoTransportIsNotARenderer() {
        val xml = """<root><device><deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>NAS</friendlyName></device></root>"""
        assertNull(parseDescription(xml, "http://10.0.0.2/"))
        assertNull(parseDescription("<<<", "http://10.0.0.2/"))
    }

    @Test
    fun aDescriptionThatPointsElsewhereIsNotFollowed() {
        val xml = """<?xml version="1.0"?><!DOCTYPE root [<!ENTITY x SYSTEM "file:///etc/hosts">]>
            <root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>&x;</friendlyName>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av</controlURL></service></serviceList>
            </device></root>"""
        val renderer = parseDescription(xml, "http://10.0.0.2/")
        // Refused outright, or read without the outside file in it.
        assertTrue(renderer == null || "localhost" !in renderer.name)
    }

    @Test
    fun theActionListSaysWhetherNextSongsCanBeQueued() {
        val scpd = """<scpd xmlns="urn:schemas-upnp-org:service-1-0"><actionList>
            <action><name>SetAVTransportURI</name></action><action><name>SetNextAVTransportURI</name></action>
            </actionList></scpd>"""
        assertTrue(hasAction(scpd, "SetNextAVTransportURI"))
        assertFalse(hasAction(scpd, "Record"))
        assertFalse(hasAction("junk", "Play"))
    }

    @Test
    fun theLoudestVolumeComesFromTheRenderersList() {
        val scpd = """<scpd><serviceStateTable>
            <stateVariable><name>Mute</name><dataType>boolean</dataType></stateVariable>
            <stateVariable><name>Volume</name><dataType>ui2</dataType>
              <allowedValueRange><minimum>0</minimum><maximum>60</maximum><step>1</step></allowedValueRange></stateVariable>
            </serviceStateTable></scpd>"""
        assertEquals(60, volumeMaximum(scpd))
        // Not said, or not readable: out of 100.
        assertEquals(100, volumeMaximum("<scpd/>"))
        assertEquals(100, volumeMaximum(null))
    }
}

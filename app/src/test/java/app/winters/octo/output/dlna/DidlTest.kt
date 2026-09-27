package app.winters.octo.output.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DidlTest {
    private val track = DidlTrack(
        url = "http://192.168.1.2:4000/m/abc.flac",
        mimeType = "audio/flac",
        title = "Tom's \"Song\" <Live> & more",
        artist = "Simon & Garfunkel",
        album = "Bridge <Over>",
        coverUrl = "http://192.168.1.2:4000/m/art.jpg?size=1&x=2",
        durationMs = 252_500,
    )

    @Test
    fun everyDetailIsEscaped() {
        val didl = didlLite(track)
        assertTrue("<dc:title>Tom&apos;s &quot;Song&quot; &lt;Live&gt; &amp; more</dc:title>" in didl)
        assertTrue("<upnp:artist>Simon &amp; Garfunkel</upnp:artist>" in didl)
        assertTrue("<dc:creator>Simon &amp; Garfunkel</dc:creator>" in didl)
        assertTrue("<upnp:album>Bridge &lt;Over&gt;</upnp:album>" in didl)
        assertTrue("<upnp:albumArtURI>http://192.168.1.2:4000/m/art.jpg?size=1&amp;x=2</upnp:albumArtURI>" in didl)
        // It is well formed, and reads back to the same words.
        val doc = parseXml(didl)!!.documentElement
        assertEquals(track.title, doc.descendants("title").single().text())
        assertEquals(track.coverUrl, doc.descendants("albumArtURI").single().text())
    }

    @Test
    fun theResourceSaysHowToFetchAndSeekIt() {
        val didl = didlLite(track)
        assertTrue("<upnp:class>object.item.audioItem.musicTrack</upnp:class>" in didl)
        assertTrue(
            "<res protocolInfo=\"http-get:*:audio/flac:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\" duration=\"0:04:12.500\">http://192.168.1.2:4000/m/abc.flac</res>" in didl,
        )
    }

    @Test
    fun aStationIsABroadcastWithNoLengthOrSeeking() {
        val didl = didlLite(DidlTrack(url = "http://radio/s", mimeType = "audio/mpeg", title = "FM", live = true, seekable = false, durationMs = 5_000))
        assertTrue("object.item.audioItem.audioBroadcast" in didl)
        assertTrue("DLNA.ORG_OP=00" in didl)
        assertFalse("duration=" in didl)
        assertFalse("upnp:artist" in didl)
    }

    @Test
    fun charactersXmlCannotHoldAreLeftOut() {
        val didl = didlLite(track.copy(title = "Bell\u0007 song"))
        assertTrue("<dc:title>Bell song</dc:title>" in didl)
    }

    @Test
    fun aRenderersListSaysWhatItPlays() {
        val sink = splitProtocols("http-get:*:audio/mpeg:*, http-get:*:audio/L16;rate=44100;channels=2:*,http-get:*:audio/flac:DLNA.ORG_PN=FLAC")
        assertTrue(sinkAccepts(sink, "audio/mpeg"))
        assertTrue(sinkAccepts(sink, "audio/FLAC"))
        assertFalse(sinkAccepts(sink, "audio/ogg"))
        assertTrue(sinkAccepts(splitProtocols("http-get:*:audio/*:*"), "audio/ogg"))
        assertTrue(sinkAccepts(splitProtocols("http-get:*:*:*"), "audio/ogg"))
        assertFalse(sinkAccepts(splitProtocols("rtsp-rtp-udp:*:audio/ogg:*"), "audio/ogg"))
        // A renderer that gave no list is trusted to play anything.
        assertTrue(sinkAccepts(splitProtocols(""), "audio/ogg"))
    }
}

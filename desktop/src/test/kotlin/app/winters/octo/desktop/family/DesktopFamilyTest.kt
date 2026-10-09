package app.winters.octo.desktop.family

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.audio.ServerSongs
import app.winters.octo.desktop.system.LaunchRequest
import app.winters.octo.desktop.system.parseLaunchArgs
import app.winters.octo.subsonic.FamilyJoinLink
import app.winters.octo.subsonic.Song
import app.winters.octo.subsonic.familyJoinUrl
import app.winters.octo.ui.family.FamilyNotice
import app.winters.octo.ui.family.qrCode
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.imageio.ImageIO

// The desktop's family parts: QR codes read from pictures and the
// clipboard, links handed over at launch, notices told once, and streams
// that ask for this app's quality only when it picks.
class DesktopFamilyTest {
    @get:Rule val folder = TemporaryFolder()

    private val link = familyJoinUrl("https://music.example.com", "alex", "482913")

    @Test
    fun theClipboardOffersOnlyText() {
        val clipboard = Clipboard("test")
        clipboard.setContents(StringSelection("  $link "), null)
        assertEquals(link, clipboardText(clipboard))
        clipboard.setContents(StringSelection("   "), null)
        assertNull(clipboardText(clipboard))
    }

    @Test
    fun theQrCodeAsDrawnReadsBack() {
        val scene = androidx.compose.ui.ImageComposeScene(300, 300, androidx.compose.ui.unit.Density(1f)) {
            QrImage(link, side = androidx.compose.ui.unit.Dp(280f))
        }
        val image = scene.render()
        val bytes = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
        image.close()
        scene.close()
        val picture = ImageIO.read(java.io.ByteArrayInputStream(bytes))
        val pixels = picture.getRGB(0, 0, picture.width, picture.height, null, 0, picture.width)
        assertEquals(link, app.winters.octo.ui.family.readQr(pixels, picture.width, picture.height))
    }

    @Test
    fun aFamilyLinkInEitherFormIsHandedOverAtLaunch() {
        val https = parseLaunchArgs(listOf(link))
        assertEquals(listOf(LaunchRequest.OpenLink(link)), https)
        val own = "octo://join?server=https%3A%2F%2Fmusic.example.com&username=alex&code=482913"
        assertEquals(listOf(LaunchRequest.OpenLink(own)), parseLaunchArgs(listOf(own)))
        // Any other web address is not a file to play, nor a link.
        assertTrue(parseLaunchArgs(listOf("https://example.com/page"), isFile = { false }).isEmpty())
        assertEquals(FamilyJoinLink("https://music.example.com", "alex", "482913"), app.winters.octo.subsonic.parseFamilyLink(link))
    }

    @Test
    fun noticesAreToldOnceAndKeptAcrossRuns() = runBlocking {
        FakeServer().use { server ->
            var state = "Pending"
            server.answer("getFamily", """"family":{"me":{"username":"winters","role":"Listener","managed":true,"abilities":{}}}""", type = "octo")
            server.answerBy("getFamilyRequests") { server.ok(""""familyRequests":{"request":[{"id":"r1","title":"Angel","artist":"Massive Attack","state":"$state"}]}""", type = "octo") }
            val file = NoticeFile(File(folder.root, "family-notices.json"))
            val client = server.client()
            val first = app.winters.octo.ui.family.checkFamilyNotices(client, file.read("s1"))
            file.write("s1", first.second)
            assertTrue(first.first.isEmpty())
            state = "Approved"
            val (told, memory) = app.winters.octo.ui.family.checkFamilyNotices(client, NoticeFile(File(folder.root, "family-notices.json")).read("s1"))
            assertEquals(listOf(FamilyNotice("r1", "Approved", "Angel · Massive Attack is on its way to your library.")), told)
            file.write("s1", memory)
            assertTrue(app.winters.octo.ui.family.checkFamilyNotices(client, file.read("s1")).first.isEmpty())
            // Another server keeps its own.
            assertTrue(file.read("s2").told.isEmpty())
        }
    }

    @Test
    fun streamsAskForThisAppsQualityOnlyWhenItPicks() {
        FakeServer().use { server ->
            val client = server.client()
            val song = Song("tr-1", "One")
            val raw = ServerSongs(client = { client }).addressOf(song)!!.source.toHttpUrl()
            assertEquals("raw", raw.queryParameter("format"))
            val picked = ServerSongs(params = { app.winters.octo.ui.family.streamParams(true, app.winters.octo.subsonic.StreamQuality.Standard) }, client = { client })
                .addressOf(song)!!.source.toHttpUrl()
            assertEquals("opus", picked.queryParameter("format"))
            assertEquals("160", picked.queryParameter("maxBitRate"))
        }
    }
}

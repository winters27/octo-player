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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

// The desktop's family parts: QR codes read from pictures and the
// clipboard, links handed over at launch, notices told once, and streams
// that ask for this app's quality only when it picks.
class DesktopFamilyTest {
    @get:Rule val folder = TemporaryFolder()

    private val link = familyJoinUrl("https://music.example.com", "alex", "482913")

    // The link's QR code as a picture, four pixels a square, with a border.
    private fun qrPicture(text: String = link): BufferedImage {
        val code = qrCode(text)
        val scale = 4
        val side = (code.size + 8) * scale
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until side) for (x in 0 until side) image.setRGB(x, y, 0xFFFFFF)
        for (y in 0 until code.size) for (x in 0 until code.size) if (code.isDark(x, y)) {
            for (dy in 0 until scale) for (dx in 0 until scale) image.setRGB((x + 4) * scale + dx, (y + 4) * scale + dy, 0)
        }
        return image
    }

    private class Picture(val image: java.awt.Image) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any = image
    }

    @Test
    fun aQrCodeIsReadFromAPictureAFileAndTheClipboard() {
        assertEquals(link, readQrImage(qrPicture()))
        val file = File(folder.root, "code.png").also { ImageIO.write(qrPicture(), "png", it) }
        assertEquals(link, readQrFile(file))
        val clipboard = Clipboard("test")
        clipboard.setContents(Picture(qrPicture()), null)
        assertEquals(link, readClipboardForLink(clipboard))
        clipboard.setContents(StringSelection("  $link "), null)
        assertEquals(link, readClipboardForLink(clipboard))
        // The quiet check on opening reads text only.
        clipboard.setContents(Picture(qrPicture()), null)
        assertNull(clipboardText(clipboard))
        assertNull(readQrImage(BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB)))
    }

    // A camera that shows one picture, again and again.
    class StillCamera(private val image: BufferedImage) : CameraSource {
        private var serial = 0L
        override fun names() = listOf("Test camera")
        override fun open(index: Int) = 1L
        override fun close(handle: Long) = Unit
        override fun picture(handle: Long, seen: Long, buffer: ByteArray, size: IntArray): Long {
            size[0] = image.width
            size[1] = image.height
            if (buffer.size < image.width * image.height * 3) return 0
            var at = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val p = image.getRGB(x, y)
                buffer[at++] = (p shr 16).toByte()
                buffer[at++] = (p shr 8).toByte()
                buffer[at++] = p.toByte()
            }
            Thread.sleep(20)
            return ++serial
        }
    }

    @Test
    fun theCameraScannerFindsTheFamilyLink() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val scanner = CameraScanner({ StillCamera(qrPicture()) }, scope)
            scanner.start()
            val end = System.currentTimeMillis() + 10_000
            while (scanner.found == null && System.currentTimeMillis() < end) Thread.sleep(20)
            assertEquals(FamilyJoinLink("https://music.example.com", "alex", "482913"), scanner.found)
            assertEquals(listOf("Test camera"), scanner.cameras)
            // A QR code that is not a family link is said so, and scanning goes on.
            val other = CameraScanner({ StillCamera(qrPicture("https://example.com/menu")) }, scope)
            other.start()
            val end2 = System.currentTimeMillis() + 10_000
            while (!other.notALink && System.currentTimeMillis() < end2) Thread.sleep(20)
            assertTrue(other.notALink)
            assertTrue(other.scanning)
            other.stop()
            // No camera: plain words.
            val none = CameraScanner({ null }, scope)
            none.start()
            val end3 = System.currentTimeMillis() + 5_000
            while (none.problem == null && System.currentTimeMillis() < end3) Thread.sleep(20)
            assertTrue(none.problem!!.contains("picture"))
        } finally {
            scope.cancel()
        }
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
        assertEquals(link, readQrImage(ImageIO.read(java.io.ByteArrayInputStream(bytes))))
    }

    @Test
    fun cameraPicturesTurnIntoPixels() {
        val rgb = byteArrayOf(10, 20, 30, -1, 0, 0)
        val argb = toArgb(rgb, 2, 1)
        assertEquals(0xFF0A141E.toInt(), argb[0])
        assertEquals(0xFFFF0000.toInt(), argb[1])
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

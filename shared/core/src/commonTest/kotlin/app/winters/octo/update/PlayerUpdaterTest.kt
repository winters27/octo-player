package app.winters.octo.update

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

// The whole path from GitHub's list to a checked file on disk, against a
// pretend GitHub on this machine.
class PlayerUpdaterTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val signer = TestSigner()
    private val calls = CopyOnWriteArrayList<RecordedRequest>()
    private val client = OkHttpClient()

    // What the pretend GitHub serves: files by name, and the release list.
    private val files = HashMap<String, ByteArray>()
    private var served: (String) -> ByteArray? = { files[it] }
    private var listAnswer: (RecordedRequest) -> MockResponse = { MockResponse.Builder().body(releasesJson()).addHeader("ETag", "\"one\"").build() }
    private val releases = mutableListOf<Triple<String, Boolean, List<String>>>()

    private val installer = ByteArray(300_000) { (it * 31 % 251).toByte() }

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += request
                val path = request.url.encodedPath
                if (path == "/repos/winters27/octo/releases") return listAnswer(request)
                val name = request.url.pathSegments.last()
                val bytes = served(name) ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().body(okio.Buffer().write(bytes)).build()
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // Publishes a release as the workflow would: the installer, its manifest
    // and the signature.
    private fun publish(tag: String, version: String, pre: Boolean = false, bytes: ByteArray = installer, signWith: TestSigner = signer) {
        val name = "Octo-$version-windows-x64.msi"
        files[name] = bytes
        val manifest = manifestBytes(sampleManifest(tag, version, assets = listOf(ManifestAsset(name, sha(bytes), bytes.size.toLong(), "windows", "x64", "msi"))))
        files["$tag-update.json"] = manifest
        files["$tag-update.json.sig"] = signWith.sign(manifest).toByteArray()
        releases += Triple(tag, pre, listOf(name))
    }

    private fun releasesJson(): String = releases.joinToString(",", "[", "]") { (tag, pre, names) ->
        // A server release carries none of the players' files.
        val assets = (names + listOf("$tag-update.json", "$tag-update.json.sig")).mapNotNull { name -> files[name]?.let { name to it.size } }
        val list = assets.joinToString(",") { (name, size) ->
            // GitHub names the manifest the same in every release; the
            // pretend one keeps each release's apart by its URL.
            val shown = name.removePrefix("$tag-")
            """{"name":"$shown","size":$size,"browser_download_url":"${server.url("/download/$tag/$name")}"}"""
        }
        """{"tag_name":"$tag","draft":false,"prerelease":$pre,"assets":[$list]}"""
    }

    private val folder by lazy { temp.newFolder("updates") }

    private fun updater(running: String = "1.1.0", keys: List<ByteArray> = listOf(signer.publicKey)) = PlayerUpdater(
        PlayerApp.Desktop,
        PlayerVersion.parse(running)!!,
        ReleaseFeed(client, java.io.File(folder, "releases.json"), "Octo test", apiBase = server.url("/")),
        client,
        folder,
        keys,
        pick = { assets -> assets.firstOrNull { it.kind == "msi" } },
    )

    private fun downloads() = calls.count { it.url.pathSegments.last().endsWith(".msi") }

    @Test
    fun aNewerReleaseIsDownloadedAndChecked() {
        publish("desktop-v1.2.0", "1.2.0")
        releases += Triple("2026.09.23", false, emptyList())
        val ready = updater().check(early = false) as UpdateCheck.Ready
        assertEquals("1.2.0", ready.version.toString())
        assertEquals("Octo keeps itself up to date.", ready.notes)
        assertTrue(ready.file.readBytes().contentEquals(installer))
        assertFalse(java.io.File(ready.file.path + ".part").exists())
        // GitHub is asked as its API asks to be.
        val list = calls.first()
        assertEquals("application/vnd.github+json", list.headers["Accept"])
        assertEquals("Octo test", list.headers["User-Agent"])
        assertEquals("100", list.url.queryParameter("per_page"))
    }

    @Test
    fun aChangedInstallerIsDeletedAndNothingIsReady() {
        publish("desktop-v1.2.0", "1.2.0")
        // The file served is not the one the manifest names, at the same size.
        val tampered = installer.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        served = { name -> if (name.endsWith(".msi")) tampered else files[name] }
        val result = updater().check(early = false)
        assertTrue(result.toString(), result is UpdateCheck.Refused)
        assertTrue((result as UpdateCheck.Refused).reason.contains("does not match"))
        assertFalse(java.io.File(folder, "desktop-v1.2.0").exists())
    }

    @Test
    fun anInstallerOfTheWrongSizeIsRefused() {
        publish("desktop-v1.2.0", "1.2.0")
        served = { name -> if (name.endsWith(".msi")) installer + 1 else files[name] }
        assertTrue(updater().check(early = false) is UpdateCheck.Refused)
        assertFalse(java.io.File(folder, "desktop-v1.2.0").exists())
    }

    @Test
    fun aReleaseSignedByAnotherKeyIsRefusedBeforeAnyDownload() {
        publish("desktop-v1.2.0", "1.2.0", signWith = TestSigner())
        val result = updater().check(early = false)
        assertTrue(result is UpdateCheck.Refused)
        assertEquals(0, downloads())
    }

    @Test
    fun aChangedManifestIsRefusedBeforeAnyDownload() {
        publish("desktop-v1.2.0", "1.2.0")
        val manifest = files["desktop-v1.2.0-update.json"]!!
        files["desktop-v1.2.0-update.json"] = String(manifest).replace("Octo keeps", "Octo keep").toByteArray()
        assertTrue(updater().check(early = false) is UpdateCheck.Refused)
        assertEquals(0, downloads())
    }

    @Test
    fun aReleaseWithoutAManifestIsRefused() {
        publish("desktop-v1.2.0", "1.2.0")
        files.remove("desktop-v1.2.0-update.json.sig")
        releases.clear()
        releases += Triple("desktop-v1.2.0", false, listOf("Octo-1.2.0-windows-x64.msi"))
        files["desktop-v1.2.0-update.json.sig"] = ByteArray(0)
        // Its signature is empty.
        assertTrue(updater().check(early = false) is UpdateCheck.Refused)
    }

    @Test
    fun theServersReleasesAreNeverTakenForUpdates() {
        releases += Triple("2026.09.23", false, emptyList())
        releases += Triple("v3.0.0", false, emptyList())
        assertEquals(UpdateCheck.UpToDate, updater().check(early = false))
    }

    @Test
    fun earlyVersionsWaitUntilAskedFor() {
        publish("desktop-v1.3.0-beta.1", "1.3.0-beta.1", pre = true)
        assertEquals(UpdateCheck.UpToDate, updater().check(early = false))
        assertTrue(updater().check(early = true) is UpdateCheck.Ready)
    }

    @Test
    fun aFileAlreadyCheckedIsNotFetchedAgain() {
        publish("desktop-v1.2.0", "1.2.0")
        updater().check(early = false) as UpdateCheck.Ready
        updater().check(early = false) as UpdateCheck.Ready
        assertEquals(1, downloads())
    }

    @Test
    fun anUnchangedListIsAnsweredFromItsETag() {
        publish("desktop-v1.2.0", "1.2.0")
        listAnswer = { request ->
            if (request.headers["If-None-Match"] == "\"one\"") MockResponse.Builder().code(304).build()
            else MockResponse.Builder().body(releasesJson()).addHeader("ETag", "\"one\"").build()
        }
        updater().check(early = false) as UpdateCheck.Ready
        updater().check(early = false) as UpdateCheck.Ready
        val lists = calls.filter { it.url.encodedPath.endsWith("/releases") }
        assertNull(lists[0].headers["If-None-Match"])
        assertEquals("\"one\"", lists[1].headers["If-None-Match"])
    }

    @Test
    fun aRateLimitSaysWhenToComeBack() {
        listAnswer = {
            MockResponse.Builder().code(403).addHeader("X-RateLimit-Remaining", "0").addHeader("X-RateLimit-Reset", "2000000000").build()
        }
        val result = updater().check(early = false) as UpdateCheck.Unavailable
        assertEquals(2_000_000_000_000L, result.retryAt)
        assertEquals(UpdateTiming.INTERVAL_MS, UpdateTiming.nextWait(now = 0, retryAt = null))
        assertEquals(2_000_000_000_000L - 5, UpdateTiming.nextWait(now = 5, retryAt = 2_000_000_000_000L))
    }

    @Test
    fun anAnswerThatIsNotAListIsOnlyAWait() {
        listAnswer = { MockResponse.Builder().body("<html>Sign in to the Wi-Fi</html>").build() }
        assertTrue(updater().check(early = false) is UpdateCheck.Unavailable)
    }

    @Test
    fun anOlderReleasesFilesGoOnceANewerOneIsReady() {
        publish("desktop-v1.2.0", "1.2.0")
        updater().check(early = false) as UpdateCheck.Ready
        publish("desktop-v1.2.1", "1.2.1")
        val ready = updater().check(early = false) as UpdateCheck.Ready
        assertEquals("desktop-v1.2.1", ready.tag)
        assertFalse(java.io.File(folder, "desktop-v1.2.0").exists())
        assertTrue(java.io.File(folder, "releases.json").exists())
    }

    @Test
    fun askedNotToDownloadItOnlySaysWhatIsOut() {
        publish("desktop-v1.2.0", "1.2.0")
        val available = updater().check(early = false, download = false) as UpdateCheck.Available
        assertEquals("1.2.0", available.version.toString())
        assertEquals(0, downloads())
    }

    @Test
    fun aReleaseWithNothingForThisSystemSaysSo() {
        publish("desktop-v1.2.0", "1.2.0")
        val none = PlayerUpdater(
            PlayerApp.Desktop, PlayerVersion.parse("1.1.0")!!,
            ReleaseFeed(client, null, "Octo test", apiBase = server.url("/")), client, folder, listOf(signer.publicKey),
            pick = { null },
        ).check(early = false)
        assertTrue(none is UpdateCheck.NoInstaller)
    }
}

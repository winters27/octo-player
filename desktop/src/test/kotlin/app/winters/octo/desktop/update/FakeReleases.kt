package app.winters.octo.desktop.update

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

// A pretend GitHub on this machine holding Octo's releases: the server's
// dated one and the desktop release published last, with its MSI, manifest
// and signature, as the release workflow makes them.
class FakeReleases : AutoCloseable {
    val server = MockWebServer()
    val files = HashMap<String, ByteArray>()
    var releases = "[]"

    // The release key, made with the JDK's own Ed25519, so this also shows
    // the JDK's signatures read the same as OpenSSL's in the app.
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val publicKey: ByteArray = keys.public.encoded.takeLast(32).toByteArray()

    val msi = ByteArray(50_000) { (it % 7).toByte() }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/repos/winters27/octo/releases") return MockResponse.Builder().body(releases).build()
                val bytes = files[request.url.pathSegments.last()] ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().body(okio.Buffer().write(bytes)).build()
            }
        }
        server.start()
    }

    val api get() = server.url("/")

    private fun sign(bytes: ByteArray): String = Signature.getInstance("Ed25519").run {
        initSign(keys.private)
        update(bytes)
        Base64.getEncoder().encodeToString(sign())
    }

    fun publish(version: String, notes: String = "- Octo updates itself.") {
        val tag = "desktop-v$version"
        val name = "Octo-$version-windows-x64.msi"
        val sha = MessageDigest.getInstance("SHA-256").digest(msi).joinToString("") { "%02x".format(it) }
        val quoted = notes.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val manifest = """{"assets":[{"arch":"x64","kind":"msi","name":"$name","os":"windows","sha256":"$sha","size":${msi.size}}],"format":1,"notes":"$quoted","product":"desktop","tag":"$tag","version":"$version"}""".toByteArray()
        files[name] = msi
        files["update.json"] = manifest
        files["update.json.sig"] = sign(manifest).toByteArray()
        val assets = listOf(name, "update.json", "update.json.sig").joinToString(",") {
            """{"name":"$it","size":${files[it]!!.size},"browser_download_url":"${server.url("/dl/$it")}"}"""
        }
        releases = """[{"tag_name":"2026.09.23","assets":[]},{"tag_name":"$tag","prerelease":false,"assets":[$assets]}]"""
    }

    override fun close() = server.close()
}

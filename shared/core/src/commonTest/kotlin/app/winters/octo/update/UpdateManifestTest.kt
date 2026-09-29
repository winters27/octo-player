package app.winters.octo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class UpdateManifestTest {
    private val signer = TestSigner()
    private val asset = ManifestAsset("Octo-1.2.0-windows-x64.msi", "a".repeat(64), 1234, "windows", "x64", "msi")
    private val manifest = sampleManifest(assets = listOf(asset))
    private val bytes = manifestBytes(manifest)

    private fun verify(bytes: ByteArray = this.bytes, signature: String = signer.sign(this.bytes), keys: List<ByteArray> = listOf(signer.publicKey), app: PlayerApp = PlayerApp.Desktop, tag: String = "desktop-v1.2.0") =
        verifiedManifest(bytes, signature, keys, app, tag)

    private fun refused(block: () -> Unit): String = assertThrows(UpdateRefused::class.java) { block() }.message.orEmpty()

    @Test
    fun aManifestSignedByATrustedKeyIsRead() {
        assertEquals(manifest, verify())
    }

    @Test
    fun anyOfSeveralTrustedKeysWillDo() {
        assertEquals(manifest, verify(keys = listOf(TestSigner().publicKey, signer.publicKey)))
    }

    @Test
    fun aChangedManifestIsRefused() {
        val changed = String(bytes).replace("1234", "1235").toByteArray()
        assertTrue(refused { verify(bytes = changed, signature = signer.sign(bytes)) }.contains("signature"))
        // Even a byte of white space.
        assertTrue(refused { verify(bytes = bytes + ' '.code.toByte(), signature = signer.sign(bytes)) }.contains("signature"))
    }

    @Test
    fun anotherKeysSignatureIsRefused() {
        val stranger = TestSigner()
        assertTrue(refused { verify(signature = stranger.sign(bytes)) }.contains("signature"))
        assertTrue(refused { verify(keys = listOf(stranger.publicKey)) }.contains("signature"))
    }

    @Test
    fun aBuildWithNoKeyTrustsNothing() {
        assertTrue(refused { verify(keys = emptyList()) }.contains("no key"))
    }

    @Test
    fun aBrokenSignatureIsRefused() {
        refused { verify(signature = "") }
        refused { verify(signature = "not base64 at all!") }
        refused { verify(signature = Base64.getEncoder().encodeToString(ByteArray(63))) }
    }

    @Test
    fun aSignedManifestForSomethingElseIsRefused() {
        // Another app's, another release's, or naming another version than its tag.
        val phone = manifestBytes(sampleManifest(product = "android", tag = "android-v1.2.0"))
        assertTrue(refused { verify(bytes = phone, signature = signer.sign(phone), tag = "android-v1.2.0") }.contains("another app"))
        assertTrue(refused { verify(tag = "desktop-v1.3.0") }.contains("another release"))
        val older = manifestBytes(sampleManifest(version = "1.1.0"))
        assertTrue(refused { verify(bytes = older, signature = signer.sign(older)) }.contains("another version"))
        val newer = manifestBytes(manifest.copy(format = 2))
        assertTrue(refused { verify(bytes = newer, signature = signer.sign(newer)) }.contains("newer kind"))
    }

    @Test
    fun aFileListedBadlyIsRefused() {
        for (bad in listOf(asset.copy(sha256 = "A".repeat(64)), asset.copy(size = 0), asset.copy(name = "../Octo.msi"), asset.copy(name = "a\\b.msi"))) {
            val signed = manifestBytes(manifest.copy(assets = listOf(bad)))
            refused { verify(bytes = signed, signature = signer.sign(signed)) }
        }
    }

    // What the release workflow makes: OpenSSL's `pkeyutl -sign -rawin` over
    // the manifest's bytes, checked here with the same key's public half.
    @Test
    fun opensslsSignaturesAreRead() {
        val signed = javaClass.getResourceAsStream("/update/openssl-signed.json")!!.readBytes()
        val signature = javaClass.getResourceAsStream("/update/openssl-signed.json.sig")!!.readBytes().toString(Charsets.US_ASCII)
        val key = Base64.getDecoder().decode("VBNe2ApJo4Wfs9Bb/0GXQckCIY12L0mUqW5cGPHatIg=")
        val read = verifiedManifest(signed, signature, listOf(key), PlayerApp.Desktop, "desktop-v1.2.0")
        assertEquals("Octo-1.2.0-windows-x64.msi", read.assets.single().name)
        assertFalse(Ed25519Check.verify(key, signed + 1, Base64.getDecoder().decode(signature)))
    }

    @Test
    fun keysAreReadOneALine() {
        val text = """
            # a comment
            ${Base64.getEncoder().encodeToString(signer.publicKey)}   # the release key

        """.trimIndent()
        assertEquals(listOf(signer.publicKey.toList()), parseKeys(text).map { it.toList() })
        assertThrows(IllegalArgumentException::class.java) { parseKeys("c2hvcnQ=") }
    }

    @Test
    fun theBuildsKeyListReads() {
        // Empty until the release key's public half is added; never broken.
        trustedKeys()
    }
}

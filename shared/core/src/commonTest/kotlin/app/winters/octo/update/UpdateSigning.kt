package app.winters.octo.update

import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.Base64

// A signing key made for a test, standing in for the release workflow's.
class TestSigner {
    private val private = Ed25519PrivateKeyParameters(SecureRandom())
    val publicKey: ByteArray = private.generatePublicKey().encoded

    fun sign(bytes: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, private)
        signer.update(bytes, 0, bytes.size)
        return Base64.getEncoder().encodeToString(signer.generateSignature())
    }
}

private val compact = Json { encodeDefaults = true }

// A manifest's bytes as the release workflow writes them.
fun manifestBytes(manifest: UpdateManifest): ByteArray = compact.encodeToString(UpdateManifest.serializer(), manifest).toByteArray()

fun sampleManifest(tag: String = "desktop-v1.2.0", version: String = "1.2.0", product: String = "desktop", assets: List<ManifestAsset> = emptyList()) =
    UpdateManifest(MANIFEST_FORMAT, product, version, tag, "Octo keeps itself up to date.", assets)

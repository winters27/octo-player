package app.winters.octo.update

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

// Every player release carries update.json, which names the version and
// each file with its size and SHA-256, and update.json.sig, an Ed25519
// signature over update.json's exact bytes (base64). The apps trust a file
// only through a manifest whose signature one of their built-in keys made:
// GitHub's own listing is only where to look.
@Serializable
data class UpdateManifest(
    val format: Int,
    val product: String,
    val version: String,
    val tag: String,
    val notes: String = "",
    val assets: List<ManifestAsset> = emptyList(),
)

// One file of a release. `os` is windows, macos, linux or android; `arch`
// x64, arm64 or any; `kind` the file's type (msi, dmg, deb, rpm, zip, apk).
@Serializable
data class ManifestAsset(
    val name: String,
    val sha256: String,
    val size: Long,
    val os: String = "",
    val arch: String = "",
    val kind: String = "",
)

const val MANIFEST_NAME = "update.json"
const val SIGNATURE_NAME = "update.json.sig"
const val MANIFEST_FORMAT = 1

// Why an update was turned down. Said quietly: nothing is installed.
class UpdateRefused(message: String) : Exception(message)

private val manifestJson = Json { ignoreUnknownKeys = true }

// The manifest in these bytes, once the signature is one of the trusted
// keys' and the manifest is for this app's release under this tag.
// Anything else is refused, and the manifest is not read at all until the
// signature holds.
fun verifiedManifest(
    bytes: ByteArray,
    signature: String,
    keys: List<ByteArray>,
    app: PlayerApp,
    tag: String,
): UpdateManifest {
    if (keys.isEmpty()) throw UpdateRefused("this build has no key to check updates with")
    val raw = try {
        Base64.getMimeDecoder().decode(signature.trim())
    } catch (e: IllegalArgumentException) {
        throw UpdateRefused("the signature is not readable")
    }
    if (keys.none { Ed25519Check.verify(it, bytes, raw) }) throw UpdateRefused("the signature does not match")
    val manifest = try {
        manifestJson.decodeFromString(UpdateManifest.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (e: SerializationException) {
        throw UpdateRefused("the manifest is not readable")
    } catch (e: IllegalArgumentException) {
        throw UpdateRefused("the manifest is not readable")
    }
    if (manifest.format != MANIFEST_FORMAT) throw UpdateRefused("the manifest is a newer kind")
    if (manifest.product != app.product) throw UpdateRefused("the manifest is for another app")
    if (manifest.tag != tag) throw UpdateRefused("the manifest is for another release")
    val named = versionOfTag(tag, app)
    if (named == null || PlayerVersion.parse(manifest.version) != named) throw UpdateRefused("the manifest names another version")
    manifest.assets.forEach { asset ->
        if (!asset.sha256.matches(Sha256Hex) || asset.size <= 0 || asset.name.isBlank() || '/' in asset.name || '\\' in asset.name) {
            throw UpdateRefused("the manifest lists a file badly")
        }
    }
    return manifest
}

private val Sha256Hex = Regex("[0-9a-f]{64}")

// The keys in a list of them: one base64 Ed25519 public key (32 bytes) a
// line, with # starting a comment.
fun parseKeys(text: String): List<ByteArray> = text.lineSequence()
    .map { it.substringBefore('#').trim() }
    .filter(String::isNotEmpty)
    .map { line ->
        val key = try {
            Base64.getDecoder().decode(line)
        } catch (e: IllegalArgumentException) {
            null
        }
        require(key != null && key.size == 32) { "not an Ed25519 public key: $line" }
        key
    }
    .toList()

// The keys this build trusts, from trusted-keys.txt beside this file. The
// release workflow checks each signature it makes against the same file,
// so a release this build would refuse is never published.
fun trustedKeys(): List<ByteArray> {
    val text = UpdateManifest::class.java.getResourceAsStream("trusted-keys.txt")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return emptyList()
    return parseKeys(text)
}

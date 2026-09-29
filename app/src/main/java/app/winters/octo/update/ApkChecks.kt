package app.winters.octo.update

import android.content.pm.PackageInfo
import android.content.pm.Signature
import java.security.MessageDigest

// A downloaded APK installs only when it is this very app, newer, and
// signed with the key this copy was signed with (or a key that key handed
// over to). Android refuses the rest itself, but checking first means a
// wrong file is deleted quietly instead of failing in the installer.
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    // The SHA-256 of each signing certificate it is signed with now.
    val signers: Set<String>,
    // And of every certificate in its signing history, for a rotated key.
    val history: Set<String> = emptySet(),
)

// Why the downloaded APK must not be installed over this app, or null
// when it may be.
fun apkRefusal(installed: ApkFacts, downloaded: ApkFacts?): String? = when {
    downloaded == null -> "the download is not an app Android can read"
    downloaded.packageName != installed.packageName -> "the download is another app"
    downloaded.versionCode <= installed.versionCode -> "the download is not newer than this Octo"
    installed.signers.isEmpty() || downloaded.signers.isEmpty() -> "the download is not signed"
    !installed.signers.all { it in downloaded.signers || it in downloaded.history } -> "the download is signed with another key"
    else -> null
}

fun certificateDigest(signature: Signature): String =
    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

// What an installed or downloaded package says about itself.
@Suppress("DEPRECATION")
fun apkFacts(info: PackageInfo?): ApkFacts? {
    info ?: return null
    val signing = info.signingInfo ?: return ApkFacts(info.packageName, info.longVersionCode, emptySet())
    val now = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory?.takeLast(1)?.toTypedArray()
    val history = if (signing.hasMultipleSigners()) emptyArray() else signing.signingCertificateHistory ?: emptyArray()
    return ApkFacts(
        info.packageName,
        info.longVersionCode,
        now.orEmpty().map(::certificateDigest).toSet(),
        history.map(::certificateDigest).toSet(),
    )
}

// The phone's file among a release's files.
fun apkFor(assets: List<ManifestAsset>): ManifestAsset? = assets.firstOrNull { it.os == "android" && it.kind == "apk" }

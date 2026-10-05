package app.winters.octo.catalog

import java.security.MessageDigest

// The pictures a server sends in place of a cover it cannot give. Navidrome
// answers getCoverArt with its blue record ("navidrome" on the label) when
// it has no picture, or cannot read the one it has: a 200 like any cover,
// so only its bytes tell it apart. It is the same file at every size asked
// for, since Navidrome sends it without resizing it.
//
// Kept as a stand-in, it stays where the real cover should be for good:
// covers are cached under their id, and the id only changes when the album
// does. A song added from an online result showed the record that way in
// its list rows while the server had its real cover.
object StandInCovers : StandIns(
    // SHA-256 of each known stand-in, by its length in bytes. A server that
    // ships a new picture needs its own line here.
    mapOf(
        // Navidrome's album-placeholder.webp, 1024 by 1024, as Navidrome sent it in October 2026.
        69_228 to setOf("273a4dbd61dfbb0a12d5d8ffe780eb7a3d4d000bc9771c5411cc70ae4dfa8a1f"),
    ),
)

// Stand-ins told apart by their length and digest.
open class StandIns(private val known: Map<Int, Set<String>>) {
    // The longest stand-in, so a reader knows how far to look before
    // knowing a picture is a real one.
    val longest: Int = known.keys.maxOrNull() ?: 0

    // Whether a picture this many bytes long could be a stand-in at all,
    // before reading it through.
    fun mayBe(length: Long): Boolean = length in 1..longest && length.toInt() in known

    fun isStandIn(bytes: ByteArray): Boolean {
        val digests = known[bytes.size] ?: return false
        return sha256(bytes) in digests
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

package app.winters.octo.desktop.server

import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec

// A client certificate's private key in the form the audio engine takes:
// PKCS#8 DER its TLS library can sign with. RSA and Ed25519 keys go as the
// JDK encodes them. An EC key is written again, since the JDK leaves out the
// public key that library needs: an RFC 5915 key naming its curve in [0]
// and holding the certificate's own point in [1], its scalar exactly as
// long as the curve's order. Null when the key has no encoding, or an EC key
// cannot be written so: a curve given by its numbers rather than its name, a
// certificate whose key is not on the same curve, or a scalar longer than
// the order.
fun engineKeyOf(key: PrivateKey, leaf: X509Certificate): ByteArray? {
    if (key !is ECPrivateKey) return key.encoded
    val point = leaf.publicKey as? ECPublicKey ?: return null
    if (!sameCurve(key.params, point.params)) return null
    val encoded = key.encoded ?: return null
    // PrivateKeyInfo: its version, the algorithm with the curve, the key.
    val info = only(encoded, SEQUENCE)?.children() ?: return null
    val algorithm = info.getOrNull(1)?.takeIf { it.tag == SEQUENCE }?.children() ?: return null
    val curve = algorithm.getOrNull(1)?.takeIf { it.tag == OID }?.whole ?: return null
    val wrapped = info.getOrNull(2)?.takeIf { it.tag == OCTET_STRING } ?: return null
    // ECPrivateKey: its version, then the scalar.
    val inner = only(wrapped.content, SEQUENCE)?.children() ?: return null
    val octets = inner.getOrNull(1)?.takeIf { it.tag == OCTET_STRING } ?: return null
    val scalar = fixedLength(octets.content, (key.params.order.bitLength() + 7) / 8) ?: return null
    val size = (point.params.curve.field.fieldSize + 7) / 8
    val x = fixedLength(point.w.affineX.toByteArray(), size) ?: return null
    val y = fixedLength(point.w.affineY.toByteArray(), size) ?: return null
    // No unused bits, then an uncompressed point.
    val publicKey = der(BIT_STRING, byteArrayOf(0, 4), x, y)
    val ecKey = der(
        SEQUENCE,
        der(INTEGER, byteArrayOf(1)),
        der(OCTET_STRING, scalar),
        der(CURVE_TAG, curve),
        der(POINT_TAG, publicKey),
    )
    return der(SEQUENCE, der(INTEGER, byteArrayOf(0)), der(SEQUENCE, EC_PUBLIC_KEY, curve), der(OCTET_STRING, ecKey))
}

// `bytes` as a number exactly `size` bytes long, big-endian: leading zero
// bytes taken off, then zeros put in front up to `size`. Null when it is
// still longer than that.
internal fun fixedLength(bytes: ByteArray, size: Int): ByteArray? {
    val first = bytes.indexOfFirst { it != 0.toByte() }.takeIf { it >= 0 } ?: bytes.size
    val digits = bytes.copyOfRange(first, bytes.size)
    if (digits.size > size) return null
    return ByteArray(size - digits.size) + digits
}

// Whether two keys are on the same curve; the JDK's parameter objects have
// no equality of their own.
private fun sameCurve(a: ECParameterSpec, b: ECParameterSpec): Boolean =
    a.curve == b.curve && a.order == b.order && a.generator == b.generator && a.cofactor == b.cofactor

private const val INTEGER = 0x02
private const val BIT_STRING = 0x03
private const val OCTET_STRING = 0x04
private const val OID = 0x06
private const val SEQUENCE = 0x30

// An ECPrivateKey's [0] parameters and [1] public key.
private const val CURVE_TAG = 0xA0
private const val POINT_TAG = 0xA1

// id-ecPublicKey (1.2.840.10045.2.1), its tag and length included.
private val EC_PUBLIC_KEY = byteArrayOf(0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)

// A DER element: the tag, the length, then the parts one after another.
private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
    val body = parts.fold(ByteArray(0)) { all, part -> all + part }
    val n = body.size
    val length = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }
    return byteArrayOf(tag.toByte()) + length + body
}

// One DER element inside `bytes`: its tag, and where it and its content lie.
private class Element(val tag: Int, private val bytes: ByteArray, private val at: Int, private val start: Int, val end: Int) {
    // The element itself, its tag and length included.
    val whole: ByteArray get() = bytes.copyOfRange(at, end)

    val content: ByteArray get() = bytes.copyOfRange(start, end)

    // The elements inside it, in order; null when they do not fill it exactly.
    fun children(): List<Element>? = elements(bytes, start, end)
}

// The elements from `from` up to `to`, one after another; null when one is
// cut short or has a length DER does not allow.
private fun elements(bytes: ByteArray, from: Int, to: Int): List<Element>? {
    val found = mutableListOf<Element>()
    var at = from
    while (at < to) {
        val next = element(bytes, at, to) ?: return null
        found += next
        at = next.end
    }
    return found
}

// The element starting at `at`, which must end by `to`.
private fun element(bytes: ByteArray, at: Int, to: Int): Element? {
    if (to - at < 2) return null
    val first = bytes[at + 1].toInt() and 0xFF
    // A short length, or one to three bytes of length after 0x81 to 0x83.
    val count = when {
        first < 0x80 -> 0
        first in 0x81..0x83 -> first - 0x80
        else -> return null
    }
    val start = at + 2 + count
    if (start > to) return null
    var length = if (count == 0) first else 0
    for (i in at + 2 until start) length = (length shl 8) or (bytes[i].toInt() and 0xFF)
    if (length > to - start) return null
    return Element(bytes[at].toInt() and 0xFF, bytes, at, start, start + length)
}

// The one element filling `bytes`, when it has this tag.
private fun only(bytes: ByteArray, tag: Int): Element? =
    elements(bytes, 0, bytes.size)?.singleOrNull()?.takeIf { it.tag == tag }

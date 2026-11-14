package app.winters.octo.desktop.server

import app.winters.octo.audio.clientIdentityProblem
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The audio engine's form of a client certificate's key, made from keys the
// JDK opened from PKCS#12 files keytool made (fixtures/gateway/certs/engine,
// remade by make-keys.ps1 there). The engine's own tests sign with the
// golden .key.der files this checks against, so what engineKeyOf makes from
// a JDK key is what the engine is shown to take. OCTO_WRITE_ENGINE_KEYS=1
// writes the golden files again from the PKCS#12 files.
class EngineKeyTest {
    private val dir = File("../fixtures/gateway/certs/engine")

    // The key and certificate keytool put in `name`.p12.
    private fun opened(name: String): Pair<PrivateKey, X509Certificate> {
        val store = KeyStore.getInstance("PKCS12")
        File(dir, "$name.p12").inputStream().use { store.load(it, PASSWORD) }
        return Pair(store.getKey(ALIAS, PASSWORD) as PrivateKey, store.getCertificate(ALIAS) as X509Certificate)
    }

    // Red when: engineKeyOf gives other bytes than the golden ones the
    // engine's tests sign with.
    @Test
    fun theKeysMadeFromKeytoolFilesAreTheGoldenBytesTheEngineSignsWith() {
        val write = System.getenv("OCTO_WRITE_ENGINE_KEYS") == "1"
        for (name in NAMES) {
            val (key, leaf) = opened(name)
            val made = assertNotNull(engineKeyOf(key, leaf), name)
            val keyFile = File(dir, "$name.key.der")
            val leafFile = File(dir, "$name.leaf.der")
            if (write) {
                keyFile.writeBytes(made)
                leafFile.writeBytes(leaf.encoded)
            }
            assertContentEquals(keyFile.readBytes(), made, "$name key")
            assertContentEquals(leafFile.readBytes(), leaf.encoded, "$name certificate")
        }
    }

    // Red when: an RSA or Ed25519 key is written again rather than passed on.
    @Test
    fun rsaAndEd25519KeysGoAsTheJdkEncodesThem() {
        for (name in listOf("rsa-2048", "ed25519")) {
            val (key, leaf) = opened(name)
            assertContentEquals(key.encoded, engineKeyOf(key, leaf), name)
        }
    }

    // Red when: [0] or [1] is left out, [1] is not the certificate's own
    // point, the scalar is not as long as the curve's order, or the key is
    // not wrapped in PKCS#8 v1 with the curve in its algorithm.
    @Test
    fun anEcKeyIsWrittenWithItsCurveTheCertificatesPointAndAFullLengthScalar() {
        for ((name, curve, size) in listOf(Triple("ec-p256", P256, 32), Triple("ec-p384", P384, 48))) {
            val (key, leaf) = opened(name)
            val s = (key as ECPrivateKey).s
            val w = (leaf.publicKey as ECPublicKey).w
            val point = der(BIT_STRING, byteArrayOf(0, 4), fixed(w.affineX, size), fixed(w.affineY, size))
            val inner = der(SEQUENCE, der(INTEGER, byteArrayOf(1)), der(OCTET_STRING, fixed(s, size)), der(0xA0, curve), der(0xA1, point))
            val expected = der(SEQUENCE, der(INTEGER, byteArrayOf(0)), der(SEQUENCE, EC_PUBLIC_KEY, curve), der(OCTET_STRING, inner))
            val made = assertNotNull(engineKeyOf(key, leaf), name)
            assertContentEquals(expected, made, name)
            // The JDK reads it back as the same key.
            val read = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(made)) as ECPrivateKey
            assertEquals(s, read.s, name)
        }
    }

    // Red when: the scalar is taken from BigInteger.toByteArray, which gives
    // 33 bytes for a scalar with its top bit set and 31 for one with a
    // leading zero byte.
    @Test
    fun aScalarWithItsTopBitSetOrALeadingZeroByteIsWrittenFullLength() {
        val (_, leaf) = opened("ec-p256")
        val params = (leaf.publicKey as ECPublicKey).params
        for (s in listOf(BigInteger("f0" + "5a".repeat(31), 16), BigInteger("00" + "35".repeat(31), 16))) {
            val key = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(s, params))
            val made = assertNotNull(engineKeyOf(key, leaf), s.toString(16))
            assertTrue(made.holds(der(OCTET_STRING, fixed(s, 32)) + byteArrayOf(0xA0.toByte())), s.toString(16))
        }
    }

    // Red when: the scalar is copied from the key's encoding as it is: an
    // encoder that drops a leading zero byte leaves 31 bytes for P-256.
    @Test
    fun aScalarTheEncodingGivesShortIsPaddedToFullLength() {
        val (_, leaf) = opened("ec-p256")
        val digits = ByteArray(31) { 0x35 }
        val inner = der(SEQUENCE, der(INTEGER, byteArrayOf(1)), der(OCTET_STRING, digits))
        val short = der(SEQUENCE, der(INTEGER, byteArrayOf(0)), der(SEQUENCE, EC_PUBLIC_KEY, P256), der(OCTET_STRING, inner))
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(short))
        val made = assertNotNull(engineKeyOf(key, leaf))
        assertTrue(made.holds(der(OCTET_STRING, byteArrayOf(0) + digits) + byteArrayOf(0xA0.toByte())))
    }

    @Test
    fun aNumberIsWrittenExactlyAsLongAsAsked() {
        val full = ByteArray(32) { 0x81.toByte() }
        assertContentEquals(full, fixedLength(full, 32), "already that long")
        assertContentEquals(full, fixedLength(byteArrayOf(0) + full, 32), "with a sign byte in front")
        assertContentEquals(byteArrayOf(0, 0) + ByteArray(30) { 7 }, fixedLength(ByteArray(30) { 7 }, 32), "short")
        assertContentEquals(ByteArray(32), fixedLength(ByteArray(0), 32), "nothing")
        assertNull(fixedLength(byteArrayOf(1) + full, 32), "longer with no zero in front")
    }

    // Red when: the curves are not compared, or a certificate whose key is
    // not EC is taken for one.
    @Test
    fun anEcKeyWithACertificateOnAnotherCurveOrOfAnotherKindIsRefused() {
        val (key, _) = opened("ec-p256")
        assertNull(engineKeyOf(key, opened("ec-p384").second), "another curve")
        assertNull(engineKeyOf(key, opened("rsa-2048").second), "an RSA certificate")
    }

    // The engine itself, through its bindings: it signs with each key made
    // here, and refuses an EC key as the JDK encodes it (with no public key).
    @Test
    fun theEngineTakesEveryKeyMadeHereAndNotTheJdksOwnEcEncoding() {
        for (name in NAMES) {
            val (key, leaf) = opened(name)
            val made = assertNotNull(engineKeyOf(key, leaf), name)
            assertNull(clientIdentityProblem(listOf(leaf.encoded), made), name)
        }
        val (key, leaf) = opened("ec-p256")
        assertNotNull(clientIdentityProblem(listOf(leaf.encoded), key.encoded), "the JDK's own EC encoding")
    }

    private companion object {
        val NAMES = listOf("rsa-2048", "ec-p256", "ec-p384", "ed25519")
        const val ALIAS = "octo"
        val PASSWORD = "octo-test".toCharArray()

        const val INTEGER = 0x02
        const val BIT_STRING = 0x03
        const val OCTET_STRING = 0x04
        const val SEQUENCE = 0x30

        // The OIDs, their tag and length included: id-ecPublicKey, P-256, P-384.
        val EC_PUBLIC_KEY: ByteArray = HexFormat.of().parseHex("06072a8648ce3d0201")
        val P256: ByteArray = HexFormat.of().parseHex("06082a8648ce3d030107")
        val P384: ByteArray = HexFormat.of().parseHex("06052b81040022")

        // A DER element: the tag, the length, then the parts one after another.
        fun der(tag: Int, vararg parts: ByteArray): ByteArray {
            val body = parts.fold(ByteArray(0)) { all, part -> all + part }
            val length = when {
                body.size < 0x80 -> byteArrayOf(body.size.toByte())
                body.size < 0x100 -> byteArrayOf(0x81.toByte(), body.size.toByte())
                else -> byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
            }
            return byteArrayOf(tag.toByte()) + length + body
        }

        // `n` as exactly `size` bytes, big-endian.
        fun fixed(n: BigInteger, size: Int): ByteArray {
            val digits = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(size - digits.size) + digits
        }
    }
}

// Whether `part` appears somewhere in these bytes.
private fun ByteArray.holds(part: ByteArray): Boolean =
    (0..size - part.size).any { at -> part.indices.all { this[at + it] == part[it] } }

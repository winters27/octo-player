package app.winters.octo.update

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

// Ed25519 signatures (RFC 8032), as OpenSSL makes them with
// `openssl pkeyutl -sign -rawin`. Bouncy Castle's lightweight classes, so
// nothing is registered with the system's security providers.
object Ed25519Check {
    const val KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        return try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}

package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.FamilySignInState
import app.winters.octo.subsonic.familySignInRedeem
import app.winters.octo.subsonic.redeemFamilySignIn
import app.winters.octo.subsonic.SubsonicException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.XChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import java.util.Base64

// Signing in on another device of one's own: the signed-in device shows a
// QR code holding a one-time token and a key made on it; the new device
// redeems the token, the first device allows it, seals its sign-in with the
// key and hands the sealed box to the server, and the new device opens it.
// The server relays a box it cannot open: the key is only in the QR code,
// after the #, which never reaches it.

// The sign-in that travels, sealed: who, the secret this app signs in
// with (a password, or what it holds in its place), how it signs in, the
// server's addresses and the account's name.
@Serializable
data class HandOverSignIn(
    val username: String,
    val secret: String,
    val mode: String = "Token",
    val server: String,
    val home: String? = null,
    val displayName: String = "",
) {
    // Never print the secret, even by accident in a log.
    override fun toString() = "HandOverSignIn(username=$username, server=$server, home=$home)"
}

// The box could not be opened: the key is not the one it was sealed with,
// or it was changed on the way.
class HandOverException(message: String) : Exception(message)

// Sealing with XChaCha20-Poly1305 (Bouncy Castle): a random 24 byte nonce,
// a 256 bit key, and the purpose as associated data, so a box made for
// anything else never opens here.
object HandOverBox {
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 24
    private const val TAG_BITS = 128
    private val PURPOSE = "octo-family-signin-1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    // A new key, made on this device, as text for the QR code.
    fun newKey(): String = encoder.encodeToString(ByteArray(KEY_BYTES).also(random::nextBytes))

    fun seal(signIn: HandOverSignIn, key: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val plain = json.encodeToString(HandOverSignIn.serializer(), signIn).toByteArray(Charsets.UTF_8)
        val cipher = XChaCha20Poly1305().apply { init(true, AEADParameters(KeyParameter(keyBytes(key)), TAG_BITS, nonce, PURPOSE)) }
        val out = ByteArray(cipher.getOutputSize(plain.size))
        val written = cipher.processBytes(plain, 0, plain.size, out, 0)
        cipher.doFinal(out, written)
        return encoder.encodeToString(nonce + out)
    }

    fun open(box: String, key: String): HandOverSignIn {
        val raw = runCatching { decoder.decode(box.trim()) }.getOrNull() ?: throw HandOverException("The sign-in from your other device is damaged.")
        if (raw.size <= NONCE_BYTES + TAG_BITS / 8) throw HandOverException("The sign-in from your other device is damaged.")
        val nonce = raw.copyOfRange(0, NONCE_BYTES)
        val sealed = raw.copyOfRange(NONCE_BYTES, raw.size)
        val cipher = XChaCha20Poly1305().apply { init(false, AEADParameters(KeyParameter(keyBytes(key)), TAG_BITS, nonce, PURPOSE)) }
        val out = ByteArray(cipher.getOutputSize(sealed.size))
        val written = cipher.processBytes(sealed, 0, sealed.size, out, 0)
        try {
            cipher.doFinal(out, written)
        } catch (e: InvalidCipherTextException) {
            throw HandOverException("This sign-in could not be opened here. Make a new QR code on your other device.")
        }
        return runCatching { json.decodeFromString(HandOverSignIn.serializer(), String(out, Charsets.UTF_8)) }
            .getOrElse { throw HandOverException("The sign-in from your other device is damaged.") }
    }

    private fun keyBytes(key: String): ByteArray {
        val bytes = runCatching { decoder.decode(key.trim()) }.getOrNull()
        if (bytes == null || bytes.size != KEY_BYTES) throw HandOverException("This sign-in link is not complete. Scan the QR code again.")
        return bytes
    }
}

// What the new device says at each step, in plain words.
const val HANDOVER_WAITING = "Waiting for your other device"
const val HANDOVER_ALLOWED = "Allowed, signing in"
const val HANDOVER_SIGNED_IN = "Signed in"
const val HANDOVER_DENIED = "Your other device said no. Nothing was shared."
const val HANDOVER_EXPIRED = "This sign-in link expired. Make a new one on your other device."
const val HANDOVER_UNREACHABLE = "Octo can't reach the server. Check this device is online, then try again."

// How the new device's side ended: the sign-in to keep, or why not.
sealed interface HandOverResult {
    class Received(val signIn: HandOverSignIn) : HandOverResult
    class Refused(val message: String) : HandOverResult
}

// The new device's side: redeems the token on the address the link was
// opened on, says what is happening, waits for the other device, and opens
// the sealed sign-in with the key from the link. The key is never sent.
suspend fun receiveHandOver(
    link: FamilyHandOverLink,
    http: OkHttpClient,
    deviceName: String,
    platform: FamilyPlatform,
    step: (String) -> Unit = {},
    pollMs: Long = 1_500,
    waitMs: Long = 130_000,
): HandOverResult {
    val base = link.base.toHttpUrlOrNull() ?: return HandOverResult.Refused("This sign-in link has no server in it. Scan the QR code again.")
    step(HANDOVER_WAITING)
    val id = try {
        redeemFamilySignIn(base, http, link.token, deviceName, platform)
    } catch (e: SubsonicException.Unreachable) {
        return HandOverResult.Refused(HANDOVER_UNREACHABLE)
    } catch (e: SubsonicException.NotFound) {
        return HandOverResult.Refused(HANDOVER_EXPIRED)
    } catch (e: SubsonicException) {
        return HandOverResult.Refused(if (e is SubsonicException.WrongCredentials || (e is SubsonicException.NotSubsonic && e.status == 404)) HANDOVER_EXPIRED else e.message ?: HANDOVER_EXPIRED)
    }
    var waited = 0L
    while (waited < waitMs) {
        val status = try {
            familySignInRedeem(base, http, id)
        } catch (e: SubsonicException.Unreachable) {
            null
        } catch (e: SubsonicException) {
            return HandOverResult.Refused(HANDOVER_EXPIRED)
        }
        when (status?.state) {
            FamilySignInState.Allowed -> {
                val box = status.box
                if (box != null) {
                    step(HANDOVER_ALLOWED)
                    return try {
                        HandOverResult.Received(HandOverBox.open(box, link.key))
                    } catch (e: HandOverException) {
                        HandOverResult.Refused(e.message ?: HANDOVER_EXPIRED)
                    }
                }
                step(HANDOVER_ALLOWED)
            }
            FamilySignInState.Denied -> return HandOverResult.Refused(HANDOVER_DENIED)
            FamilySignInState.Expired -> return HandOverResult.Refused(HANDOVER_EXPIRED)
            else -> Unit
        }
        delay(pollMs)
        waited += pollMs
    }
    return HandOverResult.Refused(HANDOVER_EXPIRED)
}

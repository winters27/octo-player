package app.winters.octo.subsonic

import java.security.MessageDigest
import java.security.SecureRandom

// How the app proves who it is to the server.
enum class AuthMode {
    // md5(password + salt) with a new salt each request. The password never
    // travels. Every Subsonic server takes this.
    Token,

    // The password itself, hex encoded. For servers that check passwords
    // against a directory and so cannot check a token, and for old servers.
    LegacyPassword,

    // A key made on the server, sent in place of a username and password.
    // Only servers that list the apiKeyAuthentication extension take it.
    ApiKey,
}

// Who is signing in, and the secret for the chosen way of proving it: the
// password, or for AuthMode.ApiKey the key. With a key the username is only
// for showing, and may be empty until the server names it.
class Credentials(
    val username: String,
    private val secret: String,
    val mode: AuthMode = AuthMode.Token,
) {
    fun sign(salt: String): String = md5Hex(secret + salt)

    // The secret itself, only for sealing it into a hand-over.
    internal fun handOverSecret(): String = secret

    // The query parameters that prove who is asking, fresh for each request.
    fun authParams(): List<Pair<String, String>> = when (mode) {
        AuthMode.Token -> {
            val salt = newSalt()
            listOf("u" to username, "t" to sign(salt), "s" to salt)
        }
        AuthMode.LegacyPassword -> listOf("u" to username, "p" to legacyPassword(secret))
        AuthMode.ApiKey -> listOf("apiKey" to secret)
    }

    // The same sign-in under the name the server gave it.
    fun named(name: String) = Credentials(name, secret, mode)

    // Never print the secret, even by accident in a log.
    override fun toString() = "Credentials(username=$username, mode=$mode)"
}

// The legacy form of a password: "enc:" and its UTF-8 bytes in hex, so odd
// characters survive the address.
fun legacyPassword(password: String): String = "enc:" + password.toByteArray(Charsets.UTF_8).toHex()

private val random = SecureRandom()

internal fun newSalt(): String = ByteArray(8).also(random::nextBytes).toHex()

internal fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).toHex()

// Lower-case hex, two digits a byte. By table rather than String.format,
// which cost about 10 microseconds a signature: queueing a 20,000 song
// library signs every song's address, 0.27 s on the window's thread.
internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val byte = this[i].toInt() and 0xFF
        out[i * 2] = HexDigits[byte ushr 4]
        out[i * 2 + 1] = HexDigits[byte and 0x0F]
    }
    return String(out)
}

private val HexDigits = "0123456789abcdef".toCharArray()

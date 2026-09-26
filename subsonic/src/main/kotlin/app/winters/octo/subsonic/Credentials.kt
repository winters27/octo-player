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

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

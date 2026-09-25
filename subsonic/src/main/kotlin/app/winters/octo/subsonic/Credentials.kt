package app.winters.octo.subsonic

import java.security.MessageDigest
import java.security.SecureRandom

// Subsonic token login: the password never travels, only
// md5(password + salt) with a new salt each request.
class Credentials(val username: String, private val password: String) {
    fun sign(salt: String): String = md5Hex(password + salt)

    // Never print the password, even by accident in a log.
    override fun toString() = "Credentials(username=$username)"
}

private val random = SecureRandom()

internal fun newSalt(): String = ByteArray(8).also(random::nextBytes).toHex()

internal fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).toHex()

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

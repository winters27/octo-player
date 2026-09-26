package app.winters.octo.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

// Keeps the server password encrypted with a key that never leaves the
// phone's keystore. If the key is gone (a restored backup on a new phone)
// open returns null and the user signs in again. Other secrets use a vault
// with their own key, so forgetting one leaves the rest.
@Singleton
class CredentialVault(private val alias: String) {
    @Inject constructor() : this(ALIAS)

    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    fun open(stored: String): String? = runCatching {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }.getOrNull()

    fun forget() {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun key(): SecretKey =
        (keyStore.getKey(alias, null) as SecretKey?) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "octo.server.password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

package app.winters.octo.desktop.secrets

import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.currentOs

// The system's password store, for the server password: Windows Credential
// Manager, the macOS Keychain, or the Secret Service on Linux (GNOME
// Keyring, KWallet). Secrets are filed under the app's name and an account
// name; nothing secret is ever written to the app's own files.
interface SecretStore {
    // What the store is called, for the settings page.
    val label: String

    // Whether secrets outlive the app. False for the stand-in used when the
    // system has no store.
    val lasting: Boolean get() = true

    fun read(account: String): String?

    // Throws SecretStoreException when the store refuses.
    fun write(account: String, secret: String)

    fun delete(account: String)

    companion object {
        // The name every secret is filed under.
        const val SERVICE = "Octo"

        // The store for this system, or a stand-in that keeps secrets only
        // while the app runs when the system has none (a Linux box with no
        // keyring, say). Never a file.
        fun forSystem(os: DesktopOs = currentOs()): SecretStore =
            try {
                when (os) {
                    DesktopOs.Windows -> WindowsCredentials()
                    DesktopOs.Mac -> MacKeychain()
                    DesktopOs.Linux -> LinuxSecretService()
                }
            } catch (e: Throwable) {
                SessionOnlySecrets(reason = e.message ?: e.javaClass.simpleName)
            }
    }
}

class SecretStoreException(message: String) : Exception(message)

// The account a server's password is filed under: who signs in, where.
fun secretAccount(username: String, address: String): String = "$username@$address"

// Keeps secrets in memory only, for this run of the app. Used when the
// system has no password store, and in tests.
class SessionOnlySecrets(val reason: String? = null) : SecretStore {
    private val secrets = HashMap<String, String>()

    override val label: String = "Kept only while Octo is open"
    override val lasting: Boolean = false

    @Synchronized override fun read(account: String): String? = secrets[account]

    @Synchronized override fun write(account: String, secret: String) {
        secrets[account] = secret
    }

    @Synchronized override fun delete(account: String) {
        secrets.remove(account)
    }
}

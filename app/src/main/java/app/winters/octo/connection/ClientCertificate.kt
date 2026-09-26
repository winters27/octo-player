package app.winters.octo.connection

import android.content.Context
import android.security.KeyChain
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

// The client certificate chosen for the server, and the hosts it may be
// shown to. The name is the one the phone's certificate store knows it by.
class ClientCertChoice(val alias: String, val hosts: Set<String>) {
    override fun toString() = "ClientCertChoice(alias=${mask(alias)}, hosts=$hosts)"
}

// Hands the chosen client certificate to the server when it asks for one,
// and to no other host. The key stays in the phone's certificate store;
// the app only gets to use it, after the user picked it.
class ClientCertKeyManager(
    private val context: Context,
    private val choice: () -> ClientCertChoice?,
) : X509ExtendedKeyManager() {
    // Reading the store is slow, so what was read is kept by name.
    private val chains = ConcurrentHashMap<String, Array<X509Certificate>>()
    private val keys = ConcurrentHashMap<String, PrivateKey>()

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        aliasFor((socket as? SSLSocket)?.handshakeSession?.peerHost)

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        aliasFor(engine?.peerHost)

    // Only for the server's own hosts. With no host to go by, nothing is shown.
    private fun aliasFor(host: String?): String? {
        val current = choice() ?: return null
        return current.alias.takeIf { host != null && host.lowercase() in current.hosts }
    }

    // Both run on the connection's thread, never the main one, as the store requires.
    override fun getCertificateChain(alias: String): Array<X509Certificate>? =
        chains[alias] ?: runCatching { KeyChain.getCertificateChain(context, alias) }.getOrNull()?.also { chains[alias] = it }

    override fun getPrivateKey(alias: String): PrivateKey? =
        keys[alias] ?: runCatching { KeyChain.getPrivateKey(context, alias) }.getOrNull()?.also { keys[alias] = it }

    // Forgets what was read, for when the choice changes.
    fun forget() {
        chains.clear()
        keys.clear()
    }

    // The app never acts as a server, and never lists the store.
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}

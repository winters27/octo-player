package app.winters.octo.connection

import android.content.Context
import android.security.KeyChain
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

// The client certificate chosen for the server, and the hosts it may be
// shown to. The name is the one the phone's certificate store knows it by.
class ClientCertChoice(val alias: String, val hosts: Set<String>) {
    override fun toString() = "ClientCertChoice(alias=${mask(alias)}, hosts=$hosts)"
}

// What a key manager had for a socket when the server on it asked for a
// certificate: the alias the socket's host had then, offered or turned down
// for its kind of key, or none. A socket with a mark was asked.
internal class SocketMark(val alias: String?)

// Whether a server that takes this kind of key takes this key.
private fun fitsKey(type: String, key: PrivateKey): Boolean = type.equals(key.algorithm, ignoreCase = true)

// Hands the chosen client certificate to the server when it asks for one,
// to no other host, and only when the server takes its kind of key. The key
// stays in the phone's certificate store; the app only gets to use it,
// after the user picked it. Every socket it is asked about goes into
// `marks` with the alias its host had, so a connection made for another
// certificate can be told apart later.
internal class ClientCertKeyManager(
    context: Context,
    private val choice: () -> ClientCertChoice?,
    private val marks: MutableMap<Socket, SocketMark> = Collections.synchronizedMap(WeakHashMap()),
    // How the phone's certificate store gives a key and a chain by name.
    private val keys: (String) -> PrivateKey? = { KeyChain.getPrivateKey(context, it) },
    private val chains: (String) -> Array<X509Certificate>? = { KeyChain.getCertificateChain(context, it) },
) : X509ExtendedKeyManager() {
    // Reading the store is slow, so what was read is kept by name.
    private val readChains = ConcurrentHashMap<String, Array<X509Certificate>>()
    private val readKeys = ConcurrentHashMap<String, PrivateKey>()

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        aliasFor((socket as? SSLSocket)?.handshakeSession?.peerHost, keyType, socket)

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        aliasFor(engine?.peerHost, keyType, null)

    // Only for the server's own hosts, and only when one of the kinds of key
    // the server takes is this key's; a key the store cannot give declines.
    // With no host to go by, nothing is shown. The socket, when there is
    // one, is noted with the alias its host had, offered or not.
    internal fun aliasFor(host: String?, keyType: Array<out String>?, socket: Socket?): String? {
        val current = choice()
        val had = if (current != null && host != null && host.lowercase() in current.hosts) current.alias else null
        if (socket != null) marks[socket] = SocketMark(had)
        if (had == null) return null
        val key = getPrivateKey(had) ?: return null
        return had.takeIf { keyType.orEmpty().any { type -> fitsKey(type, key) } }
    }

    // Both run on the connection's thread, never the main one, as the store requires.
    override fun getCertificateChain(alias: String): Array<X509Certificate>? =
        readChains[alias] ?: runCatching { chains(alias) }.getOrNull()?.also { readChains[alias] = it }

    override fun getPrivateKey(alias: String): PrivateKey? =
        readKeys[alias] ?: runCatching { keys(alias) }.getOrNull()?.also { readKeys[alias] = it }

    // The app never acts as a server, and never lists the store.
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}

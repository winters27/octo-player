package app.winters.octo.desktop.server

import app.winters.octo.connection.fingerprint
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.Protocol
import java.net.InetAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// A pretend Subsonic server over https that asks for a client certificate:
// required, wanted, or not at all. It writes down, for every request it
// answers, the leaf of the client certificate the request's connection was
// made with (null for none). Its own certificate is the test servers',
// which tlsAccounts trusts. `schemes` limits the signature schemes it asks
// a client certificate to sign with.
class ClientAuthServer(
    auth: Auth = Auth.Required,
    at: InetAddress = InetAddress.getByName("127.0.0.1"),
    context: SSLContext = CertFixtures.serverContext(),
    schemes: Array<String>? = null,
    http2: Boolean = false,
) : AutoCloseable {
    enum class Auth { None, Wanted, Required }

    private val server = MockWebServer()
    private val answers = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    val leaves = CopyOnWriteArrayList<String?>()
    val calls = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += request
                leaves += (request.handshake?.peerCertificates?.firstOrNull() as? X509Certificate)?.fingerprint()
                val endpoint = request.url.pathSegments.lastOrNull().orEmpty()
                return answers[endpoint]?.invoke(request) ?: ok()
            }
        }
        val factory = context.socketFactory
        server.useHttps(if (schemes == null) factory else SchemesFactory(factory, schemes))
        if (http2) server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        when (auth) {
            Auth.None -> server.noClientAuth()
            Auth.Wanted -> server.requestClientAuth()
            Auth.Required -> server.requireClientAuth()
        }
        server.start(at, 0)
    }

    val port: Int get() = server.port

    // The server under `host`, which must lead to where it listens.
    fun url(host: String = "127.0.0.1"): HttpUrl = HttpUrl.Builder().scheme("https").host(host).port(server.port).build()

    val address: String get() = url().toString()

    // The leaf the last request came with.
    val lastLeaf: String? get() = leaves.lastOrNull()

    fun answer(endpoint: String, rule: (RecordedRequest) -> MockResponse) {
        answers[endpoint] = rule
    }

    override fun close() = server.close()

    companion object {
        fun ok(payload: String = "", type: String = "octo"): MockResponse {
            val extra = if (payload.isEmpty()) "" else ",$payload"
            return MockResponse.Builder()
                .body("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"$type","serverVersion":"0.58.0","openSubsonic":true$extra}}""")
                .build()
        }

        fun body(text: String, code: Int = 200): MockResponse = MockResponse.Builder().code(code).body(text).build()
    }
}

// Asks client certificates for these signature schemes alone.
private class SchemesFactory(private val delegate: SSLSocketFactory, private val schemes: Array<String>) : SSLSocketFactory() {
    private fun tuned(socket: Socket): Socket = socket.also { made ->
        (made as? SSLSocket)?.let { it.sslParameters = it.sslParameters.apply { signatureSchemes = schemes } }
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket = tuned(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String?, port: Int): Socket = tuned(delegate.createSocket(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = tuned(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket = tuned(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        tuned(delegate.createSocket(address, port, localAddress, localPort))
}

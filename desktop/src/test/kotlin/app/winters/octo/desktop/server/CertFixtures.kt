package app.winters.octo.desktop.server

import app.winters.octo.connection.fingerprint
import app.winters.octo.connection.pinKey
import app.winters.octo.desktop.secrets.SecretStore
import app.winters.octo.desktop.settings.HeldDisk
import app.winters.octo.desktop.settings.SettingsOpen
import app.winters.octo.desktop.settings.SettingsStore
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.io.File
import java.net.InetAddress
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// The files fixtures/gateway/certs/make-certs.sh makes, and what the tests
// build from them.
object CertFixtures {
    const val PASSWORD = "octo-test"

    // Inside every certificate's hundred years, after them, and before them.
    val NOW: Instant get() = Instant.now()
    val AFTER: Instant = Instant.parse("2200-01-01T00:00:00Z")
    val BEFORE: Instant = Instant.parse("2000-01-01T00:00:00Z")

    // fixtures/gateway/certs, found from the working folder upward.
    val folder: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "fixtures/gateway/certs") }
            .firstOrNull(File::isDirectory)
            ?: error("fixtures/gateway/certs not found above ${File("").absolutePath}")
    }

    fun file(name: String): File = File(folder, name)

    // A copy in `dir`, so a test can move, delete or deny it.
    fun copy(name: String, dir: File, named: String = name): File = File(dir, named).also { file(name).copyTo(it, overwrite = true) }

    // The leaf fingerprint of a file's key entry: `alias`, else the first.
    fun leaf(name: String, password: String = PASSWORD, alias: String? = null): String {
        val store = KeyStore.getInstance("PKCS12").apply { file(name).inputStream().use { load(it, password.toCharArray()) } }
        val entry = alias ?: store.aliases().toList().first(store::isKeyEntry)
        return (store.getCertificate(entry) as X509Certificate).fingerprint()
    }

    // Which client certificates a test server takes: those whose chain
    // reaches the test root, any at all, or none (it trusts only its own
    // certificate, which no client has).
    enum class Clients { Root, Any, None }

    // The test servers' own side: their certificate for localhost,
    // 127.0.0.1 and ::1, and the client certificates they take.
    fun serverContext(clients: Clients = Clients.Root): SSLContext {
        val keys = KeyStore.getInstance("PKCS12").apply { file("server.p12").inputStream().use { load(it, PASSWORD.toCharArray()) } }
        val keyFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASSWORD.toCharArray()) }
        val anchor = when (clients) {
            Clients.None -> keys.getCertificate("server")
            else -> file("ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        }
        val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("anchor", anchor)
        }
        val trust = if (clients == Clients.Any) {
            arrayOf<TrustManager>(AnyClient)
        } else {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }.trustManagers
        }
        return SSLContext.getInstance("TLS").apply { init(keyFactory.keyManagers, trust, null) }
    }

    val serverFingerprint: String by lazy { leaf("server.p12", alias = "server") }

    // The app trusts the test servers' certificate at the names they are
    // reached by, as the trust question would have it.
    fun trustTestServers(settings: SettingsStore) {
        val pins = listOf("127.0.0.1", "localhost", "::1").associate { pinKey(it) to serverFingerprint }
        settings.update { it.copy(trustedCertificates = it.trustedCertificates + pins) }
    }

    @Suppress("CustomX509TrustManager")
    private object AnyClient : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

// Every name the tests use leads to the loopback address the servers listen
// on; music.test is a name the servers' certificate does not hold.
val LOOPBACK: Dns = Dns { host ->
    if (host == "localhost" || host == "music.test") listOf(InetAddress.getByName("127.0.0.1")) else Dns.SYSTEM.lookup(host)
}

// An Accounts over https as the app makes one, and the shared client it
// makes its connections with: the test servers trusted at their own names,
// a base for every check (a TLS version, the names, an event listener, any
// other setting), and certificate files read through `read`.
internal fun tlsParts(
    settings: SettingsStore,
    secrets: SecretStore,
    tls: TlsVersion? = null,
    dns: Dns = LOOPBACK,
    events: (() -> EventListener)? = null,
    read: (Path) -> ByteArray = ::readCapped,
    boundMs: Long = 3_000,
    clock: () -> Instant = Instant::now,
    tune: (OkHttpClient.Builder) -> Unit = {},
): Pair<Accounts, OkHttpClient> {
    CertFixtures.trustTestServers(settings)
    val base = {
        OkHttpClient.Builder().dns(dns).apply {
            if (tls != null) connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(tls).build(), ConnectionSpec.CLEARTEXT))
            if (events != null) eventListenerFactory(EventListener.Factory { events() })
            tune(this)
        }
    }
    val security = ServerSecurity(settings)
    val http = security.install(base()).build()
    return Accounts(settings, secrets, http, security, base, readCertificateBytes = read, clock = clock, certificateReadMs = boundMs) to http
}

internal fun tlsAccounts(
    settings: SettingsStore,
    secrets: SecretStore,
    tls: TlsVersion? = null,
    dns: Dns = LOOPBACK,
    events: (() -> EventListener)? = null,
    read: (Path) -> ByteArray = ::readCapped,
    boundMs: Long = 3_000,
    clock: () -> Instant = Instant::now,
): Accounts = tlsParts(settings, secrets, tls, dns, events, read, boundMs, clock).first

// A start on a settings file as it is now, through a disk of its own that
// refuses nothing: each change is written at once, with no writer thread
// and no exit write, so it leaves nothing to write after the test.
internal fun startOn(file: File): SettingsStore = (SettingsStore.openOn(HeldDisk(), file) as SettingsOpen.Opened).store

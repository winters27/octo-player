package app.winters.octo.desktop.server

import app.winters.octo.connection.fingerprint
import app.winters.octo.connection.platformTrustManager
import app.winters.octo.connection.trustManagerFor
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

// The authorities the server's connections trust on a computer: the Java
// runtime's own and the operating system's (the Windows root store, the
// macOS keychains, the Linux system bundle), so a private authority
// installed in the system (a homelab step-ca, a company's) is trusted like
// any other. The audio engine already checks with the system's own trust.
// Whatever can't be read is left out; with nothing from the system, the
// runtime's trust alone. Read once, when first needed (systemTrust).
val systemTrust: X509TrustManager by lazy { systemTrustManager() }

fun systemTrustManager(os: String = System.getProperty("os.name").orEmpty()): X509TrustManager {
    val runtime = platformTrustManager()
    val system = systemAuthorities(os)
    if (system.isEmpty()) return runtime
    return trustManagerFor(authorityStore(runtime.acceptedIssuers.asList() + system))
}

// A key store trusting each of these authorities once.
internal fun authorityStore(authorities: List<X509Certificate>): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
    load(null, null)
    authorities.distinctBy { it.fingerprint() }.forEachIndexed { index, authority -> setCertificateEntry("authority-$index", authority) }
}

// The authorities the operating system trusts.
internal fun systemAuthorities(os: String): List<X509Certificate> {
    val name = os.lowercase()
    return when {
        name.startsWith("windows") -> fromKeyStores("Windows-ROOT", "Windows-ROOT-LOCALMACHINE")
        name.startsWith("mac") -> fromKeyStores("KeychainStore", "KeychainStore-ROOT")
        else -> LINUX_BUNDLES.asSequence().map(::File).firstOrNull { it.canRead() }?.let { authoritiesInPem(it.readText()) }.orEmpty()
    }
}

// Where Linux systems keep the bundle of authorities they trust: Debian,
// Ubuntu, Arch and the Flatpak runtimes; Fedora and RHEL; openSUSE; Alpine.
private val LINUX_BUNDLES = listOf(
    "/etc/ssl/certs/ca-certificates.crt",
    "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
    "/etc/pki/tls/certs/ca-bundle.crt",
    "/etc/ssl/ca-bundle.pem",
    "/etc/ssl/cert.pem",
)

// Every certificate in the system key stores of these names that exist here.
private fun fromKeyStores(vararg names: String): List<X509Certificate> = names.flatMap { name ->
    runCatching {
        val store = KeyStore.getInstance(name).apply { load(null, null) }
        store.aliases().toList().mapNotNull { store.getCertificate(it) as? X509Certificate }
    }.getOrDefault(emptyList())
}

private val PEM_BLOCK = Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+?)-----END CERTIFICATE-----")

// The certificates in a PEM bundle. Comments between them (Fedora's
// bundle names each one) and a block that can't be read are skipped.
internal fun authoritiesInPem(text: String): List<X509Certificate> {
    val factory = CertificateFactory.getInstance("X.509")
    return PEM_BLOCK.findAll(text).mapNotNull { block ->
        runCatching {
            val der = Base64.getMimeDecoder().decode(block.groupValues[1])
            factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.getOrNull()
    }.toList()
}

package app.winters.octo.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.connection.ConnectionChooser
import app.winters.octo.connection.ConnectionSecurity
import app.winters.octo.connection.MemoryPrefs
import app.winters.octo.connection.ServerClients
import app.winters.octo.connection.testDevices
import app.winters.octo.connection.testSecurity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

// The sessions as the app makes them, for tests: the phone's keystore stood
// in for by keys kept in memory (Robolectric has none), the kept servers in
// memory, and the app's own client, security, checks and address chooser.

// The phone's keystore, for this test on: AES keys made as the vault asks
// for them and kept in memory under their alias. Each call starts with none.
internal fun installTestKeystore() {
    Security.removeProvider(PHONE_KEYSTORE)
    Security.addProvider(MemoryKeystore())
}

// The sessions over `security` and the app's own client `http`, once they
// have read the kept servers. `vault` must be made after installTestKeystore.
internal fun testSessions(
    store: SessionStore = SessionStore(MemoryPrefs()),
    vault: CredentialVault = CredentialVault(),
    security: ConnectionSecurity = testSecurity(),
    http: OkHttpClient = security.install(OkHttpClient.Builder()).build(),
): SessionRepository {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val clients = ServerClients(context, testDevices())
    val sessions = SessionRepository(
        store,
        vault,
        ConnectionCheck(clients, OctoProbe()),
        http,
        security,
        ConnectionChooser(context, security),
        clients,
        SessionHandoff(),
    )
    runBlocking { sessions.state.first { it !is SessionState.Loading } }
    return sessions
}

private const val PHONE_KEYSTORE = "AndroidKeyStore"

private class MemoryKeystore : Provider(PHONE_KEYSTORE, 1.0, "Keys kept in memory, for tests") {
    private val made = ConcurrentHashMap<String, Key>()

    init {
        putService(Made("KeyStore", PHONE_KEYSTORE) { Store(made) })
        putService(Made("KeyGenerator", "AES") { Generator(made) })
    }

    private inner class Made(type: String, algorithm: String, private val make: () -> Any) :
        Provider.Service(this@MemoryKeystore, type, algorithm, "unused", null, null) {
        override fun newInstance(constructorParameter: Any?): Any = make()
    }
}

private class Store(private val keys: MutableMap<String, Key>) : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]

    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

    override fun engineGetCertificate(alias: String): Certificate? = null

    override fun engineGetCreationDate(alias: String): Date? = if (alias in keys) Date(0) else null

    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
        keys[alias] = key
    }

    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) {
        throw UnsupportedOperationException("Only keys made here are kept")
    }

    override fun engineSetCertificateEntry(alias: String, cert: Certificate) {
        throw UnsupportedOperationException("No certificates are kept")
    }

    override fun engineDeleteEntry(alias: String) {
        keys.remove(alias)
    }

    override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())

    override fun engineContainsAlias(alias: String): Boolean = alias in keys

    override fun engineSize(): Int = keys.size

    override fun engineIsKeyEntry(alias: String): Boolean = alias in keys

    override fun engineIsCertificateEntry(alias: String): Boolean = false

    override fun engineGetCertificateAlias(cert: Certificate): String? = null

    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}

private class Generator(private val keys: MutableMap<String, Key>) : KeyGeneratorSpi() {
    private var alias: String? = null
    private var bits = 256

    override fun engineInit(random: SecureRandom?) = Unit

    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        val spec = params as KeyGenParameterSpec
        alias = spec.keystoreAlias
        if (spec.keySize > 0) bits = spec.keySize
    }

    override fun engineInit(keysize: Int, random: SecureRandom?) {
        bits = keysize
    }

    override fun engineGenerateKey(): SecretKey {
        val key = KeyGenerator.getInstance("AES").apply { init(bits) }.generateKey()
        alias?.let { keys[it] = key }
        return key
    }
}

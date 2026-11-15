package app.winters.octo.desktop.server

import app.winters.octo.audio.ClientCertificate
import app.winters.octo.audio.clientIdentityProblem
import app.winters.octo.connection.SignInWords
import app.winters.octo.connection.fingerprint
import app.winters.octo.connection.mask
import app.winters.octo.connection.sha256Fingerprint
import app.winters.octo.desktop.audio.AudioEngine
import app.winters.octo.desktop.settings.CertFile
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SavedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import app.winters.octo.audio.Origin as EngineOrigin

// The largest certificate file Octo reads: a PKCS #12 file holds a key and
// a few certificates, far under this.
const val CERT_FILE_CAP = 1 shl 20

// How long reading a certificate file may take before Octo says the drive
// is out of reach.
const val CERT_READ_MS = 3_000L

// Where a certificate may be offered: a host as HttpUrl names it (an IPv6
// one without brackets), lowercased, and its port.
data class TlsOrigin(val host: String, val port: Int) {
    companion object {
        fun of(url: HttpUrl) = TlsOrigin(url.host.lowercase(), url.port)
    }
}

// A server's https addresses, as origins: where its certificate may go.
// Plain http never carries one.
fun certificateOrigins(main: HttpUrl?, home: HttpUrl?): Set<TlsOrigin> =
    listOfNotNull(main, home).filter(HttpUrl::isHttps).mapTo(LinkedHashSet(), TlsOrigin::of)

// Whether a server's certificate can go anywhere from these addresses: it
// has one, and one of them is https. False counts as having none: nothing
// is read for it.
fun sendsCertificate(server: SavedServer, main: HttpUrl?, home: HttpUrl?): Boolean =
    server.certificate != null && (main?.isHttps == true || home?.isHttps == true)

// The name a certificate file shows as.
fun certificateName(path: String): String = File(path).name

// The person's PKCS #12 file, opened: the key and chain of the client
// certificate, the same key as the audio engine takes it, and the file's
// name. The key never prints.
class LoadedCertificate(val name: String, val key: PrivateKey, val chain: List<X509Certificate>, val engineKey: ByteArray) {
    val leaf: X509Certificate get() = chain.first()

    val notAfter: Instant get() = leaf.notAfter.toInstant()

    val fingerprint: String = chain.first().fingerprint()

    // Every certificate of the chain in one hash: a file picked again with
    // a fuller chain and the same leaf is another identity.
    val chainHash: String = sha256Fingerprint(chain.joinToString(":") { it.fingerprint() }.toByteArray(Charsets.UTF_8))

    override fun toString() = "LoadedCertificate(name=$name, fingerprint=$fingerprint)"
}

// A file chosen on the form and opened with its password.
class PickedCertificate(val path: String, val password: String, val loaded: LoadedCertificate) {
    override fun toString() = "PickedCertificate(path=$path, password=${mask(password)}, loaded=$loaded)"
}

// What a form asks a keep to do with the client certificate. Keep and
// Remove name the server whose certificate the form showed: Keep leaves it
// as it is (and takes it to a server the keep makes for the same origin),
// Remove takes it off that server only. Use is a file picked now, with its
// password. None asks for nothing.
sealed interface CertificateDraft {
    data object None : CertificateDraft

    data class Keep(val serverId: String) : CertificateDraft

    data class Remove(val serverId: String) : CertificateDraft

    data class Use(val path: String, val password: String) : CertificateDraft {
        override fun toString() = "Use(path=$path, password=${mask(password)})"
    }
}

// The certificate a request sends: none, a kept server's, or a file picked
// now.
sealed interface CertificateChoice {
    data object None : CertificateChoice

    data class Saved(val serverId: String, val file: CertFile) : CertificateChoice

    data class Picked(val path: String, val password: String) : CertificateChoice {
        override fun toString() = "Picked(path=$path, password=${mask(password)})"
    }
}

// What the form's certificate row shows for a request: nothing, a file the
// record keeps that this request cannot send (no https address), or the
// one it sends, which the row reads for its date.
sealed interface CertificateShown {
    data object None : CertificateShown

    data class HttpsOnly(val name: String) : CertificateShown

    data class Sends(val choice: CertificateChoice) : CertificateShown
}

// The item a server's certificate keeps in the system's store: the file's
// path and its password together, so a store that ends up out of step with
// the settings asks again instead of opening another file.
@Serializable
data class CertSecret(val path: String, val password: String) {
    override fun toString() = "CertSecret(path=$path, password=${mask(password)})"
}

// How loading a certificate went. Unreadable never changes or deletes
// anything; `again` is true when only choosing it again helps (the item
// gone or naming another file, a wrong password, a file out of date or no
// longer usable), false when the reason passes (a drive not connected, no
// permission, a locked store).
sealed interface CertificateLoad {
    class Found(val certificate: LoadedCertificate) : CertificateLoad {
        override fun toString() = "Found($certificate)"
    }

    class Unreadable(val words: String, val again: Boolean) : CertificateLoad {
        override fun toString() = "Unreadable($words)"
    }
}

// The certificate a try resolved to and how loading it went (nothing is
// loaded for None), which the form's row shows once the try is over.
class CertificateTry(val choice: CertificateChoice, val load: CertificateLoad?) {
    val certificate: LoadedCertificate? get() = (load as? CertificateLoad.Found)?.certificate

    override fun toString() = "CertificateTry(choice=$choice, load=$load)"
}

// Opening a file: the certificate, or why not. `wrongPassword` asks for
// the password.
sealed interface CertificateOpened {
    class Found(val certificate: LoadedCertificate) : CertificateOpened

    class Refused(val words: String, val wrongPassword: Boolean = false) : CertificateOpened
}

// The desktop's own certificate words.
object CertificateWords {
    fun notFound(name: String) = "Octo can't find $name. If it's on a drive that isn't connected, connect it and sign in again; otherwise choose it again."

    fun notAllowed(name: String) = "Octo isn't allowed to read $name. Choose a copy in a folder you can open."

    fun needsPassword(name: String) = "Octo needs the password for $name. Choose it again."

    fun storeUnread(store: String, reason: String) = "Octo couldn't read the certificate's password from $store ($reason). Unlock it and try again."

    fun writeRefused(reason: String) = "Octo couldn't save the certificate's password ($reason), so you'll need to choose the certificate again next time."

    const val SESSION_ONLY = "This computer has no password store, so Octo will ask you to choose the certificate again each time it opens."

    fun deleteRefused(store: String, reason: String) = "Octo couldn't remove the certificate's password from $store ($reason). It's no longer used."

    const val UNSAVED_KEEP = "Octo couldn't save its settings yet. If it still can't when Octo closes, you'll be asked to choose the certificate again."

    fun unsavedRemove(name: String) =
        "Octo couldn't save its settings yet. If it still can't when Octo closes, Octo will ask about the certificate the next time you open $name; choose Remove there."

    fun unsavedAdd(name: String) = "Octo couldn't save its settings yet. If it still can't when Octo closes, $name won't be in your list next time."

    const val CARRY_UNREAD = "Octo couldn't read the certificate's password, so nothing changed. Try again."

    const val KEEP_FILE = "Octo reads this file each time it connects, so keep it where it is."

    const val HTTPS_ONLY = "Octo sends it only to https addresses."
}

// Opens a PKCS #12 file with its password: the key entry that may sign in
// a client, its chain, and the key as the audio engine takes it. `problem`
// is the engine's own check of that key (engineKeyProblem).
fun openCertificate(
    bytes: ByteArray,
    password: String,
    name: String,
    now: Instant,
    problem: (chain: List<ByteArray>, key: ByteArray) -> String?,
): CertificateOpened {
    if (bytes.size > CERT_FILE_CAP) return CertificateOpened.Refused(SignInWords.CERT_TOO_BIG)
    val secret = password.toCharArray()
    val store = KeyStore.getInstance("PKCS12")
    try {
        store.load(ByteArrayInputStream(bytes), secret)
    } catch (e: IOException) {
        return if (e.cause is UnrecoverableKeyException) {
            CertificateOpened.Refused(SignInWords.CERT_WRONG_PASSWORD, wrongPassword = true)
        } else {
            CertificateOpened.Refused(SignInWords.CERT_NOT_A_FILE)
        }
    } catch (e: GeneralSecurityException) {
        return CertificateOpened.Refused(SignInWords.CERT_NOT_A_FILE)
    }
    val entries = store.aliases().toList().filter(store::isKeyEntry).mapNotNull { alias ->
        val chain = store.getCertificateChain(alias)?.map { it as? X509Certificate ?: return@mapNotNull null }
        if (chain.isNullOrEmpty()) null else alias to chain
    }
    if (entries.isEmpty()) return CertificateOpened.Refused(SignInWords.CERT_NO_KEY)
    val forClients = entries.filter { (_, chain) -> signsInClients(chain.first()) }
    if (forClients.isEmpty()) return CertificateOpened.Refused(SignInWords.CERT_NOT_FOR_CLIENTS)
    val (alias, chain) = forClients.firstOrNull { (_, chain) -> inDate(chain.first(), now) } ?: forClients.first()
    val leaf = chain.first()
    if (now.isBefore(leaf.notBefore.toInstant())) return CertificateOpened.Refused(SignInWords.CERT_NOT_YET_VALID)
    if (now.isAfter(leaf.notAfter.toInstant())) return CertificateOpened.Refused(SignInWords.CERT_EXPIRED)
    val key = try {
        store.getKey(alias, secret) as? PrivateKey
    } catch (e: UnrecoverableKeyException) {
        return CertificateOpened.Refused(SignInWords.CERT_WRONG_PASSWORD, wrongPassword = true)
    } catch (e: GeneralSecurityException) {
        return CertificateOpened.Refused(SignInWords.CERT_NOT_A_FILE)
    } ?: return CertificateOpened.Refused(SignInWords.CERT_NO_KEY)
    // A key the engine's form cannot be made from gets the same words as one
    // the engine itself turns down.
    val engineKey = engineKeyOf(key, leaf) ?: return CertificateOpened.Refused(SignInWords.CERT_KEY_KIND)
    if (problem(chain.map(X509Certificate::getEncoded), engineKey) != null) return CertificateOpened.Refused(SignInWords.CERT_KEY_KIND)
    return CertificateOpened.Found(LoadedCertificate(name, key, chain, engineKey))
}

// Why the audio engine could not sign with this chain (DER, the client's own
// first) and PKCS#8 DER key, or null when it can: its library's own answer,
// which needs no engine open (a P-521 key, RSA under 2048 bits and RSA-PSS
// open in the JDK, yet the engine cannot sign with them). Where the library
// will not load, Octo plays through the silent player, which signs nothing,
// so every key is taken.
fun engineKeyProblem(chain: List<ByteArray>, key: ByteArray): String? = try {
    clientIdentityProblem(chain, key)
} catch (e: LinkageError) {
    null
}

// Client sign-in, and any use at all, as extended key usages name them.
private const val CLIENT_AUTH = "1.3.6.1.5.5.7.3.2"
private const val ANY_USAGE = "2.5.29.37.0"

// Whether a certificate may sign in a client: no extended key usage at all,
// or one that names client sign-in.
private fun signsInClients(leaf: X509Certificate): Boolean {
    val usage = try {
        leaf.extendedKeyUsage
    } catch (e: CertificateParsingException) {
        return false
    } ?: return true
    return CLIENT_AUTH in usage || ANY_USAGE in usage
}

private fun inDate(leaf: X509Certificate, now: Instant): Boolean =
    !now.isBefore(leaf.notBefore.toInstant()) && !now.isAfter(leaf.notAfter.toInstant())

// A file over the cap: never read past it.
class CertificateTooBig : IOException("over $CERT_FILE_CAP bytes")

// A certificate file's bytes, read at most one byte past the cap, so a
// bigger file is refused without reading it whole.
fun readCapped(path: Path): ByteArray {
    if (!Files.isRegularFile(path)) throw NoSuchFileException(path.toString())
    val bytes = Files.newInputStream(path).use { it.readNBytes(CERT_FILE_CAP + 1) }
    if (bytes.size > CERT_FILE_CAP) throw CertificateTooBig()
    return bytes
}

// A stored path as a Path, or null when it is not one.
fun certificatePath(path: String): Path? = try {
    Path.of(path)
} catch (e: InvalidPathException) {
    null
}

// What a read that failed says, for the file named `name`.
fun unreadableRead(e: Throwable, name: String): CertificateLoad.Unreadable = when (e) {
    is AccessDeniedException -> CertificateLoad.Unreadable(CertificateWords.notAllowed(name), again = false)
    is CertificateTooBig -> CertificateLoad.Unreadable(SignInWords.CERT_TOO_BIG, again = true)
    else -> CertificateLoad.Unreadable(CertificateWords.notFound(name), again = false)
}

// Reads certificate files on threads of their own, one read per path at a
// time: a read stuck on a drive that hangs is waited for again by later
// loads, never started twice, and every wait is bounded.
class CertificateReads(private val read: (Path) -> ByteArray, private val boundMs: Long) {
    private val pool = Executors.newCachedThreadPool { work -> Thread(work, "octo-cert-read").apply { isDaemon = true } }
    private val inFlight = ConcurrentHashMap<Path, CompletableFuture<ByteArray>>()
    private val joined = AtomicInteger()

    // The paths being read now, for tests.
    val reading: Set<Path> get() = inFlight.keys.toSet()

    // How many callers joined a read under way, for tests.
    val joins: Int get() = joined.get()

    // The read of `path` under way, or one started now. Only the caller
    // whose future went in starts the read, which completes that future and
    // then lets go of it.
    private fun started(path: Path): CompletableFuture<ByteArray> {
        val mine = CompletableFuture<ByteArray>()
        val running = inFlight.putIfAbsent(path, mine)
        if (running != null) {
            joined.incrementAndGet()
            return running
        }
        pool.execute {
            try {
                mine.complete(read(path))
            } catch (e: Throwable) {
                mine.completeExceptionally(e)
            } finally {
                inFlight.remove(path, mine)
            }
        }
        return mine
    }

    // The file's bytes, or null when the read took longer than the bound.
    // It waits on a copy, so giving up never ends the read for anyone else,
    // and on the IO threads, so the bound is real time. Throws what the read
    // threw.
    suspend fun bytes(path: Path): ByteArray? = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(boundMs) { started(path).copy().await() }
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
    }

    // The same, waiting on this thread.
    fun bytesNow(path: Path): ByteArray? = try {
        started(path).get(boundMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        null
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }
}

// The certificate the audio engine offers, since it fetches songs by
// itself: the latest one under a lock, handed to the engine as soon as one
// is attached and again whenever it changes. A refusal leaves the engine
// with none.
class EngineIdentity {
    private var engine: AudioEngine? = null
    private var latest: ClientCertificate? = null
    private var latestKey: String? = null
    private var givenKey: String? = null
    private var given = false

    // The engine to tell, before its player is made, so the certificate in
    // use is there before any song is asked for.
    @Synchronized
    fun attach(engine: AudioEngine) {
        this.engine = engine
        given = false
        forward()
    }

    // The certificate in use and the origins it goes to, or none.
    @Synchronized
    fun use(certificate: LoadedCertificate?, origins: Set<TlsOrigin>) {
        latest = certificate?.let { ClientCertificate(origins = origins.map(::engineOrigin), chain = it.chain.map(X509Certificate::getEncoded), key = it.engineKey) }
        latestKey = certificate?.let { it.chainHash + "@" + origins.joinToString(",") }
        forward()
    }

    private fun forward() {
        val engine = engine ?: return
        if (given && givenKey == latestKey) return
        given = true
        givenKey = latestKey
        try {
            engine.setClientCertificate(latest)
        } catch (e: Exception) {
            // The engine refused it and offers none now.
            givenKey = null
        }
    }

    override fun toString() = "EngineIdentity(given=${givenKey != null})"
}

private fun engineOrigin(origin: TlsOrigin) =
    EngineOrigin(scheme = "https", host = if (':' in origin.host) "[${origin.host}]" else origin.host, port = origin.port.toUShort())

// Where a server's certificate item is filed in the system's store: under
// its id, and for a run with a folder of its own, a hash of that folder too
// (its real path, lowercased on Windows), so such a run never reads or
// replaces the items of the Octo in use.
fun certificateAccountFor(profile: File?, os: DesktopOs): (String) -> String {
    val scope = profile?.let { folder ->
        runCatching { folder.mkdirs() }
        val real = runCatching { folder.toPath().toRealPath().toString() }.getOrElse { folder.absolutePath }
        val named = if (os == DesktopOs.Windows) real.lowercase() else real
        "#" + sha256Fingerprint(named.toByteArray(Charsets.UTF_8)).take(16)
    }.orEmpty()
    return { id -> "certificate@$id$scope" }
}

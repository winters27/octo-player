package app.winters.octo.connection

import android.net.http.X509TrustManagerExtensions
import okhttp3.OkHttpClient
import javax.net.ssl.X509TrustManager

// The chain the phone itself trusted for a host. Android fills in a
// certificate a server left out from ones it saw on earlier connections,
// so what the server sent is not the whole story. Made on first use: unit
// tests on the JVM never get this far.
fun phoneBuiltChain(platform: X509TrustManager): BuiltChain {
    val extensions by lazy { X509TrustManagerExtensions(platform) }
    return BuiltChain { chain, authType, host -> extensions.checkServerTrusted(chain, authType, host).orEmpty() }
}

// The phone's own trust with distrusted authorities turned down, for a
// client that must never use a server's pins, headers or client
// certificate.
fun phoneTrust(): X509TrustManager {
    val platform = platformTrustManager()
    return DistrustingTrustManager(platform, phoneBuiltChain(platform))
}

// Sets up a client builder to use the phone's own trust as above. Every
// client the app makes for itself goes through this or ConnectionSecurity
// (PhoneTrustTest checks).
fun OkHttpClient.Builder.phoneTls(trust: X509TrustManager = phoneTrust()): OkHttpClient.Builder = distrustingTls(trust)

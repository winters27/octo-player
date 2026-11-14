package app.winters.octo.connection

import android.content.Context
import app.winters.octo.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// A client of its own for a kept server that is not the one in use, or for
// checking a sign-in before it is kept: its headers, trusted certificates
// and client certificate, a sign-in page cookie jar that starts empty, and
// a short time to answer, without touching the shared client, which stays
// with the server in use. Each is made for one check and let go (letGo)
// once that check is done.
@Singleton
class ServerClients @Inject constructor(
    @ApplicationContext private val context: Context,
    private val devices: DeviceIds,
) : JoinClients {
    fun forServer(main: HttpUrl, settings: ConnectionSettings, seconds: Long): OkHttpClient = clientFor(main, settings, seconds).first

    // The same, with its security too, which keeps a certificate the client
    // refused, to ask about (joining a family pairs before signing in).
    override fun forJoin(main: HttpUrl, settings: ConnectionSettings, seconds: Long): Pair<OkHttpClient, ConnectionSecurity> = clientFor(main, settings, seconds)

    private fun clientFor(main: HttpUrl, settings: ConnectionSettings, seconds: Long): Pair<OkHttpClient, ConnectionSecurity> {
        val security = ConnectionSecurity(context, devices).apply { configure(main, settings) }
        return OkHttpClient.Builder()
            .connectTimeout(seconds, TimeUnit.SECONDS)
            .callTimeout(seconds, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "Octo/${BuildConfig.VERSION_NAME} (Android)").build())
            }
            .let(security::install)
            .build() to security
    }
}

// Lets a client made for one check go once the check is done: its threads
// stop and its connections close. Only for a client from ServerClients (or
// one built from it); the shared client lives on. Off the main thread,
// since closing an HTTPS connection says goodbye over the network, and
// whatever happened to the check meanwhile.
suspend fun OkHttpClient.letGo() {
    withContext(NonCancellable + Dispatchers.IO) {
        dispatcher.executorService.shutdown()
        connectionPool.evictAll()
    }
}

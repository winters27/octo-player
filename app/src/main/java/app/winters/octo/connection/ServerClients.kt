package app.winters.octo.connection

import android.content.Context
import app.winters.octo.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// A client of its own for a kept server that is not the one in use: its
// headers, trusted certificates and client certificate, and a short time to
// answer, without touching the shared client, which stays with the server
// in use.
@Singleton
class ServerClients @Inject constructor(@ApplicationContext private val context: Context) {
    fun forServer(main: HttpUrl, settings: ConnectionSettings, seconds: Long): OkHttpClient {
        val security = ConnectionSecurity(context).apply { configure(main, settings) }
        return OkHttpClient.Builder()
            .connectTimeout(seconds, TimeUnit.SECONDS)
            .callTimeout(seconds, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "Octo/${BuildConfig.VERSION_NAME} (Android)").build())
            }
            .let(security::install)
            .build()
    }
}

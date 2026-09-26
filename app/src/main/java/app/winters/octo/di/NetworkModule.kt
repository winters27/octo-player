package app.winters.octo.di

import app.winters.octo.BuildConfig
import app.winters.octo.connection.ConnectionSecurity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    // One client for the API, covers and streams. No logging interceptor:
    // every request address carries a login token. The server's extra
    // headers, trusted certificates and client certificate come from the
    // connection security, which follows the signed-in server.
    @Provides
    @Singleton
    fun okHttp(security: ConnectionSecurity): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Octo/${BuildConfig.VERSION_NAME} (Android)")
                    .build(),
            )
        }
        .let(security::install)
        .build()
}

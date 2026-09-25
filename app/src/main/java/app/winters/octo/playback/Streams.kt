package app.winters.octo.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.extractor.DefaultExtractorsFactory
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.player.PlayerSettings
import app.winters.octo.player.StreamPrefs
import app.winters.octo.subsonic.SubsonicClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// How a server song sits in the queue: its id on the server and what its
// file is, never a signed address. The address is signed as the song loads.
private const val STREAM_SCHEME = "octo-stream"

// How long to wait for the saved sign-in to come back as the app starts.
private const val SESSION_WAIT_MS = 3_000L

// Why a server song cannot load: no connection, or no server signed in.
class StreamUnavailable(message: String) : IOException(message)

// Streams songs from the signed-in server. It knows the connection and the
// streaming settings, and signs each request as a deck opens it, so a new
// address is made for every load and seek and none is ever kept.
@OptIn(UnstableApi::class)
@Singleton
class Streams @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
    private val settings: PlayerSettings,
    private val http: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    // The settings as they are now, for choices made while a song loads.
    private val current: StateFlow<StreamPrefs> =
        settings.streamPrefs.stateIn(scope, SharingStarted.Eagerly, StreamPrefs())

    suspend fun prefs(): StreamPrefs = settings.streamPrefs.first()

    // The signed-in server, once the saved sign-in is back, or null.
    suspend fun client(): SubsonicClient? {
        val state = withTimeoutOrNull(SESSION_WAIT_MS) { sessions.state.first { it !is SessionState.Loading } }
        return (state as? SessionState.SignedIn)?.session?.client
    }

    // Whether the phone has a connection a stream could use.
    fun online(): Boolean =
        capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    // Mobile data is any connection that is not Wi-Fi or a cable.
    private fun onMobileData(): Boolean {
        val caps = capabilities() ?: return false
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun capabilities(): NetworkCapabilities? =
        connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)

    // The stream size for the connection the phone is on.
    private fun quality(prefs: StreamPrefs): StreamQuality = if (onMobileData()) prefs.mobile else prefs.wifi

    // Where the queue keeps a server copy.
    fun uriFor(copy: SourceTrackEntity): String =
        Uri.Builder()
            .scheme(STREAM_SCHEME)
            .authority("song")
            .appendPath(copy.nativeId)
            .apply {
                copy.mimeType?.let { appendQueryParameter("mime", it) }
                bitrateOf(copy)?.let { appendQueryParameter("bitrate", "$it") }
            }
            .build()
            .toString()

    // What the player will receive for a server copy on this connection.
    fun mimeTypeFor(copy: SourceTrackEntity, prefs: StreamPrefs): String? =
        streamRequest(copy.mimeType, bitrateOf(copy), quality(prefs)).mimeType(copy.mimeType)

    // What a deck loads songs with: phone files as they are, server songs
    // signed as they open. One for each deck.
    fun mediaSourceFactory(): MediaSource.Factory {
        val upstream = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http))
        val sources = ResolvingDataSource.Factory(upstream) { spec ->
            if (spec.uri.scheme == STREAM_SCHEME) spec.withUri(sign(spec.uri)) else spec
        }
        // A made-on-the-way MP3 has no seek table, so seek by its steady bitrate.
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        return DefaultMediaSourceFactory(sources, extractors).setLoadErrorHandlingPolicy(Retries())
    }

    // Runs on the loading thread, each time a stream opens.
    private fun sign(uri: Uri): Uri {
        if (!online()) throw StreamUnavailable("No connection")
        val client = (sessions.state.value as? SessionState.SignedIn)?.session?.client
            ?: throw StreamUnavailable("No server signed in")
        val id = uri.lastPathSegment ?: throw StreamUnavailable("No song id")
        val request = streamRequest(uri.getQueryParameter("mime"), uri.getQueryParameter("bitrate")?.toIntOrNull(), quality(current.value))
        return client.url("stream", mapOf("id" to id) + request.params).toString().toUri()
    }

    // A failed load is tried again as usual, except with no connection or
    // no server: then the song fails at once and is skipped, not waited on.
    private inner class Retries : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long =
            if (loadErrorInfo.exception is StreamUnavailable || !online()) {
                C.TIME_UNSET
            } else {
                super.getRetryDelayMsFor(loadErrorInfo)
            }
    }
}

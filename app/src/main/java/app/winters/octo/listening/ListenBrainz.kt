package app.winters.octo.listening

import app.winters.octo.catalog.isFind
import app.winters.octo.connection.platformTrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.tls.OkHostnameVerifier
import java.io.IOException
import javax.net.ssl.SSLContext

// Where plays go. The token is only ever sent here, over HTTPS.
val LISTENBRAINZ_API: HttpUrl = "https://api.listenbrainz.org/".toHttpUrl()

// Where the listener finds their token.
const val LISTENBRAINZ_TOKEN_PAGE = "https://listenbrainz.org/settings/"

// ListenBrainz takes at most this many plays in one request.
const val MAX_LISTENS_PER_REQUEST = 1_000

// How many plays may wait. Past that the oldest are let go.
const val MAX_QUEUED_LISTENS = 5_000

// One play as ListenBrainz hears it. `listenedAt` is when it started, in
// seconds; a length of zero and a missing track number are left out.
@Serializable
data class Listen(
    val listenedAt: Long,
    val track: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val trackNumber: Int? = null,
)

// What was played, as far as the app knows it, and whether the signed-in
// server hears the play too.
data class PlayedSong(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNumber: Int?,
    val reachesServer: Boolean,
) {
    // Null when the song has no title or artist, which ListenBrainz needs.
    fun listenAt(startedAtMs: Long): Listen? {
        if (title.isBlank() || artist.isBlank()) return null
        return Listen(
            listenedAt = startedAtMs / 1000,
            track = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            durationMs = durationMs.coerceAtLeast(0),
            trackNumber = trackNumber?.takeIf { it > 0 },
        )
    }
}

// Which plays are sent: all of them, or only those the server never hears,
// so a server that passes its own plays on does not count them twice.
enum class SendPlays { All, PhoneOnly }

// A play reaches the server when the song is a server song found online or
// has a copy on the server, the same rule the server's play queue follows.
fun playReachesServer(trackId: String, copySources: List<String>): Boolean =
    isFind(trackId) || copySources.any { it.startsWith("server:") }

fun shouldSend(mode: SendPlays, reachesServer: Boolean): Boolean = mode == SendPlays.All || !reachesServer

// Oldest first, each play once, and never more than the cap.
fun List<Listen>.plusListen(listen: Listen): List<Listen> =
    (this + listen).distinctBy { Triple(it.listenedAt, it.track, it.artist) }
        .sortedBy { it.listenedAt }
        .takeLast(MAX_QUEUED_LISTENS)

// The plays the next request carries: the oldest, up to the limit.
fun nextBatch(queue: List<Listen>): List<Listen> = queue.take(MAX_LISTENS_PER_REQUEST)

// "single" for one play, "import" for several.
fun listenType(count: Int): String = if (count == 1) "single" else "import"

// How long to wait after this many failures in a row: 30 seconds, doubling
// each time, and never more than six hours.
fun retryDelayMs(failures: Int): Long {
    if (failures <= 0) return 0
    val steps = (failures - 1).coerceAtMost(20)
    return (30_000L shl steps).coerceAtMost(6 * 60 * 60 * 1000L)
}

// The request body for these plays. Now playing carries no time.
fun listensJson(type: String, listens: List<Listen>, clientVersion: String): JsonObject = buildJsonObject {
    put("listen_type", type)
    put(
        "payload",
        buildJsonArray {
            listens.forEach { listen ->
                add(
                    buildJsonObject {
                        if (type != "playing_now") put("listened_at", listen.listenedAt)
                        put("track_metadata", trackMetadata(listen, clientVersion))
                    },
                )
            }
        },
    )
}

private fun trackMetadata(listen: Listen, clientVersion: String): JsonObject = buildJsonObject {
    put("artist_name", listen.artist)
    put("track_name", listen.track)
    if (listen.album.isNotBlank()) put("release_name", listen.album)
    put(
        "additional_info",
        buildJsonObject {
            put("media_player", "Octo")
            put("media_player_version", clientVersion)
            put("submission_client", "Octo")
            put("submission_client_version", clientVersion)
            if (listen.durationMs > 0) put("duration_ms", listen.durationMs)
            listen.trackNumber?.let { put("tracknumber", it.toString()) }
        },
    )
}

// ListenBrainz asks for no more than one call a second, and says in each
// answer how many calls are left and when the count starts over.
class RateLimits(private val clock: () -> Long = System::currentTimeMillis) {
    private var lastCallAt = Long.MIN_VALUE / 2
    private var blockedUntil = 0L

    // How long to wait before the next call.
    @Synchronized
    fun waitMs(): Long {
        val now = clock()
        return maxOf(lastCallAt + MIN_GAP_MS - now, blockedUntil - now, 0)
    }

    // How long until the current window ends, when no calls are left in it.
    @Synchronized
    fun blockedMs(): Long = (blockedUntil - clock()).coerceAtLeast(0)

    @Synchronized
    fun called() {
        lastCallAt = clock()
    }

    // What an answer said. Nothing left means waiting for the window to end.
    @Synchronized
    fun heard(remaining: Int?, resetInSeconds: Long?) {
        if (remaining != null && remaining <= 0 && resetInSeconds != null) blockedUntil = clock() + resetInSeconds * 1000
    }

    // A 429: nothing more until the window ends.
    @Synchronized
    fun refused(retryInMs: Long) {
        blockedUntil = clock() + retryInMs
    }

    private companion object {
        const val MIN_GAP_MS = 1_000L
    }
}

sealed interface TokenCheck {
    data class Valid(val user: String) : TokenCheck
    data object Invalid : TokenCheck
    data object Unreachable : TokenCheck
}

sealed interface SubmitResult {
    data object Sent : SubmitResult

    // The token is no longer accepted.
    data object Unauthorized : SubmitResult

    // ListenBrainz refused what was sent; sending it again would not help.
    data object Rejected : SubmitResult

    // Too many calls; try again after this long.
    data class RateLimited(val retryInMs: Long) : SubmitResult

    // Could not reach ListenBrainz, or it had a problem of its own.
    data object Failed : SubmitResult
}

// Talks to ListenBrainz. Tokens are passed in for each call and never kept,
// printed or put in an address.
class ListenBrainzApi(
    private val http: OkHttpClient,
    private val base: HttpUrl = LISTENBRAINZ_API,
    private val limits: RateLimits = RateLimits(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Calls take turns at the rate check, so two never go out together.
    private val turn = Mutex()

    // Whether the token is good, and whose it is.
    suspend fun validate(token: String): TokenCheck {
        if (!looksLikeToken(token)) return TokenCheck.Invalid
        val request = Request.Builder().url(base.resolve("1/validate-token")!!).header(AUTH, "Token $token").get().build()
        val answer = call(request) ?: return TokenCheck.Unreachable
        return when {
            answer.code == 200 -> {
                val body = runCatching { json.parseToJsonElement(answer.body).jsonObject }.getOrNull()
                val valid = body?.get("valid")?.jsonPrimitive?.booleanOrNull == true
                val user = body?.get("user_name")?.jsonPrimitive?.contentOrNull
                if (valid && !user.isNullOrBlank()) TokenCheck.Valid(user) else TokenCheck.Invalid
            }
            answer.code == 400 || answer.code == 401 -> TokenCheck.Invalid
            else -> TokenCheck.Unreachable
        }
    }

    // Sends finished plays: one as "single", more as "import".
    suspend fun submit(token: String, listens: List<Listen>, clientVersion: String): SubmitResult {
        require(listens.size in 1..MAX_LISTENS_PER_REQUEST)
        return post(token, listensJson(listenType(listens.size), listens, clientVersion))
    }

    // Says what is playing now. Skipped rather than held back when no calls
    // are left, since it is soon out of date anyway.
    suspend fun playingNow(token: String, listen: Listen, clientVersion: String): SubmitResult {
        limits.blockedMs().takeIf { it > 0 }?.let { return SubmitResult.RateLimited(it) }
        return post(token, listensJson("playing_now", listOf(listen), clientVersion))
    }

    private suspend fun post(token: String, body: JsonObject): SubmitResult {
        if (!looksLikeToken(token)) return SubmitResult.Unauthorized
        val request = Request.Builder()
            .url(base.resolve("1/submit-listens")!!)
            .header(AUTH, "Token $token")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val answer = call(request) ?: return SubmitResult.Failed
        return when (answer.code) {
            in 200..299 -> SubmitResult.Sent
            401 -> SubmitResult.Unauthorized
            400, 413 -> SubmitResult.Rejected
            429 -> SubmitResult.RateLimited(answer.retryInMs)
            else -> SubmitResult.Failed
        }
    }

    private class Answer(val code: Int, val body: String, val retryInMs: Long)

    // Makes one call once the rate allows it; null when it could not be made.
    private suspend fun call(request: Request): Answer? {
        turn.withLock {
            limits.waitMs().takeIf { it > 0 }?.let { delay(it) }
            limits.called()
        }
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { response ->
                    val resetIn = response.header("X-RateLimit-Reset-In")?.trim()?.toLongOrNull()
                    limits.heard(response.header("X-RateLimit-Remaining")?.trim()?.toIntOrNull(), resetIn)
                    val retryIn = (resetIn ?: DEFAULT_RETRY_S).coerceIn(1, MAX_RETRY_S) * 1000
                    if (response.code == 429) limits.refused(retryIn)
                    Answer(response.code, response.body.string(), retryIn)
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private companion object {
        const val AUTH = "Authorization"
        const val DEFAULT_RETRY_S = 10L
        const val MAX_RETRY_S = 60L * 60
        val JSON_TYPE = "application/json".toMediaType()
    }
}

// A token is letters, digits and dashes. Anything else is not one, and
// would not be a valid header either.
fun looksLikeToken(token: String): Boolean = token.length in 8..128 && token.all { (it.isLetterOrDigit() && it.code < 128) || it == '-' }

// The client for ListenBrainz, made from the shared one so it reuses its
// connections, but without anything the shared one adds for the signed-in
// server: its extra headers, its trusted certificates and its client
// certificate. It only ever talks to `allowed`, and never follows a
// redirect anywhere else.
fun listenBrainzClient(shared: OkHttpClient, userAgent: String, allowed: HttpUrl = LISTENBRAINZ_API): OkHttpClient {
    val trust = platformTrustManager()
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    return shared.newBuilder()
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .addInterceptor(OnlyListenBrainz(allowed, userAgent))
        .sslSocketFactory(tls.socketFactory, trust)
        .hostnameVerifier(OkHostnameVerifier)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

// Refuses any request that is not for ListenBrainz, so the token cannot go
// anywhere else.
private class OnlyListenBrainz(private val allowed: HttpUrl, private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (url.scheme != allowed.scheme || !url.host.equals(allowed.host, ignoreCase = true) || url.port != allowed.port) {
            throw IOException("Not a ListenBrainz address")
        }
        return chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
    }
}

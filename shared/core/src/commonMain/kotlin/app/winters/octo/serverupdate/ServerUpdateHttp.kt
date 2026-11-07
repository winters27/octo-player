package app.winters.octo.serverupdate

import app.winters.octo.connection.GatewayWords
import app.winters.octo.subsonic.SubsonicClient
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.answerFacts
import app.winters.octo.subsonic.gateway.isHtml
import app.winters.octo.subsonic.gateway.recognizeGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

// Reading and starting a server update.
interface ServerUpdateCalls {
    suspend fun read(): UpdateRead

    suspend fun start(tag: String): UpdateStart
}

// Where Octo's admin pages answer, and how a call to them is signed. The
// server's own extra headers (a proxy's access token, a sign-in page's
// password) come from the security installed on the client, which keeps
// them to the server's addresses on every hop.
class AdminPlace(
    val base: HttpUrl,
    val sign: (HttpUrl) -> HttpUrl,
)

// The server's own addresses as the place: the one calls go to now. The
// sign-in is only ever added for the server's own addresses, never for an
// address that merely answers like it.
fun ownAddresses(client: SubsonicClient): AdminPlace {
    val trusted = setOf(rootOf(client.baseUrl), rootOf(client.primaryUrl))
    return AdminPlace(rootOf(client.baseUrl), { url -> if (rootOf(url) in trusted) client.signed(url) else url })
}

private fun rootOf(url: HttpUrl): HttpUrl = url.newBuilder().encodedPath("/").query(null).fragment(null).build()

// GET and POST /api/admin/update. Every call has a short limit of its own,
// so a connection left open to a server that restarted is dropped rather
// than waited on.
class ServerUpdateHttp(
    http: OkHttpClient,
    private val where: suspend () -> AdminPlace?,
) : ServerUpdateCalls {
    private val quick = http.newBuilder().callTimeout(UPDATE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()

    override suspend fun read(): UpdateRead {
        val place = runCatching { where() }.getOrNull() ?: return UpdateRead.NoAnswer
        return withContext(Dispatchers.IO) {
            try {
                quick.newCall(request(place).get().build()).execute().use { response ->
                    when {
                        // A sign-in page in front of the server is not the
                        // server saying no.
                        response.signInPage() != null -> UpdateRead.NoAnswer
                        response.code in NOT_OFFERED -> UpdateRead.NotOffered
                        !response.isSuccessful -> UpdateRead.NoAnswer
                        else -> parseServerUpdate(response.body.string())?.let { UpdateRead.Answered(it) } ?: UpdateRead.NoAnswer
                    }
                }
            } catch (e: IOException) {
                UpdateRead.NoAnswer
            }
        }
    }

    override suspend fun start(tag: String): UpdateStart {
        val place = runCatching { where() }.getOrNull() ?: return UpdateStart.Refused(NO_ANSWER)
        val body = JsonObject(mapOf("tag" to JsonPrimitive(tag))).toString().toRequestBody("application/json".toMediaType())
        return withContext(Dispatchers.IO) {
            try {
                quick.newCall(request(place).header("X-Octo-Admin", "1").post(body).build()).execute().use { response ->
                    val page = response.signInPage()
                    val answer = if (page == null) parseStartAnswer(response.body.string()) else null
                    when {
                        page != null -> UpdateStart.Refused(GatewayWords.failure(page.first, page.second))
                        response.isSuccessful -> UpdateStart.Started(answer?.id, answer?.tag ?: tag)
                        response.code == 409 -> UpdateStart.Refused(answer?.error?.takeIf(String::isNotBlank) ?: "Octo would not start the update just now.")
                        response.code in NOT_OFFERED -> UpdateStart.Refused("Only a Navidrome admin can update the server.")
                        else -> UpdateStart.Refused(answer?.error?.takeIf(String::isNotBlank) ?: NO_ANSWER)
                    }
                }
            } catch (e: IOException) {
                UpdateStart.Refused(NO_ANSWER)
            }
        }
    }

    private fun request(place: AdminPlace): Request.Builder {
        val url = place.sign(place.base.newBuilder().encodedPath("/api/admin/update").query(null).build())
        return Request.Builder().url(url)
    }

    // The sign-in page in front of the server that gave this answer, and
    // whether the app's sign-in for it went along; null when the server
    // itself answered.
    private fun Response.signInPage(): Pair<Gateway, Boolean>? {
        if (isSuccessful && !isHtml(header("Content-Type"))) return null
        val facts = answerFacts()
        return recognizeGateway(facts)?.let { it to facts.loginSent }
    }

    private companion object {
        // A listener's account, or a server that has no such page or does
        // not take the app's sign-in there.
        val NOT_OFFERED = setOf(401, 403, 404)

        const val NO_ANSWER = "Octo did not answer. Try again in a moment."
    }
}

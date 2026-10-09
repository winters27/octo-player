package app.winters.octo.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// Who a family page sign-in is.
@Serializable
data class FamilyWebUser(val username: String = "", val role: String = "")

// A member just added by a manager, and the link that invites them.
@Serializable
data class FamilyMemberAdded(val member: FamilyMember = FamilyMember(), val inviteLink: String = "")

@Serializable
private data class InviteAnswer(val inviteLink: String = "")

// The presets a manager picks for a new member.
enum class FamilyPreset { CoAdmin, Member, Listener, Kid }

// The family page's own calls (/api/family), as Octo's family web page
// makes them: JSON both ways, and a sign-in kept in a cookie that lives
// only as long as this object. Used to join with an invite, and for what
// a manager does that the Subsonic calls do not cover.
class FamilyWeb(private val server: HttpUrl, http: OkHttpClient) {
    private val cookies = object : CookieJar {
        private val kept = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            kept.removeAll { old -> cookies.any { it.name == old.name } }
            kept += cookies
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = kept.filter { it.matches(url) }
    }

    private val http = http.newBuilder().cookieJar(cookies).build()

    // Signs in as the account `client` signs in with, when its secret is a
    // password the family page takes. False when it is not (an API key, or
    // a device's own secret the page refuses), so the caller asks for the
    // account password.
    suspend fun signInAs(client: SubsonicClient): Boolean {
        val secret = client.passwordSecret() ?: return false
        return try {
            signIn(client.username, secret)
            true
        } catch (e: SubsonicException.WrongCredentials) {
            false
        }
    }

    // Signs in to the family page with an account password.
    suspend fun signIn(username: String, password: String): FamilyWebUser =
        call("POST", "auth", mapOf("username" to username.trim(), "password" to password), FamilyWebUser.serializer())

    // Accepts an invite: the new member's name and the password they chose.
    // Signs this page in as them, and answers their username.
    suspend fun join(token: String, password: String, displayName: String): String =
        call("POST", "join", mapOf("token" to token, "password" to password, "displayName" to displayName.trim()), FamilyWebUser.serializer()).username

    // A new device for the member signed in: a pair code for an Octo app,
    // or an app password for any other app.
    suspend fun addMyDevice(name: String, kind: FamilyDeviceKind): FamilyDeviceAdded =
        call("POST", "me/devices", mapOf("name" to name.trim(), "kind" to kind.name), FamilyDeviceAdded.serializer())

    // A manager adds a member, and gets the link that invites them.
    suspend fun addMember(username: String, displayName: String, preset: FamilyPreset): FamilyMemberAdded =
        call(
            "POST",
            "members",
            mapOf("username" to username.trim(), "displayName" to displayName.trim(), "preset" to preset.name),
            FamilyMemberAdded.serializer(),
        )

    // A manager's fresh sign-in link for a member: it sets up a device of
    // theirs, as an invite does.
    suspend fun memberLink(username: String): String =
        call("POST", "members/${encodeComponent(username)}/invite", null, InviteAnswer.serializer()).inviteLink

    private suspend fun <T> call(method: String, path: String, body: Map<String, String>?, serializer: kotlinx.serialization.KSerializer<T>): T {
        val url = server.newBuilder().addPathSegment("api").addPathSegment("family").addEncodedPathSegments(path).build()
        val payload = (body?.let { json.encodeToString(JsonObject.serializer(), JsonObject(it.mapValues { (_, v) -> kotlinx.serialization.json.JsonPrimitive(v) })) } ?: "{}")
            .toRequestBody(JSON)
        val request = Request.Builder().url(url).header("X-Octo-Family", "1").header("Accept", "application/json").method(method, payload).build()
        val text = try {
            val response = http.newCall(request).await()
            withContext(Dispatchers.IO) {
                response.use {
                    val answer = it.body.string()
                    if (!it.isSuccessful) throw failure(it.code, answer)
                    answer
                }
            }
        } catch (e: IOException) {
            throw SubsonicException.Unreachable(e)
        }
        return json.decodeFromString(serializer, text.ifBlank { "{}" })
    }

    // The server's own words when it gives them, else plain ones.
    private fun failure(status: Int, body: String): SubsonicException {
        val said = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?.let { it["message"] ?: it["error"] }?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        return when (status) {
            401, 403 -> SubsonicException.WrongCredentials(said ?: "That username or password did not work.")
            404 -> SubsonicException.NotSubsonic(said ?: "This server has no family page. Update Octo on the server.", status)
            410 -> SubsonicException.NotFound(said ?: "That invite has been used or has expired. Ask for a new one.")
            else -> SubsonicException.Server(status, said ?: "The server said no (HTTP $status).")
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}

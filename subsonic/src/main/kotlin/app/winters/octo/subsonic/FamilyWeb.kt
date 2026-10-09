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

// The presets a manager picks for a new member.
enum class FamilyPreset { CoAdmin, Member, Listener, Kid }

// The family page's own calls (/api/family): JSON both ways. From an app
// signed in to the server, each call carries the same sign-in as every
// Subsonic call (`sign` adds it to the address), so no password is ever
// asked for. Without one (joining with an invite, before this device has a
// sign-in) the page's cookie, kept only as long as this object, signs in
// the new member after the invite is accepted.
class FamilyWeb(
    private val server: HttpUrl,
    http: OkHttpClient,
    private val sign: ((HttpUrl) -> HttpUrl)? = null,
) {
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

    // Accepts an invite: the new member's name and the password they chose.
    // Signs this page in as them, and answers their username.
    suspend fun join(token: String, password: String, displayName: String): String =
        call("POST", "join", mapOf("token" to token, "password" to password, "displayName" to displayName.trim()), FamilyWebUser.serializer()).username

    // A new device for the member signed in: a pair code for an Octo app,
    // or an app password for any other app.
    suspend fun addMyDevice(name: String, kind: FamilyDeviceKind): FamilyDeviceAdded =
        call("POST", "me/devices", mapOf("name" to name.trim(), "kind" to kind.name), FamilyDeviceAdded.serializer())

    // A manager's new device for a member: that member's pair code or app
    // password.
    suspend fun addMemberDevice(username: String, name: String, kind: FamilyDeviceKind): FamilyDeviceAdded =
        call("POST", "members/${encodeComponent(username)}/devices", mapOf("name" to name.trim(), "kind" to kind.name), FamilyDeviceAdded.serializer())

    // A manager adds a member, and gets the link that invites them.
    suspend fun addMember(username: String, displayName: String, preset: FamilyPreset): FamilyMemberAdded =
        call(
            "POST",
            "members",
            mapOf("username" to username.trim(), "displayName" to displayName.trim(), "preset" to preset.name),
            FamilyMemberAdded.serializer(),
        )

    private suspend fun <T> call(method: String, path: String, body: Map<String, String>?, serializer: kotlinx.serialization.KSerializer<T>): T {
        val plain = server.newBuilder().addPathSegment("api").addPathSegment("family").addEncodedPathSegments(path).build()
        val url = sign?.invoke(plain) ?: plain
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
            401, 403 -> SubsonicException.WrongCredentials(said ?: "The server did not take this sign-in for that.")
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

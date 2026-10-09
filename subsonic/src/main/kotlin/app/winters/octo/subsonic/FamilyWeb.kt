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
data class FamilyMemberAdded(
    val member: FamilyMember = FamilyMember(),
    val inviteLink: String = "",
    val links: FamilyLinkChoices? = null,
    val anywhereAvailable: Boolean = true,
    val homeOnly: Boolean = false,
) {
    override fun toString() = "FamilyMemberAdded(member=${member.username})"
}

// A fresh invite for a member who has not joined yet.
@Serializable
data class FamilyNewInvite(
    val inviteLink: String = "",
    val links: FamilyLinkChoices? = null,
    val anywhereAvailable: Boolean = true,
    val homeOnly: Boolean = false,
) {
    override fun toString() = "FamilyNewInvite(anywhereAvailable=$anywhereAvailable)"
}

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
        call("POST", "join", body("token" to token, "password" to password, "displayName" to displayName.trim()), FamilyWebUser.serializer()).username

    // A new device for the member signed in: a pair code for an Octo app,
    // or an app password for any other app. `replaces` ends an earlier
    // unused code first.
    suspend fun addMyDevice(name: String, kind: FamilyDeviceKind, replaces: String? = null): FamilyDeviceAdded =
        call("POST", "me/devices", deviceBody(name, kind, replaces), FamilyDeviceAdded.serializer())

    // A manager's new device for a member: that member's pair code or app
    // password.
    suspend fun addMemberDevice(username: String, name: String, kind: FamilyDeviceKind, replaces: String? = null): FamilyDeviceAdded =
        call("POST", "members/${encodeComponent(username)}/devices", deviceBody(name, kind, replaces), FamilyDeviceAdded.serializer())

    private fun deviceBody(name: String, kind: FamilyDeviceKind, replaces: String?) = body(
        "name" to name.trim(),
        "kind" to kind.name,
        *listOfNotNull(replaces?.takeIf(String::isNotBlank)?.let { "replaces" to it }).toTypedArray(),
    )

    private fun body(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) })

    // A manager adds a member, and gets the link that invites them. Then
    // whether they may listen away from home is set as an edit to their
    // abilities.
    suspend fun addMember(username: String, displayName: String, preset: FamilyPreset, away: Boolean = true): FamilyMemberAdded {
        val added = call(
            "POST",
            "members",
            body("username" to username.trim(), "displayName" to displayName.trim(), "preset" to preset.name),
            FamilyMemberAdded.serializer(),
        )
        val name = added.member.username.ifBlank { username.trim() }
        call(
            "PUT",
            "members/${encodeComponent(name)}",
            JsonObject(mapOf("edits" to JsonObject(mapOf("away" to kotlinx.serialization.json.JsonPrimitive(away))))),
            FamilyMember.serializer(),
        )
        return added
    }

    // A new invite link for a member, in place of the last one.
    suspend fun newInvite(username: String): FamilyNewInvite =
        call("POST", "members/${encodeComponent(username)}/invite", null, FamilyNewInvite.serializer())

    private suspend fun <T> call(method: String, path: String, body: JsonObject?, serializer: kotlinx.serialization.KSerializer<T>): T {
        val plain = server.newBuilder().addPathSegment("api").addPathSegment("family").addEncodedPathSegments(path).build()
        val url = sign?.invoke(plain) ?: plain
        val payload = (body?.let { json.encodeToString(JsonObject.serializer(), it) } ?: "{}").toRequestBody(JSON)
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

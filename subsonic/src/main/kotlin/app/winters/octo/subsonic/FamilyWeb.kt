package app.winters.octo.subsonic

import kotlinx.serialization.json.JsonPrimitive
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

// A fresh sign-up link: a new invite for a member who has not signed up
// yet, or, after a manager resets their password, one to choose a new one.
@Serializable
data class FamilyNewInvite(
    val inviteLink: String = "",
    val links: FamilyLinkChoices? = null,
    val anywhereAvailable: Boolean = true,
    val homeOnly: Boolean = false,
    val expires: String? = null,
) {
    override fun toString() = "FamilyNewInvite(anywhereAvailable=$anywhereAvailable)"
}

// A sign-in hand-over just started on this device: its token, when it
// ends, and the addresses its link can go through.
@Serializable
data class FamilySignInStart(
    val token: String = "",
    val expires: String? = null,
    val links: FamilyLinkChoices = FamilyLinkChoices(),
    val anywhereAvailable: Boolean = true,
) {
    override fun toString() = "FamilySignInStart(expires=$expires)"
}

// A device that redeemed this device's hand-over and waits for an answer.
@Serializable
data class FamilySignInPending(val id: String = "", val deviceName: String = "", val platform: String = "")

@Serializable
internal data class FamilySignInRedeemed(val id: String = "")

// How a redeemed hand-over stands, from the new device's side.
@Serializable
enum class FamilySignInState { Waiting, Allowed, Denied, Expired }

@Serializable
data class FamilySignInStatus(val state: FamilySignInState = FamilySignInState.Waiting, val box: String? = null) {
    override fun toString() = "FamilySignInStatus(state=$state)"
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

    // A manager resets a member's password: it stops working at once, and
    // the answer is a new single-use link to choose another.
    suspend fun resetMember(username: String): FamilyNewInvite =
        call("POST", "members/${encodeComponent(username)}/reset", null, FamilyNewInvite.serializer())

    // Starts handing this device's sign-in to the same person's other
    // device: a short-lived token for the QR code.
    suspend fun startSignIn(): FamilySignInStart = call("POST", "signin/start", null, FamilySignInStart.serializer())

    // The device that redeemed the token, or null while none has.
    suspend fun signInPending(token: String): FamilySignInPending? =
        callOrNull("GET", "signin/${encodeComponent(token)}/pending", null, FamilySignInPending.serializer())?.takeIf { it.id.isNotEmpty() }

    // Allows or denies the device that redeemed the token.
    suspend fun decideSignIn(token: String, allow: Boolean) {
        callOrNull("POST", "signin/${encodeComponent(token)}/decide", JsonObject(mapOf("allow" to JsonPrimitive(allow))), FamilyWebUser.serializer())
    }

    // The sealed sign-in for the allowed device; the server cannot open it.
    suspend fun putSignInBox(token: String, box: String) {
        callOrNull("POST", "signin/${encodeComponent(token)}/box", body("box" to box), FamilyWebUser.serializer())
    }

    // On the new device, with no sign-in: redeems the token from the QR code,
    // naming this device, and answers the id to wait on.
    suspend fun redeemSignIn(token: String, deviceName: String, platform: FamilyPlatform): String =
        call("POST", "signin/redeem", body("token" to token, "deviceName" to deviceName.trim(), "platform" to platform.wire), FamilySignInRedeemed.serializer()).id

    // How the redeemed hand-over stands, with the sealed sign-in once allowed.
    suspend fun signInStatus(id: String): FamilySignInStatus =
        call("GET", "signin/redeem/${encodeComponent(id)}", null, FamilySignInStatus.serializer())

    private suspend fun <T> call(method: String, path: String, body: JsonObject?, serializer: kotlinx.serialization.KSerializer<T>): T =
        callOrNull(method, path, body, serializer) ?: json.decodeFromString(serializer, "{}")

    // As `call`, but an empty answer (204) is null.
    private suspend fun <T> callOrNull(method: String, path: String, body: JsonObject?, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val plain = server.newBuilder().addPathSegment("api").addPathSegment("family").addEncodedPathSegments(path).build()
        val url = sign?.invoke(plain) ?: plain
        val payload = if (method == "GET") null else (body?.let { json.encodeToString(JsonObject.serializer(), it) } ?: "{}").toRequestBody(JSON)
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
        if (text.isBlank()) return null
        return json.decodeFromString(serializer, text)
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

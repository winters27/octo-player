package app.winters.octo.subsonic.gateway

import app.winters.octo.subsonic.origin
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// What can stand in front of a server and answer in its place: a sign-in
// gateway the apps know by name, a plain password prompt (HTTP Basic), or
// a sign-in page they do not know, a public Wi-Fi's portal among them.
enum class Gateway { Authentik, Authelia, CloudflareAccess, PasswordPrompt, SignInPage }

// The three names a gateway login uses, whatever its kind. Compared without case.
object GatewayNames {
    const val AUTHORIZATION = "Authorization"
    const val CF_ID = "CF-Access-Client-Id"
    const val CF_SECRET = "CF-Access-Client-Secret"

    private val all = listOf(AUTHORIZATION, CF_ID, CF_SECRET)

    fun isGatewayName(name: String): Boolean = all.any { it.equals(name.trim(), ignoreCase = true) }
}

// One answer on the way to the final one: each redirect hop, then the
// final answer. Addresses carry the sign-in in their query and headers
// carry cookies, so neither is ever printed.
data class AnswerHop(val url: String, val status: Int, val headers: List<Pair<String, String>>) {
    override fun toString() = "AnswerHop(url=${url.substringBefore('?')}, status=$status, headers=${headers.map { it.first }})"
}

// What an answer shows about what gave it: the address asked, every hop
// in order (the last is the final answer), the start of an HTML final body
// (at most 16 KB), and whether the app's own sign-in for a gateway went
// with the request.
data class AnswerFacts(
    val requested: String,
    val hops: List<AnswerHop>,
    val htmlBody: String?,
    val loginSent: Boolean,
) {
    override fun toString() =
        "AnswerFacts(requested=${requested.substringBefore('?')}, hops=$hops, htmlBody=${htmlBody?.length}, loginSent=$loginSent)"
}

// The gateway that gave this answer, or null when the server itself did.
// Cookie and header marks count only on an answer a gateway gives (a
// redirect, a 401 or 403, or a web page), so the server's own errors after
// a header sign-in are never taken for one, and a JSON answer from the
// server's own address never is. The first rule that matches wins.
fun recognizeGateway(facts: AnswerFacts): Gateway? {
    val last = facts.hops.lastOrNull() ?: return null
    val requested = facts.requested.toHttpUrlOrNull()
    val lastUrl = last.url.toHttpUrlOrNull()
    val lastType = mediaType(last.header("Content-Type"))
    val sameOrigin = requested != null && lastUrl != null && sameOrigin(requested, lastUrl)
    if (sameOrigin && isJsonType(lastType)) return null
    val places = facts.hops.flatMap { hop -> listOfNotNull(hop.url.toHttpUrlOrNull(), hop.location()) }
    val gatewayAnswers = facts.hops.filter(::looksLikeGateway)
    val cookies = gatewayAnswers.flatMap { hop -> hop.values("Set-Cookie").map { it.substringBefore('=').trim() } }
    val body = facts.htmlBody.orEmpty()
    return when {
        places.any(::isAuthentikPlace) ||
            cookies.any { it.startsWith("authentik_proxy") } ||
            body.contains("goauthentik", ignoreCase = true) ||
            body.contains("aria-label=\"authentik\"", ignoreCase = true) -> Gateway.Authentik
        places.any(::isCloudflarePlace) ||
            cookies.any { it == "CF_Authorization" } ||
            gatewayAnswers.any { it.header("cf-access-aud") != null || it.header("cf-access-domain") != null } -> Gateway.CloudflareAccess
        places.any(::isAutheliaPlace) || cookies.any { it == "authelia_session" } -> Gateway.Authelia
        last.status == 401 && last.values("WWW-Authenticate").any { it.trimStart().startsWith("Basic", ignoreCase = true) } -> Gateway.PasswordPrompt
        !sameOrigin && lastType == "text/html" -> Gateway.SignInPage
        facts.loginSent && (last.status == 401 || last.status == 403) && !isJsonType(lastType) -> Gateway.SignInPage
        else -> null
    }
}

// The media type of a Content-Type value, lowercased, its parameters
// dropped: "text/html; charset=utf-8" is "text/html". Null for none.
fun mediaType(contentType: String?): String? =
    contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

fun isHtml(contentType: String?): Boolean = mediaType(contentType) == "text/html"

// Whether a body reads as JSON: { or [ first, after any whitespace or BOM.
fun looksLikeJson(body: String): Boolean = body.firstOrNull { !it.isWhitespace() && it != '﻿' }.let { it == '{' || it == '[' }

fun isBlankBody(body: String): Boolean = body.isBlank()

private fun isJsonType(type: String?): Boolean = type == "application/json" || type == "text/json" || type?.endsWith("+json") == true

private fun looksLikeGateway(hop: AnswerHop): Boolean =
    hop.status in 300..399 || hop.status == 401 || hop.status == 403 || isHtml(hop.header("Content-Type"))

private fun AnswerHop.header(name: String): String? = headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

private fun AnswerHop.values(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

private fun AnswerHop.location(): HttpUrl? = header("Location")?.let { url.toHttpUrlOrNull()?.resolve(it) }

private fun isAuthentikPlace(url: HttpUrl): Boolean = "/outpost.goauthentik.io/" in url.encodedPath || url.encodedPath.startsWith("/if/flow/")

private fun isCloudflarePlace(url: HttpUrl): Boolean = url.host.endsWith(".cloudflareaccess.com") || url.encodedPath.startsWith("/cdn-cgi/access/")

private fun isAutheliaPlace(url: HttpUrl): Boolean = "rd" in url.queryParameterNames && "rm" in url.queryParameterNames

// The same server: the same origin, or the same host moved up from http to https.
private fun sameOrigin(requested: HttpUrl, answered: HttpUrl): Boolean =
    origin(requested) == origin(answered) ||
        (requested.host.equals(answered.host, ignoreCase = true) && !requested.isHttps && answered.isHttps)

package app.winters.octo.subsonic.gateway

import app.winters.octo.subsonic.NOT_LIKE_SUBSONIC
import app.winters.octo.subsonic.SubsonicException
import okhttp3.Response

// The most of an HTML final body read to tell which gateway gave it.
private const val HTML_PEEK = 16 * 1024

// What this answer shows about what gave it: every hop from the first
// request on, in order; whether the app's sign-in for a gateway went with
// the first hop as it left (its network request, after the server's
// headers were added); and the start of an HTML body. The body is peeked,
// never read, so the caller can still read it.
fun Response.answerFacts(): AnswerFacts = answerFacts(peekHtml())

internal fun Response.answerFacts(htmlBody: String?): AnswerFacts {
    val hops = generateSequence(this) { it.priorResponse }.toList().asReversed()
    val first = hops.first()
    val sent = (first.networkResponse?.request ?: first.request).headers.names()
    return AnswerFacts(
        requested = first.request.url.toString(),
        hops = hops.map { AnswerHop(it.request.url.toString(), it.code, it.headers.toList()) },
        htmlBody = htmlBody,
        loginSent = sent.any(GatewayNames::isGatewayName),
    )
}

// The error for an answer that is not a usable Subsonic or JSON answer,
// naming the gateway that gave it when one did. An error status keeps the
// words "HTTP <code> from <endpoint>", which other rules read.
fun Response.notSubsonic(endpoint: String): SubsonicException.NotSubsonic = notSubsonic(endpoint, answerFacts())

// The body of a 2xx answer that is JSON or empty (a 204, or nothing at
// all), for the caller to read as it always has. Anything else is the
// NotSubsonic error for it.
fun Response.jsonBody(endpoint: String): String {
    if (!isSuccessful) throw notSubsonic(endpoint)
    val text = body.string()
    if (isBlankBody(text) || looksLikeJson(text)) return text
    val html = if (isHtml(header("Content-Type"))) text.take(HTML_PEEK) else null
    throw notSubsonic(endpoint, answerFacts(html))
}

// The same check for an answer read as it arrives: only its start is
// peeked, so the body still reads whole afterwards. A 2xx answer that
// starts as JSON or empty passes; anything else is the NotSubsonic error
// for it. Blocks while the start arrives, so it runs off the main thread.
fun Response.checkStreamed(endpoint: String) {
    if (!isSuccessful) throw notSubsonic(endpoint)
    val start = runCatching { peekBody(HTML_PEEK.toLong()).string() }.getOrDefault("")
    if (isBlankBody(start) || looksLikeJson(start)) return
    throw notSubsonic(endpoint, answerFacts(start.takeIf { isHtml(header("Content-Type")) }))
}

private fun Response.notSubsonic(endpoint: String, facts: AnswerFacts): SubsonicException.NotSubsonic {
    val gateway = recognizeGateway(facts)
    return if (isSuccessful) {
        SubsonicException.NotSubsonic(NOT_LIKE_SUBSONIC, gateway = gateway, loginSent = facts.loginSent)
    } else {
        SubsonicException.NotSubsonic("HTTP $code from $endpoint", code, gateway, facts.loginSent)
    }
}

private fun Response.peekHtml(): String? =
    if (!isHtml(header("Content-Type"))) null else runCatching { peekBody(HTML_PEEK.toLong()).string() }.getOrNull()

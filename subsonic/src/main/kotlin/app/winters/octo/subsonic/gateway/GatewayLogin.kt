package app.winters.octo.subsonic.gateway

import java.text.Normalizer
import java.util.Base64

// How the app gets through a sign-in page in front of the server: not at
// all, a username and password sent as HTTP Basic (an Authentik app
// password, Authelia, a proxy's password), or a Cloudflare Access service
// token.
enum class GatewayKind { None, Password, CloudflareToken }

// RFC 7617: "Basic " and the base64 of the UTF-8 bytes of the username and
// password, each in NFC, joined by a colon. The caller has checked them
// already: no colon in the username, no control characters.
fun basicCredentials(username: String, password: String): String {
    val pair = Normalizer.normalize(username, Normalizer.Form.NFC) + ":" + Normalizer.normalize(password, Normalizer.Form.NFC)
    return "Basic " + Base64.getEncoder().encodeToString(pair.toByteArray(Charsets.UTF_8))
}

// The kind saved headers make up, read from their values: both Cloudflare
// names make a service token (an Authorization beside them stays a row of
// its own); else an Authorization that is HTTP Basic makes a username and
// password; anything else (a Bearer token, half a Cloudflare pair) is none.
fun savedGatewayKind(headers: List<Pair<String, String>>): GatewayKind = when {
    headers.any { it.named(GatewayNames.CF_ID) } && headers.any { it.named(GatewayNames.CF_SECRET) } -> GatewayKind.CloudflareToken
    headers.any { it.isBasic() } -> GatewayKind.Password
    else -> GatewayKind.None
}

// The names, as saved, of the headers that make up that kind: the form
// shows them as the sign-in page fields, never as rows of their own.
fun savedGatewayNames(headers: List<Pair<String, String>>, kind: GatewayKind): Set<String> = when (kind) {
    GatewayKind.None -> emptySet()
    GatewayKind.Password -> headers.filter { it.isBasic() }.mapTo(LinkedHashSet()) { it.first }
    GatewayKind.CloudflareToken ->
        headers.filter { it.named(GatewayNames.CF_ID) || it.named(GatewayNames.CF_SECRET) }.mapTo(LinkedHashSet()) { it.first }
}

private fun Pair<String, String>.named(name: String): Boolean = first.trim().equals(name, ignoreCase = true)

private fun Pair<String, String>.isBasic(): Boolean = named(GatewayNames.AUTHORIZATION) && second.startsWith("Basic ", ignoreCase = true)

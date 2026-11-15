package app.winters.octo.connection

import app.winters.octo.subsonic.SubsonicException
import app.winters.octo.subsonic.gateway.Gateway
import app.winters.octo.subsonic.gateway.GatewayKind

// Every word the phone and desktop apps say about a sign-in page in front
// of a server. The iPhone app says the same; both are checked against
// fixtures/gateway/words.json.
object GatewayWords {
    const val LABEL_SECTION = "Sign-in page in front"
    const val LABEL_CAPTION = "For Authentik, Authelia, a proxy password or Cloudflare Access"
    const val LABEL_CLIENT_ID = "Client ID"
    const val LABEL_CLIENT_SECRET = "Client Secret"
    const val JOIN_HINT = "If you don't have an account on this sign-in page, ask who invited you."
    const val SHARE_LINE = "This server has a sign-in page in front of it, so people need an account there to open this link."
    const val PLAY_FAILURE = "A sign-in page answered instead of the server."
    const val NEEDS_HTTPS = "Use https for this address. The sign-in page password would cross the internet readable."
    const val ROW_NEEDS_HTTPS = "Needs https for its sign-in page"
    const val PROBLEM_BOTH_PASSWORD = "Enter both the sign-in page username and the sign-in page password."
    const val PROBLEM_BOTH_TOKEN = "Enter both the Client ID and the Client Secret."
    const val PROBLEM_COLON = "The sign-in page username can't contain a colon."
    const val PROBLEM_CONTROL = "Remove the line break or special character from the sign-in page fields."
    const val PROBLEM_RESERVED_ROW = "That header is set by the sign-in page fields. Remove the row."

    // The line under Sign in while the sign-in page's group calls out a
    // failure: a button that brings the group back into view. A no-break
    // space keeps its last two words together, so a wrapped line never ends
    // on one word.
    const val POINTER = "Your server's sign-in page needs its own login, under Advanced."

    fun name(gateway: Gateway): String = when (gateway) {
        Gateway.Authentik -> "Authentik"
        Gateway.Authelia -> "Authelia"
        Gateway.CloudflareAccess -> "Cloudflare Access"
        Gateway.PasswordPrompt -> "A password prompt"
        Gateway.SignInPage -> "A sign-in page"
    }

    // What answered in the server's place, and what to do. `loginSent`: the
    // app's own sign-in for it went along, and it said no.
    fun failure(gateway: Gateway, loginSent: Boolean): String = if (loginSent) {
        when (gateway) {
            Gateway.Authentik -> "Authentik didn't accept that username and app password."
            Gateway.Authelia -> "Authelia didn't accept that username and password."
            Gateway.CloudflareAccess -> "Cloudflare Access didn't accept the service token. Check that a Service Auth rule allows it."
            Gateway.PasswordPrompt -> "The server's password prompt didn't accept that username and password."
            Gateway.SignInPage -> "The sign-in page in front of the server didn't accept those details."
        }
    } else {
        when (gateway) {
            Gateway.Authentik ->
                "Authentik is in front of this server, so Octo needs its own sign-in to get through. Fill in the sign-in page fields under Advanced."
            Gateway.Authelia ->
                "Authelia is in front of this server, so Octo needs its own sign-in to get through. Fill in the sign-in page fields under Advanced."
            Gateway.CloudflareAccess ->
                "Cloudflare Access is in front of this server, so Octo needs a service token to get through. Fill in the sign-in page fields under Advanced."
            Gateway.PasswordPrompt ->
                "This server asks for a username and password before Octo can reach it. Fill in the sign-in page fields under Advanced."
            Gateway.SignInPage ->
                "A sign-in page answered instead of the server. On public Wi-⁠Fi, sign in to the Wi-⁠Fi first. Otherwise fill in the sign-in page fields under Advanced."
        }
    }

    // A failure's words as the sign-in page group's own error note shows
    // them, under the fields it names: "under Advanced" there reads "above",
    // kept with "fields" so it never ends a wrapped note alone.
    fun inGroup(text: String): String = text
        .replace("Otherwise fill in the sign-in page fields under Advanced.", "Otherwise fill in the fields above.")
        .replace("Fill in the sign-in page fields under Advanced.", "Fill in the fields above.")

    // The same, short enough for a server's row in the list.
    fun row(gateway: Gateway, loginSent: Boolean): String =
        if (loginSent) "${name(gateway)} didn't accept the sign-in" else "${name(gateway)} is in front of this server"

    // How to fill in the fields for that gateway.
    fun hint(gateway: Gateway): String = when (gateway) {
        Gateway.Authentik ->
            "Use your Authentik username and an app password. Make one in Authentik under your user settings, Tokens and App passwords."
        Gateway.Authelia ->
            "Use your Authelia username and password. Authelia lets apps in only where its rule for this address is one factor."
        Gateway.CloudflareAccess ->
            "Use a service token from Cloudflare Zero Trust, and add a Service Auth rule for it to this app's Access policy."
        Gateway.PasswordPrompt -> "Use the username and password the server's proxy asks for."
        Gateway.SignInPage ->
            "If the sign-in page takes a username and password from apps, enter them here. A page that only works in a browser can't let Octo in: ask whoever runs it to let /⁠rest/ through."
    }

    // The note under the sign-in page group when a kind is picked and no
    // sign-in page was met: how to get a service token. Null when there is
    // nothing to add.
    fun tip(kind: GatewayKind): String? = if (kind == GatewayKind.CloudflareToken) hint(Gateway.CloudflareAccess) else null

    fun kindLabel(kind: GatewayKind): String = when (kind) {
        GatewayKind.None -> "None"
        GatewayKind.Password -> "Username and password"
        GatewayKind.CloudflareToken -> "Service token"
    }

    // The line under a kind in the choice: what each is for. None has none.
    fun kindHelper(kind: GatewayKind): String? = when (kind) {
        GatewayKind.None -> null
        GatewayKind.Password -> "Authentik, Authelia or a proxy password"
        GatewayKind.CloudflareToken -> "Cloudflare Access"
    }

    // The names of a kind's two field rows: Cloudflare's own for a service
    // token, the met sign-in page's own when it is one whose words are
    // known, and otherwise names that never read like the server's own
    // username and password.
    fun fieldLabels(kind: GatewayKind, detected: Gateway?): Pair<String, String> = when {
        kind == GatewayKind.CloudflareToken -> LABEL_CLIENT_ID to LABEL_CLIENT_SECRET
        detected == Gateway.Authentik -> "Authentik username" to "App password"
        detected == Gateway.Authelia -> "Authelia username" to "Authelia password"
        else -> "Sign-in page username" to "Sign-in page password"
    }

    // The words for an error a sign-in page in front of the server caused,
    // or null when none did.
    fun failureFor(error: Throwable): String? {
        val page = error as? SubsonicException.NotSubsonic ?: return null
        return page.gateway?.let { failure(it, page.loginSent) }
    }
}

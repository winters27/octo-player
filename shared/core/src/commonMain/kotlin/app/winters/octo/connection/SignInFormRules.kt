package app.winters.octo.connection

import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.gateway.GatewayKind

// The sign-in form's rules that every app's form follows, checked against
// fixtures/gateway/signin-vectors.json in Kotlin and in OctoKit.

// A sign-in page field edited: the failure is the person's to fix now, so
// the group stops calling it out.
fun gatewayEdited(state: GatewayFormState): GatewayFormState = state.copy(callout = false)

// A sign-in page kind picked.
fun kindPicked(state: GatewayFormState, kind: GatewayKind): GatewayFormState = state.copy(kind = kind, callout = false)

// The form's error written or cleared by anything else: the callout and its
// scroll end, while a key row waiting to be shown still waits.
fun calm(state: GatewayFormState): GatewayFormState = state.copy(callout = false, calloutPending = false)

// An attempt starts: nothing from the last one is called out or waits to be
// scrolled to.
fun sending(state: GatewayFormState): GatewayFormState =
    state.copy(callout = false, calloutPending = false, keyPending = false)

// The group was brought into view.
fun calloutShown(state: GatewayFormState): GatewayFormState = state.copy(calloutPending = false)

// The pointer under Sign in pressed: the group is brought into view again,
// while it calls a failure out.
fun pointTo(state: GatewayFormState): GatewayFormState = if (state.callout) state.copy(calloutPending = true) else state

// The Method changed from `oldMode` to `newMode`. Picking API key with no
// key kept for it asks for the key row to be brought into view; every other
// pick drops that ask.
fun methodPicked(state: GatewayFormState, oldMode: AuthMode, newMode: AuthMode, keepsKey: Boolean): GatewayFormState =
    state.copy(keyPending = newMode == AuthMode.ApiKey && oldMode != AuthMode.ApiKey && !keepsKey)

// The key row was brought into view.
fun keyShown(state: GatewayFormState): GatewayFormState = state.copy(keyPending = false)

// The Method from the two flags a form holds; an API key wins.
fun authModeOf(legacy: Boolean, apiKey: Boolean): AuthMode = when {
    apiKey -> AuthMode.ApiKey
    legacy -> AuthMode.LegacyPassword
    else -> AuthMode.Token
}

// The two flags for a Method: Legacy password first, API key second.
fun flagsOf(mode: AuthMode): Pair<Boolean, Boolean> = when (mode) {
    AuthMode.Token -> false to false
    AuthMode.LegacyPassword -> true to false
    AuthMode.ApiKey -> false to true
}

// The Methods to offer. Before signing in nobody knows whether the server
// takes API keys, so one may be tried; once it is known not to, API key is
// offered only while it is the one picked.
fun methodsShown(takesApiKeys: Boolean?, apiKeyOn: Boolean): List<AuthMode> =
    if (takesApiKeys != false || apiKeyOn) listOf(AuthMode.Token, AuthMode.LegacyPassword, AuthMode.ApiKey)
    else listOf(AuthMode.Token, AuthMode.LegacyPassword)

// Whether the password field may stay empty and keep the saved password: a
// form that keeps the saved secret, a password saved (for a token or legacy
// sign-in), and a Method that sends a password.
fun keepsPassword(keepsSecret: Boolean, savedMode: AuthMode?, mode: AuthMode): Boolean =
    keepsSecret && savedMode != null && savedMode != AuthMode.ApiKey && mode != AuthMode.ApiKey

// The same for the API key field: a key saved, and API key picked.
fun keepsKey(keepsSecret: Boolean, savedMode: AuthMode?, mode: AuthMode): Boolean =
    keepsSecret && savedMode == AuthMode.ApiKey && mode == AuthMode.ApiKey

// Advanced's groups, in the order every app draws them, each with its title.
enum class AdvancedGroup(val title: String) {
    GettingThere(SignInWords.GROUP_GETTING_THERE),
    SigningIn(SignInWords.GROUP_SIGNING_IN),
    SpecialSetups(SignInWords.GROUP_SPECIAL_SETUPS),
}

// Advanced's rows: the sign-in page kind and its two fields, the home
// address, the Method, the custom headers and the client certificate.
enum class AdvancedRow { PageKind, PageFirst, PageSecond, Home, Method, Headers, Certificate }

data class LayoutGroup(val group: AdvancedGroup, val rows: List<AdvancedRow>)

// What Advanced draws: its groups in order, and the Methods the Method row
// offers (none while joining, where the link decides how to sign in).
data class AdvancedLayout(val groups: List<LayoutGroup>, val methods: List<AuthMode>)

// Advanced for one form: getting to the server (the sign-in page, its two
// fields once a kind is picked, the home address), signing in (the Method,
// left out while joining), and special setups (custom headers, and the
// client certificate where the host has that row).
fun advancedLayout(joining: Boolean, takesApiKeys: Boolean?, apiKeyOn: Boolean, kind: GatewayKind, certificateRow: Boolean): AdvancedLayout {
    val fields = if (kind == GatewayKind.None) emptyList() else listOf(AdvancedRow.PageFirst, AdvancedRow.PageSecond)
    val special = if (certificateRow) listOf(AdvancedRow.Headers, AdvancedRow.Certificate) else listOf(AdvancedRow.Headers)
    val groups = buildList {
        add(LayoutGroup(AdvancedGroup.GettingThere, listOf(AdvancedRow.PageKind) + fields + AdvancedRow.Home))
        if (!joining) add(LayoutGroup(AdvancedGroup.SigningIn, listOf(AdvancedRow.Method)))
        add(LayoutGroup(AdvancedGroup.SpecialSetups, special))
    }
    return AdvancedLayout(groups, if (joining) emptyList() else methodsShown(takesApiKeys, apiKeyOn))
}

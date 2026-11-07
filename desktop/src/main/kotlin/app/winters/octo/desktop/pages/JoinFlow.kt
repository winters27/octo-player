package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.winters.octo.connection.GatewayWords
import app.winters.octo.connection.ResolvedHeaders
import app.winters.octo.connection.fingerprint
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.GlassField
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.ProgressRing
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.CertificateQuestion
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SignInRequest
import app.winters.octo.desktop.server.familyPlatform
import app.winters.octo.desktop.system.openInBrowser
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.FamilyLink
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.parseFamilyLink
import app.winters.octo.ui.family.HANDOVER_SIGNED_IN
import app.winters.octo.ui.family.HandOverResult
import app.winters.octo.ui.family.JoinOutcome
import app.winters.octo.ui.family.MIN_PASSWORD
import app.winters.octo.ui.family.joinWithInvite
import app.winters.octo.ui.family.passwordStrength
import app.winters.octo.ui.family.receiveHandOver
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// Signs up from the invite the form holds: the new member's name and the
// password they chose go to the family page, then this app signs in with
// that username and password like any typed sign-in. The family page is
// asked through a client of its own, with the form's addresses, headers and
// sign-in page fields. A server with a certificate of its own is asked
// about first, as signing in asks. Answers the sign-in once done; otherwise
// the form says why (opening the sign-in page fields when one answered),
// or holds the certificate question.
suspend fun signUpFromForm(accounts: Accounts, form: SignInForm): SignInOutcome.Done? {
    val url = form.url ?: return null
    val invite = form.invite ?: return null
    val tried = form.attemptAddress
    val headers = when (val resolved = accounts.joinHeaders(form.request())) {
        is ResolvedHeaders.Problem -> {
            form.result = false to resolved.text
            return null
        }
        is ResolvedHeaders.Ready -> resolved.headers.associate { it.name to it.value }
    }
    val joined = accounts.checkClient(listOfNotNull(url, form.homeUrl), headers).use { join ->
        val outcome = joinWithInvite(url, invite.token, form.inviteName, form.invitePassword, join.http, home = form.homeUrl)
        if (outcome is JoinOutcome.Untrusted) {
            val leaf = join.security.takeRejected(outcome.host)
            if (leaf != null) form.question = CertificateQuestion(outcome.host, leaf.fingerprint()) else form.result = false to "This server's certificate is not trusted."
        }
        outcome
    }
    when (joined) {
        is JoinOutcome.Failed -> {
            form.result = false to (joined.gateway?.let { GatewayWords.failure(it, joined.loginSent) } ?: joined.message)
            form.failed(tried, joined.gateway)
        }
        is JoinOutcome.Untrusted -> Unit
        is JoinOutcome.SignedUp -> when (val done = accounts.signIn(form.signUpRequest(joined.username, joined.password))) {
            is SignInOutcome.Done -> {
                form.invitePassword = ""
                form.inviteAgain = ""
                form.invite = null
                return done
            }
            is SignInOutcome.Saved -> Unit
            is SignInOutcome.Failed -> {
                form.result = false to done.message
                form.failed(tried, done.gateway)
            }
            is SignInOutcome.Untrusted -> form.question = done.question
        }
    }
    return null
}

// Takes the sign-in another device of one's own hands over: redeems the
// token from its link, waits for that device to allow it, opens what it
// sends with the key from the link, and signs in with it like a typed one,
// with the form's headers and sign-in page fields. Every call goes through
// a client of its own with those. The form shows each step in plain words;
// a sign-in page that answers stops it and opens its fields, and Try again
// uses them.
suspend fun receiveFromForm(accounts: Accounts, platform: FamilyPlatform, form: SignInForm): SignInOutcome.Done? {
    val link = form.handOver ?: return null
    val tried = form.attemptAddress
    val headers = when (val resolved = accounts.joinHeaders(form.request())) {
        is ResolvedHeaders.Problem -> {
            form.handOverStep = null
            form.result = false to resolved.text
            return null
        }
        is ResolvedHeaders.Ready -> resolved.headers.associate { it.name to it.value }
    }
    val got = accounts.checkClient(listOfNotNull(link.base.toHttpUrlOrNull(), form.homeUrl), headers).use { join ->
        receiveHandOver(link, join.http, accounts.security.deviceName(), platform, step = { form.handOverStep = it })
    }
    if (got !is HandOverResult.Received) {
        val refused = got as HandOverResult.Refused
        form.handOverStep = null
        form.result = false to (refused.gateway?.let { GatewayWords.failure(it, refused.loginSent) } ?: refused.message)
        form.failed(tried, refused.gateway)
        return null
    }
    val signIn = got.signIn
    val request = SignInRequest(
        address = signIn.server,
        username = signIn.username,
        secret = signIn.secret,
        mode = runCatching { AuthMode.valueOf(signIn.mode) }.getOrDefault(AuthMode.Token),
        home = signIn.home.orEmpty(),
        headers = form.headers.toList(),
        rememberPassword = true,
        gateway = form.gatewayDraft(),
    )
    when (val done = accounts.signIn(request)) {
        is SignInOutcome.Done -> {
            form.handOverStep = HANDOVER_SIGNED_IN
            form.handOver = null
            return done
        }
        is SignInOutcome.Saved -> Unit
        is SignInOutcome.Failed -> {
            form.result = false to done.message
            form.failed(tried, done.gateway)
        }
        is SignInOutcome.Untrusted -> form.question = done.question
    }
    form.handOverStep = null
    return null
}

// Opens a link clicked under a QR code: an octo:// link in Octo itself,
// any other in the browser (an Octo server's family page, which hands it
// on to Octo).
fun AppState.openJoinLink(url: String) {
    val link = if (url.startsWith("octo:", ignoreCase = true)) parseFamilyLink(url) else null
    if (link != null) openFamilyLink(link) else openInBrowser(url, os)
}

// Opens a family link, from a launch, the clipboard or a pasted link:
// signed out, the sign-in page fills in; signed in, a popup does the same,
// and the link's server becomes the one in use once signed in.
fun AppState.openFamilyLink(link: FamilyLink) {
    if (connection == null) {
        signInForm.take(link)
        return
    }
    popups.showCentred(width = SettingsSize.Sheet, maxHeight = SettingsSize.SheetMax) { close ->
        val form = remember { SignInForm().also { it.take(link) } }
        LinkSheet(this@openFamilyLink, form, close)
    }
}

@Composable
private fun ColumnScope.LinkSheet(app: AppState, form: SignInForm, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<CertificateQuestion?>(null) }
    fun finish(done: SignInOutcome.Done?) {
        asking = form.question
        if (done != null) {
            close()
            app.arrive(done.connection, Page.Home, done.note)
        }
    }
    fun go() {
        if (form.busy) return
        form.busy = true
        form.result = null
        val tried = form.attemptAddress
        scope.launch {
            val done = when {
                form.invite != null -> signUpFromForm(app.accounts, form)
                form.handOver != null -> receiveFromForm(app.accounts, familyPlatform(app.os), form)
                else -> when (val signedIn = app.accounts.signIn(form.request())) {
                    is SignInOutcome.Done -> signedIn
                    is SignInOutcome.Failed -> null.also {
                        form.result = false to signedIn.message
                        form.failed(tried, signedIn.gateway)
                    }
                    is SignInOutcome.Untrusted -> null.also { form.question = signedIn.question }
                    is SignInOutcome.Saved -> null
                }
            }
            form.busy = false
            finish(done)
        }
    }
    // A hand-over starts at once: there is nothing to type.
    LaunchedEffect(form.handOver) { if (form.handOver != null && form.handOverStep == null) go() }
    val question = asking
    if (question != null) {
        TrustQuestion(
            question,
            onTrust = {
                app.accounts.security.trust(question.host, question.fingerprint)
                form.question = null
                asking = null
                go()
            },
            onCancel = {
                form.distrust()
                asking = null
            },
        )
        return
    }
    MenuTitle(
        when {
            form.invite != null -> "Join a family"
            form.handOver != null -> "Signing in from your other device"
            else -> "Sign in"
        },
    )
    PopupPadding { LinkCard(form, ::go, onLeave = close) }
}

// The part of the sign-in card a family link fills: signing up from an
// invite (a name and a password, twice, with how strong it looks), a
// sign-in coming from another device (what is happening, step by step), or
// the server and username filled in, waiting for the password. Advanced,
// with the sign-in page fields, shows here too: an invite or hand-over to a
// server behind a sign-in page needs them.
@Composable
internal fun ColumnScope.LinkCard(form: SignInForm, go: () -> Unit, onLeave: () -> Unit) {
    val invite = form.invite
    val handOver = form.handOver
    when {
        invite != null -> {
            Txt("You're invited to ${invite.server.substringAfter("://")}. Choose your name and a password. Your login then works in Octo and any other music app.", OctoType.bodySmall, OctoColors.TextSecondary, maxLines = 3)
            Label("Your name")
            GlassField(form.inviteName, { form.inviteName = it; form.result = null }, Modifier.fillMaxWidth(), placeholder = "As the family sees you", onSubmit = go)
            Label("Password")
            SecretField(form.invitePassword, { form.invitePassword = it; form.result = null }, "At least $MIN_PASSWORD characters", go, null)
            passwordStrength(form.invitePassword)?.let { Txt(it, OctoType.caption, OctoColors.TextMuted) }
            Label("Password again")
            SecretField(form.inviteAgain, { form.inviteAgain = it; form.result = null }, "The same password", go, null)
        }
        handOver != null -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (form.busy) ProgressRing(null)
                Txt(form.handOverStep ?: "Ready to sign in to ${handOver.server.substringAfter("://")}", OctoType.body, OctoColors.TextPrimary, maxLines = 3)
            }
            Txt("On your other device, choose Allow when it asks about this computer.", OctoType.caption, OctoColors.TextMuted, maxLines = 2)
        }
        else -> {
            Label("Username")
            GlassField(form.username, { form.username = it; form.result = null }, Modifier.fillMaxWidth(), onSubmit = go)
            Label("Password")
            SecretField(form.password, { form.password = it; form.result = null }, "Password", go, null)
        }
    }
    AdvancedToggle(form.advancedOpen) { form.advancedOpen = !form.advancedOpen }
    if (form.advancedOpen) Advanced(form, go, joining = invite != null || handOver != null)
    form.result?.let { (ok, text) ->
        Txt(text, OctoType.bodySmall, if (ok) OctoColors.TextSecondary else OctoColors.Error, maxLines = 4)
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        TextAction(if (handOver != null) "Cancel" else "Sign in with a password instead", onLeave)
        when {
            invite != null -> AccentButton("Sign up", go, Modifier.widthIn(min = 150.dp), enabled = form.inviteReady, loading = form.busy, size = ButtonSize.Medium, fill = OctoColors.Accent)
            handOver != null -> if (!form.busy) AccentButton("Try again", go, Modifier.widthIn(min = 150.dp), size = ButtonSize.Medium, fill = OctoColors.Accent)
            else -> AccentButton("Sign in", go, Modifier.widthIn(min = 150.dp), enabled = form.ready, loading = form.busy, size = ButtonSize.Medium, fill = OctoColors.Accent)
        }
    }
}

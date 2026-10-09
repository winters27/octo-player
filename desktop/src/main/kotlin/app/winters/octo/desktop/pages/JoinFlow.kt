package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.winters.octo.connection.fingerprint
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.SettingsSize
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.server.CertificateQuestion
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.familyPlatform
import app.winters.octo.subsonic.FamilyLink
import app.winters.octo.ui.family.JoinOutcome
import app.winters.octo.ui.family.joinFamily
import app.winters.octo.ui.family.joinWithInvite
import kotlinx.coroutines.launch

// Joins a family from what the form holds: pairs with the code, or accepts
// the invite and then pairs, and signs in with the secret that answers.
// A server with a certificate of its own is asked about first, as signing
// in asks. Answers the sign-in once done; otherwise the form says why, or
// holds the certificate question.
suspend fun joinFromForm(app: AppState, form: SignInForm): SignInOutcome.Done? {
    val url = form.url ?: return null
    val name = app.accounts.security.deviceName()
    val invite = form.invite
    val joined = if (invite != null) {
        joinWithInvite(url, invite.token, form.inviteName, form.invitePassword, name, familyPlatform(app.os), app.http)
    } else {
        joinFamily(url, form.username.trim(), form.code, name, familyPlatform(app.os), app.http)
    }
    when (joined) {
        is JoinOutcome.Failed -> form.result = false to joined.message
        is JoinOutcome.Untrusted -> {
            val leaf = app.accounts.security.takeRejected(joined.host)
            if (leaf != null) form.question = CertificateQuestion(joined.host, leaf.fingerprint()) else form.result = false to "This server's certificate is not trusted."
        }
        is JoinOutcome.Paired -> when (val done = app.accounts.signIn(form.joinRequest(joined.pair))) {
            is SignInOutcome.Done -> {
                form.code = ""
                form.invitePassword = ""
                form.inviteAgain = ""
                form.invite = null
                return done
            }
            is SignInOutcome.Saved -> Unit
            is SignInOutcome.Failed -> form.result = false to done.message
            is SignInOutcome.Untrusted -> form.question = done.question
        }
    }
    return null
}

// Opens a family link, from a launch, the clipboard or a pasted link:
// signed out, the sign-in page fills in and joins; signed in, a sheet does
// the same and the family's server becomes the one in use.
fun AppState.openFamilyLink(link: FamilyLink) {
    if (connection == null) {
        signInForm.take(link)
        return
    }
    popups.showCentred(width = SettingsSize.Sheet, maxHeight = SettingsSize.SheetMax) { close ->
        val form = remember { SignInForm().also { it.take(link) } }
        JoinSheet(this@openFamilyLink, form, close)
    }
}

@Composable
private fun ColumnScope.JoinSheet(app: AppState, form: SignInForm, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<CertificateQuestion?>(null) }
    fun join() {
        if (!form.joinReady) return
        form.busy = true
        form.result = null
        scope.launch {
            val done = joinFromForm(app, form)
            form.busy = false
            asking = form.question
            if (done != null) {
                close()
                app.arrive(done.connection, Page.Home, done.note)
            }
        }
    }
    val question = asking
    if (question != null) {
        TrustQuestion(
            question,
            onTrust = {
                app.accounts.security.trust(question.host, question.fingerprint)
                form.question = null
                asking = null
                join()
            },
            onCancel = {
                form.distrust()
                asking = null
            },
        )
        return
    }
    MenuTitle(if (form.invite != null) "Join a family" else "Add this computer to your family")
    PopupPadding { JoinCard(app, form, ::join, onPassword = close) }
}

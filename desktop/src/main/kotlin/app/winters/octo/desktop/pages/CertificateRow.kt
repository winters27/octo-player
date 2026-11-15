package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import app.winters.octo.connection.SignInWords
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.DesktopType
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.design.Spinner
import app.winters.octo.design.Txt
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.server.CertificateChoice
import app.winters.octo.desktop.server.CertificateDraft
import app.winters.octo.desktop.server.CertificateLoad
import app.winters.octo.desktop.server.CertificateOpened
import app.winters.octo.desktop.server.CertificateShown
import app.winters.octo.desktop.server.CertificateWords
import app.winters.octo.desktop.server.PickedCertificate
import app.winters.octo.desktop.server.certificateName
import app.winters.octo.desktop.settings.DesktopOs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// The client certificate row of Advanced, Special setups: the file the
// sign-in sends, with the day it runs out or why it can't be read, Choose
// or Change, and Remove. `replacingId`, `url` and `home` are what the
// request resolves its certificate with, so the row shows what the sign-in
// sends.
@Composable
internal fun CertificateRow(app: AppState, form: SignInForm, replacingId: String?, url: HttpUrl?, home: HttpUrl?) {
    val scope = rememberCoroutineScope()
    val shown = app.accounts.certificateShown(form.certificateDraft, replacingId, url, home)
    val sends = (shown as? CertificateShown.Sends)?.choice
    // A certificate shown that is not read yet is read now; an answer for
    // one no longer shown is let go.
    LaunchedEffect(sends) {
        if (sends == null || form.certificateLoad?.choice == sends) return@LaunchedEffect
        form.knowCertificate(sends, app.accounts.loadChoice(sends))
    }
    val load = form.certificateLoad?.takeIf { it.choice == sends }?.load
    val name = when (shown) {
        CertificateShown.None -> null
        is CertificateShown.HttpsOnly -> shown.name
        is CertificateShown.Sends -> choiceName(shown.choice)
    }
    val line = when {
        name == null -> SignInWords.CERT_NONE
        load is CertificateLoad.Found -> SignInWords.certificateLine(name, certificateDate(load.certificate.notAfter))
        else -> name
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Xs)) {
        FormRow(SignInWords.CERT_TITLE, line) {
            if (form.certificateOpening) Spinner(size = IconSize.Table)
            GlazeCapsule(
                null,
                if (name == null) SignInWords.CERT_CHOOSE else SignInWords.CERT_CHANGE,
                { chooseCertificate(app, form, scope) },
                enabled = !form.certificateOpening,
                height = ControlHeight.M,
            )
            if (name != null) IconAction(OctoIcons.Close, SignInWords.CERT_REMOVE, form::removeCertificate, size = 32.dp, iconSize = IconSize.Table)
        }
        form.certificateProblem?.let { CertificateNote(it, error = true) }
        (load as? CertificateLoad.Unreadable)?.let { CertificateNote(it.words, error = true) }
        if (shown is CertificateShown.HttpsOnly) CertificateNote(CertificateWords.HTTPS_ONLY, error = false)
        if (form.certificateDraft is CertificateDraft.Use) CertificateNote(CertificateWords.KEEP_FILE, error = false)
    }
}

// A line under the row: why the file can't be used, in the error words, or
// what to know about it.
@Composable
private fun CertificateNote(text: String, error: Boolean) {
    FormNote(text, if (error) NoteKind.Error else NoteKind.Info)
}

private fun choiceName(choice: CertificateChoice): String? = when (choice) {
    CertificateChoice.None -> null
    is CertificateChoice.Saved -> choice.file.name
    is CertificateChoice.Picked -> certificateName(choice.path)
}

// The day a certificate runs out, as the row says it, in the computer's own
// date format.
internal fun certificateDate(at: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(at)

internal fun isCertificateFileName(name: String): Boolean =
    name.endsWith(".p12", ignoreCase = true) || name.endsWith(".pfx", ignoreCase = true)

// The system's file window for a certificate file. On Windows the window
// lists only those; elsewhere the name filter does.
internal fun chooseCertificateFile(os: DesktopOs): File? {
    val dialog = FileDialog(null as Frame?, SignInWords.CERT_TITLE, FileDialog.LOAD)
    if (os == DesktopOs.Windows) dialog.file = "*.p12;*.pfx"
    dialog.setFilenameFilter { _, name -> isCertificateFileName(name) }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

// Opens a chosen file off the window's thread, with an empty password
// first; only a file that needs one asks for it.
private fun chooseCertificate(app: AppState, form: SignInForm, scope: CoroutineScope) {
    val file = chooseCertificateFile(app.os) ?: return
    val path = file.absolutePath
    val turn = Any()
    form.certificateTurn = turn
    form.certificateOpening = true
    form.certificateProblem = null
    scope.launch {
        val opened = app.accounts.openPicked(path, "")
        if (form.certificateTurn !== turn) return@launch
        form.certificateOpening = false
        when {
            opened is CertificateOpened.Found -> form.useCertificate(PickedCertificate(path, "", opened.certificate))
            opened is CertificateOpened.Refused && opened.wrongPassword -> askCertificatePassword(app, form, path, turn)
            opened is CertificateOpened.Refused -> form.certificateProblem = opened.words
        }
    }
}

// Asks for the password the picked file was saved with, in the middle of the
// window.
internal fun askCertificatePassword(app: AppState, form: SignInForm, path: String, turn: Any) {
    val opening = Any()
    app.popups.showCentred(width = SettingsSize.Sheet) { close -> key(opening) { CertificatePassword(app, form, path, turn, close) } }
}

// The password the certificate file was saved with. A file that still
// can't be opened says why here.
@Composable
private fun ColumnScope.CertificatePassword(app: AppState, form: SignInForm, path: String, turn: Any, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun use() {
        if (busy || password.isEmpty()) return
        busy = true
        problem = null
        val typed = password
        scope.launch {
            val opened = app.accounts.openPicked(path, typed)
            busy = false
            when (opened) {
                is CertificateOpened.Found -> {
                    close()
                    if (form.certificateTurn === turn) form.useCertificate(PickedCertificate(path, typed, opened.certificate))
                }
                is CertificateOpened.Refused -> problem = opened.words
            }
        }
    }

    MenuTitle(SignInWords.CERT_PASSWORD_TITLE)
    PopupPadding {
        Txt(SignInWords.CERT_PASSWORD_HINT, DesktopType.meta, OctoColors.TextSecondary, maxLines = 2)
        SecretField(password, { password = it; problem = null }, SignInWords.CERT_PASSWORD_FIELD, ::use, first)
        problem?.let { Txt(it, DesktopType.meta, OctoColors.Error, maxLines = 3) }
        Row(
            Modifier.fillMaxWidth().padding(top = Space.Xs, bottom = Space.Xs),
            horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlazeCapsule(null, "Cancel", close, height = ControlHeight.L)
            AccentButton(SignInWords.CERT_PASSWORD_USE, ::use, enabled = password.isNotEmpty(), loading = busy, size = ButtonSize.Medium)
        }
    }
}

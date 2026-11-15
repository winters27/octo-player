package app.winters.octo.desktop.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import app.winters.octo.connection.SignInWords
import app.winters.octo.design.AccentButton
import app.winters.octo.design.ButtonSize
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.LocalPopupRequest
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.PopupRequest
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.design.Spinner
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
    val choose = LocalChooseCertificateFile.current
    val opener = LocalPopupRequest.current
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
                { choose(app.os)?.let { openChosenCertificate(app, form, it.absolutePath, scope, opener) } },
                Modifier.focusRequester(form.certificateButton),
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

// The window the row's Choose picks a file in: the system's own, unless a
// test gives one of its own.
internal val LocalChooseCertificateFile = staticCompositionLocalOf<(DesktopOs) -> File?> { ::chooseCertificateFile }

// Opens a file chosen on the row, with an empty password first; only a
// file that needs one asks for it, over `opener` (the pop-up the row is
// drawn in, none on the page). The file is read and opened off the window's
// thread, in the row's `scope`. Choose is off while it opens and on again
// however the opening ends, a scope ended with the row (Advanced closed)
// included; a newer opening, or a link that started the form over, owns
// the flag from then on.
internal fun openChosenCertificate(app: AppState, form: SignInForm, path: String, scope: CoroutineScope, opener: PopupRequest?) {
    val turn = Any()
    form.certificateTurn = turn
    form.certificateOpening = true
    form.certificateProblem = null
    scope.launch {
        try {
            val opened = app.accounts.openPicked(path, "")
            if (form.certificateTurn !== turn) return@launch
            when {
                opened is CertificateOpened.Found -> form.useCertificate(PickedCertificate(path, "", opened.certificate))
                opened is CertificateOpened.Refused && opened.wrongPassword -> askCertificatePassword(app, form, path, turn, opener)
                opened is CertificateOpened.Refused -> form.certificateProblem = opened.words
            }
        } finally {
            if (form.certificateTurn === turn) form.certificateOpening = false
        }
    }
}

// Asks for the password the picked file was saved with, in the middle of the
// window, drawn as a sheet as the server sheets are: the pop-up gives the
// field the keyboard once the sheet's rows are drawn. The page behind is
// shaded as under every sheet. Asked from a pop-up (`opener`, a server
// sheet) that is still the one on top, it keeps that pop-up under it, which
// comes back as it was once the ask closes, however it closes; the keyboard
// then goes back to the row's Choose or Change. Anything else on top by then
// (a menu opened while the file was read) is replaced, as any sheet
// replaces it.
internal fun askCertificatePassword(app: AppState, form: SignInForm, path: String, turn: Any, opener: PopupRequest?) {
    val opening = Any()
    val first = FocusRequester()
    app.popups.showCentred(width = SettingsSize.Sheet, scrim = true, first = first, keepUnder = opener, returnTo = form.certificateButton) { close ->
        key(opening) { CompositionLocalProvider(LocalInSheet provides true) { CertificatePassword(app, form, path, turn, first, close) } }
    }
}

// The password the certificate file was saved with, under the sheet's own
// title in a group's row that says what it is. A file that still can't be
// opened says why in the group's error note.
@Composable
private fun ColumnScope.CertificatePassword(app: AppState, form: SignInForm, path: String, turn: Any, first: FocusRequester, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

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

    FormSheetTitle(SignInWords.CERT_PASSWORD_TITLE)
    PopupPadding {
        FormGroup(null, notes = { problem?.let { FormNote(it, NoteKind.Error) } }) {
            FormFieldRow(
                SignInWords.CERT_PASSWORD_FIELD,
                description = SignInWords.CERT_PASSWORD_HINT,
                value = password,
                onChange = {
                    password = it
                    problem = null
                },
                placeholder = SignInWords.REQUIRED,
                secret = true,
                onSubmit = ::use,
                focus = first,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Space.Xs, bottom = Space.Xs),
            horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlazeCapsule(null, "Cancel", close, height = ControlHeight.L)
            AccentButton(SignInWords.CERT_PASSWORD_USE, ::use, Modifier.widthIn(min = SheetMainButton), enabled = password.isNotEmpty(), loading = busy, size = ButtonSize.Medium)
        }
    }
}

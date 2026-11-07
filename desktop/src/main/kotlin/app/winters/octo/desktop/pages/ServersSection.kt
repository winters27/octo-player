package app.winters.octo.desktop.pages

import app.winters.octo.ui.family.passwordStrength
import app.winters.octo.ui.family.HANDOVER_TITLE
import app.winters.octo.desktop.family.showHandOver
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntRect
import app.winters.octo.design.ControlHeight
import app.winters.octo.design.CardFit
import app.winters.octo.design.Corner
import app.winters.octo.design.DesktopType
import app.winters.octo.design.FrameSize
import app.winters.octo.design.GlassField
import app.winters.octo.design.GlazeCapsule
import app.winters.octo.design.IconAction
import app.winters.octo.design.IconSize
import app.winters.octo.design.MenuRow
import app.winters.octo.design.MenuSeparator
import app.winters.octo.design.MenuTitle
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoSwitch
import app.winters.octo.design.PopupPadding
import app.winters.octo.design.RowHeight
import app.winters.octo.design.SettingsSize
import app.winters.octo.design.Space
import app.winters.octo.design.Spinner
import app.winters.octo.design.TextAction
import app.winters.octo.design.Txt
import app.winters.octo.design.hoverLift
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.library.LibraryState
import app.winters.octo.desktop.nav.Page
import app.winters.octo.desktop.server.Accounts
import app.winters.octo.desktop.server.Connection
import app.winters.octo.desktop.server.OCTO_LYRICS
import app.winters.octo.connection.GatewayWords
import app.winters.octo.server.warnsInRow
import app.winters.octo.desktop.server.SignInOutcome
import app.winters.octo.desktop.server.SwitchOutcome
import app.winters.octo.desktop.server.TestOutcome
import app.winters.octo.desktop.server.accountLine
import app.winters.octo.desktop.server.overviewOf
import app.winters.octo.desktop.server.statusLine
import app.winters.octo.desktop.settings.SavedServer
import app.winters.octo.desktop.settings.name
import app.winters.octo.desktop.ui.windowRect
import app.winters.octo.server.PasswordChange
import app.winters.octo.server.PasswordDraft
import app.winters.octo.server.passwordChangeWords
import app.winters.octo.server.passwordDraftProblem
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.subsonic.shownAddress
import app.winters.octo.serverupdate.upToDateLine
import kotlinx.coroutines.launch

// Settings > Servers: a newer Octo for the server in use, the servers kept
// on this computer with the one in use lit as Settings' list lights its
// page, adding another,
// what the one in use is and holds, and reading or scanning it again.

@Composable
internal fun ServerRows(app: AppState) {
    val settings by app.settings.state.collectAsState()
    val connection = app.connection
    // Every server is asked how it is while the page is on screen, and
    // again once another is in use.
    LaunchedEffect(connection, settings.servers.size) { app.serverFacts.refresh() }
    if (connection?.isOcto == true) ServerUpdateCard(app)
    SettingsCard("Your servers", OctoIcons.Servers) {
        settings.servers.forEach { server ->
            ServerRow(app, server, inUse = server.id == settings.activeServer)
        }
        ActionRow(
            "Add a server",
            "Switch between servers in one click.",
            "Add",
            { addServer(app) },
        )
    }
    if (connection != null) OverviewCards(app, connection)
}

// The ink of the lines under the name of the server in use, and of its "In
// use" mark: white eased back so it stays quieter than the name, yet over
// 4.5:1 on the chosen fill, where the muted ink is under it.
internal val InUseSubtitle: Color @Composable @ReadOnlyComposable get() = OctoColors.TextPrimary.copy(alpha = 0.78f)

// One kept server: its name, who is signed in where, and how it answered;
// Switch (or Sign in) and its menu on the right.
@Composable
private fun ServerRow(app: AppState, server: SavedServer, inUse: Boolean) {
    val switching = app.switching?.id == server.id
    val check = app.serverFacts.checks[server.id]
    Box(
        Modifier
            .settingsRow(server.name, stable = "[each saved server]", id = "server-${settingsSlug(server.id)}")
            .fillMaxWidth()
            .heightIn(min = RowHeight.Roomy)
            .semantics { selected = inUse },
    ) {
        if (inUse) PlaceFill(Modifier.matchParentSize().padding(CardFit.Fill))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = RowInset, vertical = Space.L),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.L),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                    Txt(server.name, if (inUse) ChosenWords else DesktopType.body, if (server.signedOut) OctoColors.TextSecondary else OctoColors.TextPrimary)
                    if (inUse) InUseMark()
                }
                Txt(accountLine(server), DesktopType.meta, OctoColors.TextMuted, Modifier.padding(top = Space.Xxs))
                // The one in use has its kind said under the list, and one
                // being switched to says so at the row's end, so neither
                // says it here too, unless the one in use needs https for
                // its sign-in page, which nothing else says.
                val quiet = check.warnsInRow(inUse)
                if (!switching && (!inUse || quiet)) {
                    Txt(statusLine(server, check, false, inUse), DesktopType.meta, if (quiet) OctoColors.SignalOrange else OctoColors.TextMuted, Modifier.padding(top = Space.Xxs))
                }
            }
            when {
                switching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    Spinner(size = IconSize.Table)
                    Txt("Switching", DesktopType.meta, OctoColors.TextMuted)
                }
                !inUse -> RowAction(if (server.signedOut) "Sign in" else "Switch", { switchServer(app, server.id) }, enabled = app.switching == null)
            }
            ServerMenuButton(app, server, inUse)
        }
    }
}

// The server in use, marked as such: a green dot and its words.
@Composable
private fun InUseMark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Xs)) {
        Box(Modifier.size(Space.S).clip(CircleShape).background(OctoColors.SignalGreen))
        Txt("In use", DesktopType.meta, InUseSubtitle)
    }
}

// The server's menu: edit it, change the password, sign out or in, remove.
@Composable
private fun ServerMenuButton(app: AppState, server: SavedServer, inUse: Boolean) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    val words = "More for ${server.name}"
    IconAction(
        OctoIcons.More,
        words,
        {
            app.popups.showUnder(anchor, width = FrameSize.Menu) { close ->
                MenuTitle(server.name)
                MenuRow("Edit", { close(); editServer(app, server) }, OctoIcons.Rename)
                if (inUse && server.authMode != AuthMode.ApiKey) MenuRow("Change password", { close(); changePassword(app) }, OctoIcons.Settings)
                if (server.signedOut) {
                    MenuRow("Sign in", { close(); signInTo(app, server) }, OctoIcons.Forward)
                } else {
                    MenuRow("Sign out", { close(); app.signOutOf(server.id) }, OctoIcons.SignOut)
                }
                MenuSeparator()
                MenuRow("Remove", { close(); askToRemove(app, server) }, OctoIcons.Delete, destructive = true)
            }
        },
        // Its dots end on the same line as the text buttons' words.
        Modifier.offset(x = CardFit.MenuDots).onGloballyPositioned { anchor = it.windowRect() },
        size = ControlHeight.M,
        iconSize = IconSize.Toolbar,
        tint = OctoColors.TextSecondary,
    )
}

// What the server in use is, what it holds and offers and who is signed in,
// then a card for reading its library or its folders again.
@Composable
private fun OverviewCards(app: AppState, connection: Connection) {
    val facts = app.serverFacts
    val state by (app.library?.state ?: return).collectAsState()
    val index = (state as? LibraryState.Ready)?.index
    // As kept now: a check or an update since signing in can bring a new version.
    val settings by app.settings.state.collectAsState()
    val server = settings.servers.firstOrNull { it.id == connection.server.id } ?: connection.server
    val update by app.serverUpdate.track.collectAsState()
    val overview = overviewOf(
        server,
        lyrics = connection.lyricsByIdOn || connection.supports(OCTO_LYRICS),
        adds = connection.acquires,
        songs = index?.songs?.size,
        albums = index?.albums?.size,
        artists = index?.artists?.size,
        playlists = index?.let { app.playlists.size },
        scan = facts.scan,
        user = facts.user,
        answerMs = facts.checks[server.id]?.ms,
        now = System.currentTimeMillis(),
    )
    // Octo that knows it runs the newest release says so.
    val kind = upToDateLine(update)?.takeIf { connection.isOcto } ?: overview.kind
    SettingsCard("This server", OctoIcons.Database) {
        SettingRow(kind, overview.offers ?: "Plays your music, with nothing beyond it.", stable = "[the server's kind]", id = "kind") {
            overview.answer?.let { Txt(it, DesktopType.meta.copy(fontFeatureSettings = "tnum"), OctoColors.TextSecondary, Modifier.padding(end = RowValueEnd)) }
        }
        SettingRow("In your library", overview.counts ?: if (state is LibraryState.Failed) "Couldn't read the library just now." else "Reading the library.")
        val kept = if (app.accounts.remembersSignIn) "Octo keeps you signed in on this computer." else "You'll sign in again the next time Octo opens."
        SettingRow("Signed in as ${connection.client.username.ifEmpty { "API key" }}", overview.passwordNote ?: kept, stable = "Signed in as ...") {
            if (overview.canChangePassword) RowAction("Change password", { changePassword(app) })
        }
        // Any account on a family server: a QR code another device scans to
        // sign in with the same login.
        if (connection.family) {
            SettingRow(HANDOVER_TITLE, "Show a QR code to sign in on your phone or another computer, without typing your password.") {
                RowAction("Show QR code", { showHandOver(app.popups, app, app.family) }, icon = OctoIcons.QrCode)
            }
        }
    }
    SettingsCard("Maintenance", OctoIcons.Wrench) {
        ActionRow("Read the library again", "When new music hasn't shown up yet.", "Read again", {
            app.library?.load()
            app.refreshPlaylists()
        })
        if (overview.canScan) {
            val caption = facts.scanProblem ?: overview.scan ?: "Has the server look for music added to its folders."
            SettingRow("Scan the server", caption) {
                if (overview.scanning) {
                    Spinner(size = IconSize.Table)
                } else {
                    RowAction("Scan", { facts.startScan { app.library?.load() } })
                }
            }
        } else if (overview.scan != null) {
            SettingRow("Its folders", overview.scan)
        }
    }
}

// Makes another kept server the one in use, from its row or the command
// search. One whose password is not kept asks for it.
fun switchServer(app: AppState, id: String) {
    val server = app.accounts.find(id) ?: return
    if (server.signedOut) return signInTo(app, server)
    val page = if (app.navigator.current.page == Page.Settings) Page.Settings else Page.Home
    app.switchTo(id, page) { outcome ->
        if (outcome is SwitchOutcome.NeedsPassword) serverSheet(app, sheetFor(outcome), outcome.server, outcome.note)
    }
}

// What a server sheet is for. Secure is a switch to a kept server whose
// sign-in page needs https: the edit sheet's fields, for the address, and
// a sign-in again that makes it the one in use once it works.
internal enum class SheetKind { Add, Edit, SignIn, Secure }

// The sheet a switch that asks for the password opens: moving the address
// to https needs the address field, which the secure sheet has.
internal fun sheetFor(outcome: SwitchOutcome.NeedsPassword): SheetKind =
    if (outcome.note == GatewayWords.NEEDS_HTTPS) SheetKind.Secure else SheetKind.SignIn

// What saving a server sheet asks of the accounts, under `ticket`. A sheet
// that signs in to a kept server again never hands it to another account.
internal suspend fun saveSheet(accounts: Accounts, kind: SheetKind, server: SavedServer?, form: SignInForm, ticket: Long): SignInOutcome = when (kind) {
    SheetKind.Add -> accounts.add(form.request())
    SheetKind.Edit -> accounts.edit(server!!.id, form.request(), form.label, ticket)
    SheetKind.SignIn -> accounts.signInAgain(server!!.id, form.request(), ticket, sameAccount = true)
    SheetKind.Secure -> accounts.signInAgain(server!!.id, form.request(), ticket, form.label, sameAccount = true)
}

fun addServer(app: AppState) = serverSheet(app, SheetKind.Add, null)

fun editServer(app: AppState, server: SavedServer, note: String? = null) = serverSheet(app, SheetKind.Edit, server, note)

// Asks for the password of a kept server, then makes it the one in use.
fun signInTo(app: AppState, server: SavedServer, note: String? = null) = serverSheet(app, SheetKind.SignIn, server, note)

private fun serverSheet(app: AppState, kind: SheetKind, server: SavedServer?, note: String? = null) {
    // Each opening starts afresh, even straight after another sheet.
    val opening = Any()
    app.popups.showCentred(width = SettingsSize.Sheet, maxHeight = SettingsSize.SheetMax) { close ->
        key(opening) {
            val form = remember { SignInForm(server, keepsSecret = kind == SheetKind.Edit || kind == SheetKind.Secure).also { f -> note?.let { f.result = false to it } } }
            ServerSheet(app, kind, server, form, close)
        }
    }
}

// The form of a server sheet: the sign-in page's fields, smaller, in the
// middle of the window. A certificate the system does not trust is asked
// about in its place.
@Composable
private fun ColumnScope.ServerSheet(app: AppState, kind: SheetKind, server: SavedServer?, form: SignInForm, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    // Which of test or save ran last, to run again once a certificate is trusted.
    var again by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun test() {
        if (!form.ready) return
        again = ::test
        form.busy = true
        form.result = null
        val tried = form.attemptAddress
        scope.launch {
            val request = if (form.keepsSecret && server != null && form.password.isEmpty() && form.apiKey.isEmpty()) null else form.request()
            val tested = if (request == null) null else app.accounts.test(request)
            when (tested) {
                null -> form.result = false to "Enter the password to test the connection."
                is TestOutcome.Reached -> {
                    form.worked(tested.mode)
                    form.result = true to tested.facts.summary()
                }
                is TestOutcome.Failed -> {
                    form.result = false to tested.message
                    form.failed(tried, tested.gateway)
                }
                is TestOutcome.Untrusted -> form.question = tested.question
            }
            form.busy = false
        }
    }

    fun save() {
        if (!form.ready) return
        again = ::save
        // A new name alone needs nothing from the server; a change to the
        // sign-in page in front of it does.
        if (kind == SheetKind.Edit && server != null && !form.changesConnection(server)) {
            app.accounts.rename(server.id, form.label)
            close()
            return
        }
        form.busy = true
        form.result = null
        // Taken now, so a switch, sign-out or removal asked for while this
        // is checked comes after it.
        val ticket = app.accounts.ticket()
        val tried = form.attemptAddress
        scope.launch {
            val outcome = saveSheet(app.accounts, kind, server, form, ticket)
            form.busy = false
            when (outcome) {
                is SignInOutcome.Done -> {
                    close()
                    if (kind == SheetKind.Edit) {
                        app.reconnected(outcome.connection)
                        app.notice = listOfNotNull("Saved ${outcome.connection.server.name}.", outcome.note).joinToString(" ")
                    } else {
                        app.arrive(outcome.connection, if (app.navigator.current.page == Page.Settings) Page.Settings else Page.Home, outcome.note)
                    }
                }
                is SignInOutcome.Saved -> {
                    close()
                    val done = if (kind == SheetKind.Add) "Added ${outcome.server.name}. Switch to it whenever you like." else "Saved ${outcome.server.name}."
                    app.notice = listOfNotNull(done, outcome.note).joinToString(" ")
                    app.scope.launch { app.serverFacts.refresh() }
                }
                is SignInOutcome.Failed -> {
                    form.result = false to outcome.message
                    form.failed(tried, outcome.gateway)
                }
                is SignInOutcome.Untrusted -> form.question = outcome.question
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    // A kept server's sign-in page shows as its kind, saved, without its
    // values.
    LaunchedEffect(form) { if (server != null) form.knowSavedGateway(server, app.accounts.savedGateway(server)) }

    val asked = form.question
    if (asked != null) {
        TrustQuestion(
            asked,
            onTrust = {
                form.question = null
                app.accounts.security.trust(asked.host, asked.fingerprint)
                again?.invoke()
            },
            onCancel = form::distrust,
        )
        return
    }

    MenuTitle(
        when (kind) {
            SheetKind.Add -> "Add a server"
            SheetKind.Edit -> "Edit ${server?.name.orEmpty()}"
            SheetKind.SignIn, SheetKind.Secure -> "Sign in to ${server?.name.orEmpty()}"
        },
    )
    PopupPadding {
        when (kind) {
            SheetKind.Add -> Unit
            SheetKind.Edit -> Txt("Octo signs in with the new details before it saves them.", DesktopType.meta, OctoColors.TextSecondary, maxLines = 2)
            SheetKind.SignIn, SheetKind.Secure -> Txt(server?.let(::accountLine).orEmpty(), DesktopType.meta, OctoColors.TextSecondary)
        }
        if (kind != SheetKind.SignIn) {
            Field("Server address") {
                GlassField(
                    form.address,
                    form::typeAddress,
                    Modifier.fillMaxWidth(),
                    placeholder = "music.example.com or 192.168.1.20:4533",
                    focusRequester = if (kind == SheetKind.Add || kind == SheetKind.Secure) first else null,
                    onSubmit = ::save,
                    leading = { SchemeToggle(form.scheme.prefix, form::toggleScheme) },
                )
                form.url?.let { Txt("Connects to ${shownAddress(it)}", DesktopType.meta, OctoColors.TextMuted) }
                if (form.insecure) Txt("This address isn't encrypted and isn't on your home network, so others could read what is sent.", DesktopType.meta, OctoColors.Error, maxLines = 3)
            }
            Field("Name") {
                GlassField(form.label, { form.label = it }, Modifier.fillMaxWidth(), placeholder = "Optional, like Home or Work", onSubmit = ::save, focusRequester = if (kind == SheetKind.Edit) first else null)
            }
            Field(if (form.useApiKey) "Username (optional)" else "Username") {
                GlassField(form.username, { form.username = it; form.result = null }, Modifier.fillMaxWidth(), onSubmit = ::save)
            }
        }
        if (!form.useApiKey) {
            Field("Password") {
                SecretField(
                    form.password,
                    { form.password = it; form.result = null },
                    if (form.keepsSecret) "Leave empty to keep the saved one" else "Password",
                    ::save,
                    if (kind == SheetKind.SignIn) first else null,
                )
            }
        } else if (kind == SheetKind.SignIn) {
            Field("API key") { SecretField(form.apiKey, { form.apiKey = it; form.result = null }, "API key", ::save, first) }
        }
        SheetSwitch(
            if (form.useApiKey) "Remember API key" else "Remember password",
            rememberCaption(form.rememberPassword),
            form.rememberPassword,
        ) { form.rememberPassword = it }
        // Signing in again shows Advanced only once a sign-in page answered,
        // for its fields.
        if (kind != SheetKind.SignIn || form.detected != null) {
            AdvancedToggle(form.advancedOpen) { form.advancedOpen = !form.advancedOpen }
            if (form.advancedOpen) Advanced(form, ::save)
        }
        form.result?.let { (ok, text) -> Txt(text, DesktopType.meta, if (ok) OctoColors.TextSecondary else OctoColors.Error, maxLines = 4) }
        Row(Modifier.fillMaxWidth().padding(top = Space.Xs, bottom = Space.Xs), horizontalArrangement = Arrangement.spacedBy(Space.M), verticalAlignment = Alignment.CenterVertically) {
            if (kind != SheetKind.SignIn) TextAction("Test connection", ::test, enabled = form.ready)
            Spacer(Modifier.weight(1f))
            if (form.busy) Spinner(size = IconSize.Table)
            GlazeCapsule(null, "Cancel", close, height = ControlHeight.L)
            GlazeCapsule(
                null,
                when (kind) {
                    SheetKind.Add -> "Add"
                    SheetKind.Edit -> "Save"
                    SheetKind.SignIn, SheetKind.Secure -> "Sign in"
                },
                ::save,
                lit = true,
                enabled = form.ready,
                height = ControlHeight.L,
            )
        }
    }
}

// Changes the signed-in user's password on the server in use: the current
// one, then the new one twice.
fun changePassword(app: AppState) {
    val connection = app.connection ?: return
    val opening = Any()
    app.popups.showCentred(width = SettingsSize.Sheet, scrim = true) { close -> key(opening) { PasswordSheet(app, connection, close) } }
}

@Composable
private fun ColumnScope.PasswordSheet(app: AppState, connection: Connection, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var draft by remember { mutableStateOf(PasswordDraft()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // The form's own problem shows once something was tried, not while typing.
    var tried by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // A family member's password: the family's own call, which signs every
    // other app and device out.
    val family = connection.family && app.family.me?.managed == true

    fun change() {
        tried = true
        passwordDraftProblem(draft)?.let { problem = it; return }
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            val outcome = app.changePassword(connection, draft.current, draft.new, family)
            busy = false
            if (outcome.result == PasswordChange.Changed) {
                close()
                app.notice = listOfNotNull(passwordChangeWords(outcome.result, family), outcome.note).joinToString(" ")
            } else {
                problem = passwordChangeWords(outcome.result)
            }
        }
    }

    MenuTitle("Change password")
    PopupPadding {
        Txt(
            if (family) "For ${connection.client.username} on ${connection.server.name}. Every other app and device is signed out and needs the new password."
            else "For ${connection.client.username} on ${connection.server.name}. Your other apps will ask for the new password the next time they sign in.",
            DesktopType.meta,
            OctoColors.TextSecondary,
            maxLines = 3,
        )
        Field("Current password") { SecretField(draft.current, { draft = draft.copy(current = it); problem = null }, "Current password", ::change, first) }
        Field("New password") { SecretField(draft.new, { draft = draft.copy(new = it); problem = null }, "New password", ::change) }
        passwordStrength(draft.new)?.let { Txt(it, DesktopType.meta, OctoColors.TextMuted) }
        Field("New password again") { SecretField(draft.confirm, { draft = draft.copy(confirm = it); problem = null }, "New password again", ::change) }
        val shown = problem ?: if (tried) passwordDraftProblem(draft) else null
        shown?.let { Txt(it, DesktopType.meta, OctoColors.Error, maxLines = 3) }
        Row(Modifier.fillMaxWidth().padding(top = Space.Xs, bottom = Space.Xs), horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (busy) Spinner(size = IconSize.Table)
            GlazeCapsule(null, "Cancel", close, height = ControlHeight.L)
            GlazeCapsule(null, "Change password", ::change, lit = true, enabled = !busy, height = ControlHeight.L)
        }
    }
}

// Asks before a server leaves the list, saying what goes and what stays.
fun askToRemove(app: AppState, server: SavedServer) {
    val opening = Any()
    // Cancel takes the keyboard first, so Enter never lets go by itself.
    val cancel = FocusRequester()
    app.popups.showCentred(width = SettingsSize.Sheet, over = app.settingsColumn(), first = cancel) { close ->
        var forgetHere by remember(opening) { mutableStateOf(false) }
        val inUse = server.id == app.connection?.server?.id
        PopupPadding {
            SheetQuestion(
                "Remove ${server.name}?",
                "It leaves your list and Octo forgets its password." +
                    (if (inUse) " You'll be signed out of it now." else "") +
                    " Your music and playlists stay on the server.",
            )
            SheetSwitch(
                "Also delete what's kept here for it",
                if (forgetHere) "Its queue, play history and live lists on this computer go too." else "Off keeps its queue, play history and live lists on this computer, in case you add it again.",
                forgetHere,
            ) { forgetHere = it }
            Row(
                Modifier.fillMaxWidth().padding(top = Space.Xs, bottom = Space.Xs),
                horizontalArrangement = Arrangement.spacedBy(Space.M, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlazeCapsule(null, "Cancel", close, Modifier.focusRequester(cancel), height = ControlHeight.L)
                DangerAction("Remove", {
                    close()
                    app.removeServer(server.id, forgetHere)
                    app.notice = "Removed ${server.name}."
                }, icon = OctoIcons.Delete)
            }
        }
    }
}

// A field with its name close above it.
@Composable
private fun Field(name: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Xs)) {
        Txt(name, DesktopType.meta, OctoColors.TextMuted)
        content()
    }
}

// A switch in a sheet: its name and when you'd want it, the switch beside,
// all held in from the line's edge, where the keyboard's ring is drawn just
// outside it. The whole line flips it.
@Composable
private fun SheetSwitch(title: String, caption: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .ringOutside(Corner.Row)
            .hoverLift(Corner.RowShape)
            .toggleable(value = on, interactionSource = null, indication = null, role = Role.Switch, onValueChange = change)
            .padding(horizontal = Space.L, vertical = SheetLineDepth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.L),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, DesktopType.body)
            Txt(caption, DesktopType.meta, OctoColors.TextMuted, maxLines = 3)
        }
        // The line is the one switch a screen reader hears.
        OctoSwitch(on, change, Modifier.focusProperties { canFocus = false }.semantics { hideFromAccessibility() })
    }
}

// The room above and below a sheet line's words.
private val SheetLineDepth = Space.M + Space.Xxs

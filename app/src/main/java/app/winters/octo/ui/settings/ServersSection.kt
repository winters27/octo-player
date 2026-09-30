package app.winters.octo.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.winters.octo.data.SessionState
import app.winters.octo.data.StoredServer
import app.winters.octo.design.GlassPopup
import app.winters.octo.design.GlazeSelected
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoIcons
import app.winters.octo.design.OctoType
import app.winters.octo.design.Spinner
import app.winters.octo.playback.SwitchOutcome
import app.winters.octo.server.ServerCheck
import app.winters.octo.server.accountLine
import app.winters.octo.server.isWarning
import app.winters.octo.server.statusLine
import app.winters.octo.subsonic.AuthMode
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.LocalChoiceSheet
import app.winters.octo.ui.common.LocalFeedback
import app.winters.octo.ui.common.LocalHaze
import app.winters.octo.ui.common.PopupQuestion
import app.winters.octo.ui.common.choiceAnchor
import app.winters.octo.ui.nav.ServerForm
import app.winters.octo.ui.nav.ServerFormRoute
import app.winters.octo.ui.settings.rows.ActionRow
import app.winters.octo.ui.settings.rows.RowFrame
import app.winters.octo.ui.settings.rows.SettingsGroup
import app.winters.octo.ui.settings.rows.SwitchRow
import kotlinx.coroutines.launch

// Server and sync > Your servers: the servers kept on this phone, the one in
// use on the darker pill, Switch (or Sign in) for the others, each one's
// menu, and adding another. The same rows and words as the desktop's
// Settings > Servers.

private val PillShape = RoundedCornerShape(14.dp)

@Composable
internal fun ServerList(vm: ServerViewModel, onOpen: (NavKey) -> Unit, onChangePassword: () -> Unit) {
    val kept by vm.servers.collectAsStateWithLifecycle()
    val state by vm.session.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val feedback = LocalFeedback.current
    val sheet = LocalChoiceSheet.current
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<StoredServer?>(null) }
    val inUse = (state as? SessionState.SignedIn)?.session
    // Every server is asked how it is while the page is open, and again
    // once another is in use.
    LaunchedEffect(inUse?.id, kept.servers.size) { vm.refreshChecks() }

    fun switch(server: StoredServer) {
        if (server.signedOut) {
            onOpen(ServerFormRoute(ServerForm.SignIn, server.id))
            return
        }
        scope.launch {
            when (val outcome = vm.switchTo(server.id)) {
                is SwitchOutcome.Done -> outcome.notice?.let(feedback::done)
                is SwitchOutcome.NeedsPassword -> onOpen(ServerFormRoute(ServerForm.SignIn, outcome.server.id, outcome.note))
                is SwitchOutcome.Failed -> feedback.show(outcome.message)
            }
        }
    }

    fun menu(server: StoredServer, isInUse: Boolean) {
        val actions = buildList<Pair<Choice, () -> Unit>> {
            add(Choice("Edit") to { onOpen(ServerFormRoute(ServerForm.Edit, server.id)) })
            if (isInUse && inUse?.client?.authMode != AuthMode.ApiKey) add(Choice("Change password") to onChangePassword)
            if (server.signedOut) {
                add(Choice("Sign in") to { onOpen(ServerFormRoute(ServerForm.SignIn, server.id)) })
            } else {
                add(Choice("Sign out") to { vm.signOut(server.id) })
            }
            add(Choice("Remove") to { removing = server })
        }
        sheet.show(ChoiceRequest(server.name, actions.map { it.first }, -1) { picked -> actions.getOrNull(picked)?.second?.invoke() })
    }

    SettingsGroup(title = SettingsIndex.YourServers.title) {
        kept.servers.forEachIndexed { index, server ->
            val isInUse = server.id == inUse?.id
            ServerRow(
                server = server,
                inUse = isInUse,
                switching = switching == server.id,
                anySwitching = switching != null,
                check = checks[server.id],
                // Search points at the list's first row.
                searchEntry = if (index == 0) SettingsIndex.YourServers else null,
                onSwitch = { switch(server) },
                onMenu = { menu(server, isInUse) },
            )
        }
        ActionRow(
            SettingsIndex.AddServer,
            onClick = { onOpen(ServerFormRoute(ServerForm.Add)) },
            helper = "Keep another server here and switch to it in one tap. You stay on ${inUse?.name ?: "this one"} while you add it.",
        )
    }

    RemoveServer(
        server = removing,
        inUse = removing?.id == inUse?.id,
        onRemove = { server, forgetHere ->
            removing = null
            vm.remove(server.id, forgetHere)
            feedback.done("Removed ${server.name}.")
        },
        onCancel = { removing = null },
    )
}

// One kept server: its name, who is signed in where, and how it answered;
// Switch (or Sign in) and its menu on the right.
@Composable
private fun ServerRow(
    server: StoredServer,
    inUse: Boolean,
    switching: Boolean,
    anySwitching: Boolean,
    check: ServerCheck?,
    searchEntry: SettingEntry?,
    onSwitch: () -> Unit,
    onMenu: () -> Unit,
) {
    val sheet = LocalChoiceSheet.current
    Box(Modifier.fillMaxWidth().semantics { selected = inUse }) {
        if (inUse) GlazeSelected(Modifier.matchParentSize().padding(horizontal = 6.dp, vertical = 4.dp), shape = PillShape)
        RowFrame(searchEntry, minHeight = 64.dp) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        server.name,
                        style = OctoType.body,
                        color = if (server.signedOut) OctoColors.TextSecondary else OctoColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (inUse) Text("In use", style = OctoType.caption, color = OctoColors.TextSecondary)
                }
                Text(
                    accountLine(server.username, server.serverUrl),
                    style = OctoType.caption,
                    color = OctoColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    statusLine(server.serverType, server.serverVersion, server.signedOut, check, switching, inUse),
                    style = OctoType.caption,
                    color = if (!inUse && check.isWarning()) OctoColors.SignalOrange else OctoColors.TextMuted,
                )
            }
            when {
                switching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner(size = 16.dp, color = OctoColors.TextSecondary)
                    Text("Switching", style = OctoType.caption, color = OctoColors.TextSecondary)
                }
                !inUse -> Text(
                    if (server.signedOut) "Sign in" else "Switch",
                    style = OctoType.label,
                    color = if (anySwitching) OctoColors.TextMuted else OctoColors.Accent,
                    modifier = Modifier
                        .clickable(enabled = !anySwitching, role = Role.Button, onClick = onSwitch)
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                )
            }
            val more = "More for ${server.name}"
            Box(
                Modifier
                    .choiceAnchor(sheet)
                    .clickable(role = Role.Button, onClick = onMenu)
                    .semantics { contentDescription = more }
                    .padding(8.dp),
            ) {
                Icon(painterResource(OctoIcons.More), contentDescription = null, tint = OctoColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// Asks before a server leaves the list, saying what goes and what stays.
@Composable
private fun RemoveServer(server: StoredServer?, inUse: Boolean, onRemove: (StoredServer, Boolean) -> Unit, onCancel: () -> Unit) {
    var forgetHere by remember(server?.id) { mutableStateOf(false) }
    GlassPopup(
        visible = server != null,
        anchor = null,
        onDismiss = onCancel,
        backdrop = LocalHaze.current,
        title = "Remove",
    ) {
        val asked = server ?: return@GlassPopup
        PopupQuestion(
            "Remove ${asked.name}?",
            "It leaves your list and Octo forgets its password." +
                (if (inUse) " You'll be signed out of it now." else "") +
                " Your music and playlists stay on the server.",
            "Remove",
            onConfirm = { onRemove(asked, forgetHere) },
            onCancel = onCancel,
            more = {
                SwitchRow(
                    entry = null,
                    checked = forgetHere,
                    onChange = { forgetHere = it },
                    helper = if (forgetHere) {
                        "Its queue, live lists and downloads on this phone go too."
                    } else {
                        "Off keeps its queue, live lists and downloads on this phone, in case you add it again."
                    },
                    title = "Also delete what's kept here for it",
                )
            },
        )
    }
}

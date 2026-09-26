package app.winters.octo.ui.common

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import app.winters.octo.data.SessionRepository
import app.winters.octo.data.SessionState
import app.winters.octo.design.GlazeButton
import app.winters.octo.design.OctoColors
import app.winters.octo.design.OctoType
import app.winters.octo.device.Access
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.ui.nav.SignInRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// Goes to a page from anywhere in the shell, for the few places that are
// not handed a way to. Null outside it, where such links do not show.
// Not static: the shell makes a new one as tabs change, and only the few
// readers should redraw for that.
val LocalOpenPage = compositionLocalOf<((NavKey) -> Unit)?> { null }

// Where the library's music can come from: the phone, once access is
// allowed, and a server, once one is signed in.
@HiltViewModel
class LibraryReachViewModel @Inject constructor(
    val library: DeviceLibrary,
    sessions: SessionRepository,
) : ViewModel() {
    val access: StateFlow<Access> = library.access

    // No server set up. False while the saved sign-in is still being read.
    val signedOut: StateFlow<Boolean> = sessions.state.map { it is SessionState.SignedOut }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

// Asks for the music on the phone. After a second refusal the system stops
// showing the prompt, so this opens the app's settings instead.
@Composable
fun rememberAccessRequest(library: DeviceLibrary, access: Access): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context as? Activity
        val canAskAgain = activity?.shouldShowRequestPermissionRationale(library.permissionName) ?: true
        library.onPermissionResult(granted, canAskAgain)
    }
    return {
        if (access == Access.DeniedForever) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        } else {
            ask.launch(library.permissionName)
        }
    }
}

// The button for phone access, worded for whether the system still asks.
fun accessButtonLabel(access: Access): String = if (access == Access.DeniedForever) "Open settings" else "Allow access"

// Ways to get music in: "Allow access" while the phone's music is out of
// reach (unless `offerAccess` is off because something nearby asks already),
// and "Sign in to a server" while none is set up. Nothing when neither applies.
@Composable
fun LibrarySourceActions(modifier: Modifier = Modifier, offerAccess: Boolean = true, vm: LibraryReachViewModel = hiltViewModel()) {
    val access by vm.access.collectAsStateWithLifecycle()
    val signedOut by vm.signedOut.collectAsStateWithLifecycle()
    val openPage = LocalOpenPage.current
    val requestAccess = rememberAccessRequest(vm.library, access)
    val askAccess = offerAccess && access != Access.Granted
    val signIn = signedOut && openPage != null
    if (!askAccess && !signIn) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (askAccess) GlazeButton(accessButtonLabel(access), onClick = requestAccess)
        if (signIn && openPage != null) GlazeButton("Sign in to a server", onClick = { openPage(SignInRoute) })
    }
}

// The note for a list with nothing in it, with the ways to get music in.
@Composable
fun EmptyLibraryNote(text: String = "Nothing here yet") {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, style = OctoType.bodySmall, color = OctoColors.TextMuted)
        LibrarySourceActions()
    }
}

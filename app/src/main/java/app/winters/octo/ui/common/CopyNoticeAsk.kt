package app.winters.octo.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.winters.octo.server.CopyNoticePrefs
import app.winters.octo.server.CopyNotices
import app.winters.octo.server.NoticeAskWay
import app.winters.octo.server.askShowedNothing
import app.winters.octo.server.noticeAsk
import app.winters.octo.server.noticeAskWay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CopyNoticeAskViewModel @Inject constructor(private val prefs: CopyNoticePrefs) : ViewModel() {
    // Null until read.
    val asked: StateFlow<Boolean?> = prefs.asked.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun markAsked() {
        viewModelScope.launch { prefs.markAsked() }
    }
}

// Allow for the copy's notice, asked once: the system's ask where it can
// still show, the app's notification settings otherwise (and at once when
// the system showed nothing). Null while there is nothing to ask:
// notifications show already, or the listener was asked. Kept by the page,
// not by a list row, so scrolling never drops an ask under way.
@Composable
fun rememberCopyNoticeAsk(vm: CopyNoticeAskViewModel = hiltViewModel()): (() -> Unit)? {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val asked by vm.asked.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(CopyNotices.canPost(context)) }
    var reasonBefore by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        enabled = CopyNotices.canPost(context)
        onPauseOrDispose { }
    }
    val openSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        vm.markAsked()
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val reasonAfter = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ?: false
        if (askShowedNothing(granted, reasonBefore, reasonAfter)) openSettings() else vm.markAsked()
        enabled = CopyNotices.canPost(context)
    }
    if (!noticeAsk(enabled, asked)) return null
    return {
        when (noticeAskWay(Build.VERSION.SDK_INT, notificationsAllowed(context))) {
            NoticeAskWay.System -> {
                reasonBefore = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ?: false
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            NoticeAskWay.Settings -> openSettings()
        }
    }
}

// Whether notifications are allowed, as Android 13 and later ask for.
private fun notificationsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

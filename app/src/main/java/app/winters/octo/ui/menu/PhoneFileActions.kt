package app.winters.octo.ui.menu

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.media.RingtoneManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.winters.octo.catalog.SourceDao
import app.winters.octo.device.DeletePlan
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.device.PhoneFile
import app.winters.octo.device.PhoneSound
import app.winters.octo.device.deletePlan
import app.winters.octo.device.filesToShare
import app.winters.octo.device.phoneFiles
import app.winters.octo.device.phoneSoundAt
import app.winters.octo.device.shareType
import app.winters.octo.playback.QueueEditor
import app.winters.octo.playback.QueueUndo
import app.winters.octo.ui.common.Choice
import app.winters.octo.ui.common.ChoiceRequest
import app.winters.octo.ui.common.Feedback
import app.winters.octo.ui.common.LocalChoiceSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// What can be done with the files of songs on the phone: delete them,
// send them to another app, or make one the phone's ringtone. Songs with
// no file on the phone are left out of each.
interface PhoneFileActions {
    fun delete(trackIds: List<String>)
    fun share(trackIds: List<String>)
    fun setSound(trackId: String)
}

// What the screen is asked to show for a phone-file action.
sealed interface PhoneAsk {
    // Android's own confirmation, for deleting files Octo does not own.
    class Confirm(val sender: IntentSender) : PhoneAsk

    // Android 10 asks only whether Octo may change a file, not whether to
    // delete it, so Octo asks that itself first.
    class ConfirmHere(val plan: DeletePlan) : PhoneAsk

    // Another app's screen, such as the share sheet.
    class Open(val intent: Intent) : PhoneAsk

    // Android's switch for letting Octo change the phone's sounds.
    data object AllowSettings : PhoneAsk
}

// A delete under way: what goes, the queue as it was before the songs were
// taken out of it, and on Android 10 the files still to go.
private class Deleting(val plan: DeletePlan, val undo: QueueUndo?) {
    var left: List<String> = plan.uris
    var deleted = 0
}

@HiltViewModel
class PhoneFilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: SourceDao,
    private val library: DeviceLibrary,
    private val queue: QueueEditor,
    private val feedback: Feedback,
) : ViewModel() {
    private val _asks = Channel<PhoneAsk>(Channel.BUFFERED)
    val asks: Flow<PhoneAsk> = _asks.receiveAsFlow()

    private var deleting: Deleting? = null

    // A sound waiting for Android's switch to be turned on.
    private var waitingSound: Pair<String, PhoneSound>? = null

    private suspend fun filesOf(trackIds: List<String>): List<PhoneFile> =
        withContext(Dispatchers.IO) { phoneFiles(sources.copiesOf(trackIds.distinct())) }

    // ---- Deleting ----

    fun delete(trackIds: List<String>) {
        if (deleting != null) return
        viewModelScope.launch {
            val plan = deletePlan(trackIds, filesOf(trackIds)) ?: return@launch
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) begin(plan) else _asks.send(PhoneAsk.ConfirmHere(plan))
        }
    }

    // Takes the songs out of the queue first, so none of them plays while
    // its file goes, then asks Android to delete the files.
    fun begin(plan: DeletePlan) {
        if (deleting != null) return
        val run = Deleting(plan, queue.removeSongs(plan.trackIds.toSet()))
        deleting = run
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = try {
                MediaStore.createDeleteRequest(context.contentResolver, plan.uris.map { it.toUri() })
            } catch (e: IllegalArgumentException) {
                Log.w("Octo", "delete request failed: ${e.javaClass.simpleName}")
                finish()
                feedback.show("Could not delete from the phone")
                return
            }
            _asks.trySend(PhoneAsk.Confirm(request.intentSender))
        } else {
            viewModelScope.launch { deleteNext() }
        }
    }

    // Android's answer. From Android 11 the files are already gone once
    // it was yes. On Android 10 a yes lets Octo change that one file, so
    // it tries again and goes on to the next.
    fun deleteAnswered(confirmed: Boolean) {
        val run = deleting ?: return
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (confirmed) run.deleted = run.plan.uris.size
                finish()
            }
            confirmed -> viewModelScope.launch { deleteNext() }
            else -> finish()
        }
    }

    // Android 10: one file at a time, asking for each one Octo may not change.
    private suspend fun deleteNext() {
        val run = deleting ?: return
        while (run.left.isNotEmpty()) {
            val uri = run.left.first()
            try {
                withContext(Dispatchers.IO) { context.contentResolver.delete(uri.toUri(), null, null) }
            } catch (e: RecoverableSecurityException) {
                _asks.send(PhoneAsk.Confirm(e.userAction.actionIntent.intentSender))
                return
            } catch (e: SecurityException) {
                Log.w("Octo", "delete failed: ${e.javaClass.simpleName}")
                finish()
                feedback.show("Could not delete from the phone")
                return
            }
            run.left = run.left.drop(1)
            run.deleted++
        }
        finish()
    }

    // Once some files went, the phone is scanned again, so those songs
    // leave the library, or keep only a server's copy. When none went, the
    // queue is put back as it was.
    private fun finish() {
        val run = deleting ?: return
        deleting = null
        if (run.deleted > 0) library.filesChanged() else run.undo?.let(queue::undo)
    }

    // ---- Sharing ----

    fun share(trackIds: List<String>) {
        viewModelScope.launch {
            val files = filesToShare(trackIds, filesOf(trackIds))
            if (files.isNotEmpty()) _asks.send(PhoneAsk.Open(shareIntent(files)))
        }
    }

    // The files themselves, readable by the app they go to, through the
    // phone's own chooser.
    private fun shareIntent(files: List<PhoneFile>): Intent {
        val uris = files.map { it.uri.toUri() }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.type = shareType(files.map { it.mimeType })
        // The chooser passes the read grant on only for addresses in the clip.
        send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    // ---- The phone's sounds ----

    fun setSound(trackId: String, sound: PhoneSound) {
        viewModelScope.launch {
            val file = filesOf(listOf(trackId)).firstOrNull() ?: return@launch
            if (Settings.System.canWrite(context)) {
                applySound(file.uri, sound)
            } else {
                waitingSound = file.uri to sound
                _asks.send(PhoneAsk.AllowSettings)
            }
        }
    }

    // Back from Android's switch: sets the sound when it was turned on.
    fun settingsReturned() {
        val (uri, sound) = waitingSound ?: return
        waitingSound = null
        if (Settings.System.canWrite(context)) applySound(uri, sound) else feedback.show("Octo was not allowed to change sounds")
    }

    // Sets it, with an Undo that puts back the sound it replaced.
    private fun applySound(uri: String, sound: PhoneSound) {
        val before = runCatching { RingtoneManager.getActualDefaultRingtoneUri(context, sound.type) }.getOrNull()
        try {
            RingtoneManager.setActualDefaultRingtoneUri(context, sound.type, uri.toUri())
        } catch (e: RuntimeException) {
            Log.w("Octo", "setting a sound failed: ${e.javaClass.simpleName}")
            feedback.show("Could not change the ${sound.label.lowercase()}")
            return
        }
        feedback.undoable(sound.done) {
            runCatching { RingtoneManager.setActualDefaultRingtoneUri(context, sound.type, before) }
        }
    }
}

// The phone-file actions, bound to the screens Android shows for them:
// its delete confirmation, the share sheet, and the switch for sounds.
// Kept by the song menu's host, so it lives as long as the app's screen.
@Composable
fun rememberPhoneFiles(vm: PhoneFilesViewModel = hiltViewModel()): PhoneFileActions {
    val context = LocalContext.current
    val sheet = LocalChoiceSheet.current
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.deleteAnswered(result.resultCode == Activity.RESULT_OK)
    }
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.settingsReturned() }

    LaunchedEffect(vm) {
        vm.asks.collect { ask ->
            when (ask) {
                is PhoneAsk.Confirm -> confirm.launch(IntentSenderRequest.Builder(ask.sender).build())
                is PhoneAsk.ConfirmHere -> {
                    val songs = ask.plan.trackIds.size
                    sheet.show(
                        ChoiceRequest(
                            title = if (songs == 1) "Delete this song from the phone?" else "Delete $songs songs from the phone?",
                            choices = listOf(
                                Choice("Delete", if (songs == 1) "Its file is removed for good" else "Their files are removed for good"),
                                Choice("Cancel"),
                            ),
                            selected = -1,
                        ) { picked -> if (picked == 0) vm.begin(ask.plan) },
                    )
                }
                is PhoneAsk.Open -> try {
                    context.startActivity(ask.intent)
                } catch (e: ActivityNotFoundException) {
                    Log.w("Octo", "nothing to open: ${e.javaClass.simpleName}")
                }
                PhoneAsk.AllowSettings -> sheet.show(
                    ChoiceRequest(
                        title = "Let Octo change your sounds",
                        choices = listOf(
                            Choice("Open settings", "Android asks before an app sets a ringtone. Turn on the switch for Octo, then come back."),
                        ),
                        selected = -1,
                    ) {
                        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri())
                        try {
                            allow.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            Log.w("Octo", "no settings screen: ${e.javaClass.simpleName}")
                        }
                    },
                )
            }
        }
    }

    return remember(vm, sheet) {
        object : PhoneFileActions {
            override fun delete(trackIds: List<String>) = vm.delete(trackIds)
            override fun share(trackIds: List<String>) = vm.share(trackIds)
            override fun setSound(trackId: String) = sheet.show(
                ChoiceRequest("Set as", PhoneSound.entries.map { Choice(it.label) }, selected = -1) { picked ->
                    phoneSoundAt(picked)?.let { vm.setSound(trackId, it) }
                },
            )
        }
    }
}

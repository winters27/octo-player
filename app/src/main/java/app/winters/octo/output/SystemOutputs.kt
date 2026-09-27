package app.winters.octo.output

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.mediarouter.app.SystemOutputSwitcherDialogController

// Opens Android's own list of the phone's outputs (Bluetooth, a cable, the
// speaker), where one is picked for all of the phone's sound. Android 11
// and later have one; before that, Bluetooth settings are the nearest
// thing. Answers whether anything opened.
fun openSystemOutputs(context: Context): Boolean {
    if (SystemOutputSwitcherDialogController.showDialog(context)) return true
    return try {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

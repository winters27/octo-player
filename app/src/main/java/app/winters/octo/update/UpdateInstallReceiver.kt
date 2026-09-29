package app.winters.octo.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

// Hears from Android's installer how an update went. When Android wants
// the listener to confirm, its confirmation opens, but only for an install
// the listener asked for; one started by leaving Octo waits for the next tap.
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = EntryPointAccessors.fromApplication(context.applicationContext, UpdatesEntry::class.java).updates()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && intent.getBooleanExtra(EXTRA_ASKED, false)) {
            confirmation(intent)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        updates.installFinished(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    private fun confirmation(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UpdatesEntry {
        fun updates(): AppUpdates
    }

    companion object {
        const val EXTRA_ASKED = "app.winters.octo.update.ASKED"
    }
}

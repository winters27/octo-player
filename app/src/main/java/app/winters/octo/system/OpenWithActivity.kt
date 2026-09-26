package app.winters.octo.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import app.winters.octo.MainActivity

// Asks the main screen to play a file opened from another app.
const val ACTION_OPEN_FILE = "app.winters.octo.OPEN_FILE"

// Receives "Open with Octo" from a file manager or another app, and hands
// the file on to the main screen in Octo's own task. Opened straight into
// the other app's task, the main screen would run twice side by side. It
// shows nothing and closes at once.
class OpenWithActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri != null && savedInstanceState == null) {
            keepAccess(this, intent, uri)
            startActivity(openFileIntent(this, uri))
        }
        finish()
    }
}

// Passes the file on with the right to read it, which the main screen then
// holds for as long as it is open.
private fun openFileIntent(context: Context, uri: Uri): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_FILE)
        .setData(uri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)

package app.winters.octo.system

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import app.winters.octo.MainActivity
import app.winters.octo.ui.imports.sharedImport

// Receives "Share to Octo" for a list (a file TuneMyMusic saved, or text)
// and hands it on to the main screen in Octo's own task, which opens
// Import. Opened straight into the other app's task, the main screen would
// run twice side by side. It shows nothing and closes at once.
class ShareToImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val share = intent
        if (savedInstanceState == null && share?.sharedImport() != null) startActivity(handOn(share))
        finish()
    }

    // The same share, for the main screen, with the right to read its file
    // passed on.
    private fun handOn(share: Intent): Intent {
        val next = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType(share.type)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        share.extras?.let(next::putExtras)
        val stream = androidx.core.content.IntentCompat.getParcelableExtra(share, Intent.EXTRA_STREAM, android.net.Uri::class.java)
        if (stream != null) {
            next.clipData = ClipData.newRawUri(null, stream)
            next.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return next
    }
}

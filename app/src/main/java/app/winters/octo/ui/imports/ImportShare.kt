package app.winters.octo.ui.imports

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

// A list another app shared to Octo ("Share to Octo" on a file TuneMyMusic
// saved, or on copied text), which opens Import and is sent from there.

// What was shared: a file to read, or text that is the list itself. `F` is
// how the file is reached: a content address on the phone, a plain value in
// the tests.
sealed interface SharedImport<out F> {
    data class File<F>(val file: F, val type: String?) : SharedImport<F>

    data class Text(val text: String, val name: String?) : SharedImport<Nothing>
}

// The share action, as Intent.ACTION_SEND names it.
private const val SEND = "android.intent.action.SEND"

// The types the share target takes, as the manifest lists them.
val IMPORT_SHARE_TYPES = setOf("text/plain", "text/csv", "text/comma-separated-values")

// The types the file picker offers.
val IMPORT_PICK_TYPES = arrayOf("text/plain", "text/csv", "text/comma-separated-values", "application/zip", "application/json")

// What a share hands Import, or null when it is not one Import takes: a
// send of one of IMPORT_SHARE_TYPES, with a file or with some text. A file
// wins over text, since apps often add a line of text to a shared file.
fun <F : Any> sharedImportOf(action: String?, type: String?, file: F?, text: CharSequence?, subject: CharSequence?): SharedImport<F>? {
    if (action != SEND) return null
    val kind = type?.substringBefore(';')?.trim()?.lowercase()
    if (kind !in IMPORT_SHARE_TYPES) return null
    if (file != null) return SharedImport.File(file, kind)
    val words = text?.toString()?.takeIf(String::isNotBlank) ?: return null
    return SharedImport.Text(words, subject?.toString()?.trim()?.takeIf(String::isNotEmpty))
}

// The share in an intent, if it is one Import takes.
fun Intent.sharedImport(): SharedImport<Uri>? =
    sharedImportOf(
        action,
        type,
        IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java),
        getCharSequenceExtra(Intent.EXTRA_TEXT),
        getCharSequenceExtra(Intent.EXTRA_SUBJECT),
    )

// A name for a picked or shared file that the server reads by its ending:
// its own name when that has one Octo knows, else one from its type.
fun importFileName(shown: String?, type: String?): String {
    val name = shown?.trim()?.takeIf(String::isNotEmpty)
    if (name != null && isImportFileName(name)) return name
    val ending = when (type?.substringBefore(';')?.trim()?.lowercase()) {
        "text/csv", "text/comma-separated-values" -> "csv"
        "application/zip" -> "zip"
        "application/json" -> "json"
        else -> "txt"
    }
    return "${name ?: "Shared list"}.$ending"
}

// Shares waiting for Import, handed over once. The app's screens open
// Import when one arrives; Import takes it and sends it.
@Singleton
class ImportInbox @Inject constructor() {
    private val waiting = MutableStateFlow<SharedImport<Uri>?>(null)

    val pending: StateFlow<SharedImport<Uri>?> = waiting

    fun offer(share: SharedImport<Uri>) {
        waiting.value = share
    }

    // The share, once; null when it was taken already.
    fun take(): SharedImport<Uri>? = waiting.getAndUpdate { null }
}

// Whether a launch is a fresh one rather than an old intent handed over
// again from recents.
fun Intent.fromHistory(): Boolean = flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

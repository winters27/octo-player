package app.winters.octo.sound

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

// The largest correction file read; real ones are a few hundred bytes.
private const val MAX_FILE_BYTES = 256 * 1024

// Reading and writing equalizer files the listener picks, and finding the
// phone's own equalizer screen.
@Singleton
class SoundFiles @Inject constructor(@ApplicationContext private val context: Context) {
    // A headphone correction from a picked file, or nothing when the file
    // cannot be read or has no filters in it.
    suspend fun readCorrection(uri: Uri): HeadphoneCorrection? = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { String(it.readUpTo(MAX_FILE_BYTES), Charsets.UTF_8) }
                ?: return@runCatching null
            ParametricEqFile.correction(displayName(uri), text)
        }.getOrNull()
    }

    // Writes text to a picked file. False when it could not be written.
    suspend fun write(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
    }

    // The phone's own equalizer screen for an audio session, or nothing
    // when no app on the phone offers one.
    fun systemEqualizer(audioSession: Int): Intent? {
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSession)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        val pm = context.packageManager
        val handlers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return intent.takeIf { handlers.isNotEmpty() }
    }

    // The file's name as the file picker shows it.
    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
}

private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

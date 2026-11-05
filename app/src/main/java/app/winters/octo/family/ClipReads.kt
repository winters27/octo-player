package app.winters.octo.family

import android.content.SharedPreferences
import androidx.core.content.edit

// Whether a clip is worth reading for a family link: text, and not read
// before. `stamp` is when the clip was put on the clipboard (0 where the
// phone does not say, which is read as before); `last` the stamp read last.
internal fun clipDue(stamp: Long, text: Boolean, last: Long): Boolean = text && (stamp == 0L || stamp != last)

// Android says "pasted from your clipboard" each time an app reads it, so
// each clip is read at most once, across launches too: the clip's
// description, which reading does not announce, says when it was put there.
class ClipReads(private val prefs: SharedPreferences) {
    // Answers whether to read the clip now, noting it as read.
    fun due(stamp: Long, text: Boolean): Boolean {
        if (!clipDue(stamp, text, prefs.getLong(LAST, -1L))) return false
        prefs.edit { putLong(LAST, stamp) }
        return true
    }

    private companion object {
        const val LAST = "last_clip"
    }
}

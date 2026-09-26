package app.winters.octo.device

import android.media.RingtoneManager
import app.winters.octo.catalog.SourceTrackEntity

// A song's file on the phone: the library song it belongs to, its address
// in the phone's media library, and what kind of audio it is.
data class PhoneFile(val trackId: String, val uri: String, val mimeType: String?)

// The phone files among these copies. Only an address from the phone's own
// media library can be deleted, shared or rung through Android.
fun phoneFiles(copies: List<SourceTrackEntity>): List<PhoneFile> =
    copies
        .filter { it.sourceId == DEVICE && it.mergedId.isNotEmpty() && it.uri.orEmpty().startsWith("content://media/") }
        .map { PhoneFile(it.mergedId, it.uri.orEmpty(), it.mimeType) }
        .distinctBy { it.uri }

// What deleting songs from the phone takes: every phone file each one has,
// so the song leaves the phone completely. `trackIds` are the songs that
// have a file to delete, in the order asked.
data class DeletePlan(val trackIds: List<String>, val uris: List<String>)

// Nothing to do when none of the songs has a file on the phone.
fun deletePlan(trackIds: List<String>, files: List<PhoneFile>): DeletePlan? {
    val bySong = files.groupBy { it.trackId }
    val songs = trackIds.distinct().filter { it in bySong }
    if (songs.isEmpty()) return null
    return DeletePlan(songs, songs.flatMap { song -> bySong.getValue(song).map { it.uri } })
}

// One file per song for sharing, in the order asked.
fun filesToShare(trackIds: List<String>, files: List<PhoneFile>): List<PhoneFile> {
    val bySong = files.groupBy { it.trackId }
    return trackIds.distinct().mapNotNull { bySong[it]?.firstOrNull() }
}

// The type a share names: the files' own when they all agree, any audio
// when they are all audio of different kinds, and anything otherwise.
fun shareType(mimeTypes: List<String?>): String {
    val known = mimeTypes.map { it?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
    val one = known.distinct().singleOrNull()
    return when {
        one != null -> one
        known.all { it == null || it.startsWith("audio/") } -> "audio/*"
        else -> "*/*"
    }
}

// The phone's sounds a song can become, in the order offered: its kind as
// Android knows it, its name, and the line that confirms it was set.
enum class PhoneSound(val type: Int, val label: String, val done: String) {
    Ringtone(RingtoneManager.TYPE_RINGTONE, "Ringtone", "Set as your ringtone"),
    Notification(RingtoneManager.TYPE_NOTIFICATION, "Notification sound", "Set as your notification sound"),
    Alarm(RingtoneManager.TYPE_ALARM, "Alarm", "Set as your alarm"),
}

// The sound picked from the sheet, by its place in the list.
fun phoneSoundAt(index: Int): PhoneSound? = PhoneSound.entries.getOrNull(index)

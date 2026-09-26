package app.winters.octo.offline

import java.security.MessageDigest

// Longest a name's readable part may be, so the whole name stays well under
// what file systems allow.
private const val NAME_CHARS = 80

// The file a downloaded song is saved as: "Artist - Title [a1b2c3d4].flac".
// Characters no file system takes are replaced, and the part in brackets
// comes from the server and the song's id there, so two songs never share
// a name even when their titles match.
fun downloadFileName(artist: String, title: String, sourceId: String, serverId: String, format: String?): String {
    val readable = listOf(artist, title).map(::safePart).filter { it.isNotEmpty() }.joinToString(" - ").ifEmpty { "Song" }
    val stem = readable.take(NAME_CHARS).trimEnd(' ', '.', '-')
    return "$stem [${shortHash("$sourceId|$serverId")}].${extensionFor(format)}"
}

// The file ending for a type of audio.
fun extensionFor(mimeType: String?): String = when (mimeType?.lowercase()?.substringBefore(';')?.trim()) {
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/alac" -> "m4a"
    "audio/aac", "audio/aacp" -> "aac"
    "audio/ogg", "audio/vorbis" -> "ogg"
    "audio/opus" -> "opus"
    "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
    "audio/aiff", "audio/x-aiff" -> "aiff"
    "audio/ape", "audio/x-ape" -> "ape"
    "audio/wavpack", "audio/x-wavpack" -> "wv"
    "audio/x-ms-wma" -> "wma"
    "audio/webm" -> "webm"
    else -> "audio"
}

// Leaves out what a file name cannot hold, and tidies the spaces.
private fun safePart(text: String): String =
    text.map { c -> if (c.isISOControl() || c in "\\/:*?\"<>|") ' ' else c }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')

private fun shortHash(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(4).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }

package app.winters.octo.lyrics

// The tag names lyrics are kept under, as the tag library names them: MP3
// USLT frames read as LYRICS (or LYRICS:<description>), MP4 and FLAC
// LYRICS, and some taggers write UNSYNCEDLYRICS. Synced SYLT frames are not
// read by the tag library at all, but most taggers also write LRC text into
// USLT, which is.
private val lyricsTags = listOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS")

// The best lyrics text among a file's tags: timed (LRC) text before plain,
// then in the order above. Null when the file has none.
fun songFileLyrics(tags: Map<String, Array<String>>): String? {
    val found = tags.entries
        .filter { (key, _) ->
            val name = key.uppercase()
            name in lyricsTags || name.startsWith("LYRICS:")
        }
        .sortedBy { (key, _) -> lyricsTags.indexOf(key.uppercase().substringBefore(':')).let { if (it < 0) lyricsTags.size else it } }
        .flatMap { it.value.asList() }
        .filter(String::isNotBlank)
    return found.firstOrNull { looksTimed(it) } ?: found.firstOrNull()
}

private val timedStamp = Regex("""(?m)^\s*\[\d{1,3}:\d{1,2}""")

// Whether text has LRC line stamps.
fun looksTimed(text: String): Boolean = timedStamp.containsMatchIn(text)

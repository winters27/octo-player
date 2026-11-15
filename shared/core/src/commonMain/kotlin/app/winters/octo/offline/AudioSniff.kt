package app.winters.octo.offline

// The type of audio a file holds, from its first bytes: what a download is
// named and labeled by, whatever the server said or was asked. An ID3 tag
// at the start is passed over first. Null when the bytes name no type this
// knows, or when a tag at the start is longer than the bytes given.
fun sniffAudio(head: ByteArray, count: Int): String? {
    val n = count.coerceIn(0, head.size)
    fun at(i: Int): Int = if (i < n) head[i].toInt() and 0xFF else -1
    fun text(i: Int, word: String): Boolean = i + word.length <= n && word.indices.all { at(i + it) == word[it].code }
    var start = 0
    if (text(0, "ID3") && n >= 10) {
        val size = ((at(6) and 0x7F) shl 21) or ((at(7) and 0x7F) shl 14) or ((at(8) and 0x7F) shl 7) or (at(9) and 0x7F)
        val footer = if (at(5) and 0x10 != 0) 10 else 0
        start = 10 + size + footer
        if (start + 4 > n) return null
    }
    return when {
        text(start, "fLaC") -> "audio/flac"
        text(start, "OggS") -> oggType(head, n, start)
        text(start, "RIFF") && text(start + 8, "WAVE") -> "audio/wav"
        text(start + 4, "ftyp") -> "audio/mp4"
        at(start) == 0x1A && at(start + 1) == 0x45 && at(start + 2) == 0xDF && at(start + 3) == 0xA3 -> "audio/webm"
        text(start, "caff") -> "audio/x-caf"
        at(start) == 0xFF && at(start + 1) >= 0 && (at(start + 1) and 0xE0) == 0xE0 -> frameType(at(start + 1), at(start + 2))
        else -> null
    }
}

// An Ogg stream's type from its first page's first packet: Opus when it is
// an OpusHead, Ogg otherwise (Vorbis, or a codec only the bytes would say).
private fun oggType(head: ByteArray, n: Int, page: Int): String {
    val segmentsAt = page + 26
    if (segmentsAt >= n) return "audio/ogg"
    val packet = page + 27 + (head[segmentsAt].toInt() and 0xFF)
    val opus = "OpusHead"
    val isOpus = packet + opus.length <= n && opus.indices.all { (head[packet + it].toInt() and 0xFF) == opus[it].code }
    return if (isOpus) "audio/opus" else "audio/ogg"
}

// An MPEG audio sync word's type: ADTS AAC when its layer bits are 0 (a
// syncword of FFF with layer 00, as `FF F1` and `FF F9`), an MP3 or other
// MPEG layer when they are not, and only when its version, bitrate and
// sample rate fields are ones a real frame has. Null otherwise.
private fun frameType(second: Int, third: Int): String? {
    if (third < 0) return null
    val layer = (second shr 1) and 0x3
    if (layer == 0) return if ((second and 0xF6) == 0xF0) "audio/aac" else null
    val version = (second shr 3) and 0x3
    val bitrate = (third shr 4) and 0xF
    val rate = (third shr 2) and 0x3
    return if (version != 1 && bitrate != 0xF && rate != 3) "audio/mpeg" else null
}

// Whether two types are the same kind of file, parameters aside: the one a
// listing names and the one the bytes say. Ogg and Opus are one kind (Ogg
// is the container Opus files come in), as are the names of MP4 and of WAV.
fun sameAudioKind(a: String?, b: String?): Boolean {
    val x = audioKindOf(a) ?: return true
    val y = audioKindOf(b) ?: return true
    return x == y
}

private fun audioKindOf(type: String?): String? = when (val plain = type?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) {
    null, "application/octet-stream" -> null
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/alac", "audio/mp4a-latm" -> "mp4"
    "audio/aac", "audio/aacp", "audio/x-aac" -> "aac"
    "audio/ogg", "audio/opus", "audio/vorbis", "application/ogg" -> "ogg"
    "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
    "audio/webm" -> "webm"
    "audio/x-caf" -> "caf"
    else -> plain
}

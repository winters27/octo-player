package app.winters.octo.desktop.lyrics

import java.io.InputStream
import java.nio.charset.Charset

// Reads the tags at the start of a song file, as they stream in, for the
// lyrics kept inside it: ID3v2 (MP3, and some WAV and AIFF files) and FLAC's
// Vorbis comments. It stops as soon as the tags end, so only the head of
// the file is ever fetched. Answers tag names in capitals with their
// values, as the shared songFileLyrics reads them: an ID3 USLT frame as
// LYRICS (or LYRICS:<description>), a TXXX frame by its description.
object SongTags {
    // Tags are never read past this, whatever a damaged file says.
    const val MOST_BYTES = 16 * 1024 * 1024

    fun read(input: InputStream): Map<String, Array<String>> {
        val head = input.readExactly(4) ?: return emptyMap()
        return when {
            head.startsWith("ID3") -> id3(head, input)
            head.startsWith("fLaC") -> flac(input)
            else -> emptyMap()
        }
    }

    // ---- ID3v2 ----

    private fun id3(head: ByteArray, input: InputStream): Map<String, Array<String>> {
        val rest = input.readExactly(6) ?: return emptyMap()
        val version = head[3].toInt()
        if (version !in 2..4) return emptyMap()
        val flags = rest[1].toInt()
        val size = syncSafe(rest, 2)
        if (size <= 0 || size > MOST_BYTES) return emptyMap()
        var body = input.readExactly(size) ?: return emptyMap()
        // The whole tag unsynchronised (a 0 after every 0xFF), in 2.2 and 2.3.
        if (flags and 0x80 != 0 && version < 4) body = resync(body)
        var at = 0
        // An extended header, skipped.
        if (flags and 0x40 != 0 && version >= 3) {
            val extended = if (version == 4) syncSafe(body, 0) else int(body, 0) + 4
            at += extended
        }
        val found = LinkedHashMap<String, MutableList<String>>()
        val idLength = if (version == 2) 3 else 4
        val headerLength = if (version == 2) 6 else 10
        while (at + headerLength <= body.size) {
            val id = String(body, at, idLength, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val frameSize = when (version) {
                2 -> ((body[at + 3].toInt() and 0xFF) shl 16) or ((body[at + 4].toInt() and 0xFF) shl 8) or (body[at + 5].toInt() and 0xFF)
                3 -> int(body, at + 4)
                else -> syncSafe(body, at + 4)
            }
            val start = at + headerLength
            if (frameSize <= 0 || start + frameSize > body.size) break
            val frame = body.copyOfRange(start, start + frameSize)
            if (version == 4 && (body[at + 9].toInt() and 0x02) != 0) {
                // This frame alone is unsynchronised.
                frameTag(id, resync(frame))?.let { (k, v) -> found.getOrPut(k) { mutableListOf() } += v }
            } else {
                frameTag(id, frame)?.let { (k, v) -> found.getOrPut(k) { mutableListOf() } += v }
            }
            at = start + frameSize
        }
        return found.mapValues { it.value.toTypedArray() }
    }

    // A lyrics frame's tag name and text, or null for any other frame.
    private fun frameTag(id: String, frame: ByteArray): Pair<String, String>? {
        if (frame.isEmpty()) return null
        val encoding = frame[0].toInt()
        return when (id) {
            "USLT", "ULT" -> {
                if (frame.size < 4) return null
                // After the encoding, a three letter language, then a
                // description and the words, each in that encoding.
                val (description, textStart) = terminated(frame, 4, encoding)
                val text = decode(frame, textStart, frame.size, encoding)
                (if (description.isBlank()) "LYRICS" else "LYRICS:${description.uppercase()}") to text
            }
            "TXXX", "TXX" -> {
                val (description, valueStart) = terminated(frame, 1, encoding)
                val name = description.trim().uppercase()
                if (name.isEmpty()) null else name to decode(frame, valueStart, frame.size, encoding)
            }
            else -> null
        }
    }

    // A string ended by the encoding's terminator, and where the next begins.
    private fun terminated(bytes: ByteArray, from: Int, encoding: Int): Pair<String, Int> {
        val wide = encoding == 1 || encoding == 2
        var i = from
        if (wide) {
            while (i + 1 < bytes.size && !(bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0)) i += 2
            return decode(bytes, from, i.coerceAtMost(bytes.size), encoding) to (i + 2).coerceAtMost(bytes.size)
        }
        while (i < bytes.size && bytes[i].toInt() != 0) i++
        return decode(bytes, from, i, encoding) to (i + 1).coerceAtMost(bytes.size)
    }

    private fun decode(bytes: ByteArray, from: Int, to: Int, encoding: Int): String {
        if (to <= from) return ""
        val charset: Charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(bytes, from, to - from, charset).trimEnd('\u0000')
    }

    // Takes out the 0 written after every 0xFF.
    private fun resync(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            out.write(bytes[i].toInt())
            if ((bytes[i].toInt() and 0xFF) == 0xFF && i + 1 < bytes.size && bytes[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    // ---- FLAC ----

    private fun flac(input: InputStream): Map<String, Array<String>> {
        var read = 4
        while (read < MOST_BYTES) {
            val header = input.readExactly(4) ?: return emptyMap()
            read += 4
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (type == VORBIS_COMMENT) {
                val block = input.readExactly(length) ?: return emptyMap()
                return vorbisComments(block)
            }
            if (!input.skipExactly(length)) return emptyMap()
            read += length
            if (last) break
        }
        return emptyMap()
    }

    private const val VORBIS_COMMENT = 4

    // Vorbis comments: a vendor string, then NAME=value pairs, all little
    // endian lengths and UTF-8.
    private fun vorbisComments(block: ByteArray): Map<String, Array<String>> {
        var at = 0
        fun length(): Int = littleInt(block, at).also { at += 4 }
        if (block.size < 8) return emptyMap()
        val vendor = length()
        at += vendor
        if (at + 4 > block.size) return emptyMap()
        val count = length()
        val found = LinkedHashMap<String, MutableList<String>>()
        repeat(count) {
            if (at + 4 > block.size) return@repeat
            val size = length()
            if (size < 0 || at + size > block.size) return@repeat
            val entry = String(block, at, size, Charsets.UTF_8)
            at += size
            val equals = entry.indexOf('=')
            if (equals > 0) found.getOrPut(entry.substring(0, equals).uppercase()) { mutableListOf() } += entry.substring(equals + 1)
        }
        return found.mapValues { it.value.toTypedArray() }
    }

    // ---- Bytes ----

    private fun ByteArray.startsWith(text: String) = size >= text.length && text.indices.all { this[it] == text[it].code.toByte() }

    private fun syncSafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun int(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun littleInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun InputStream.readExactly(count: Int): ByteArray? {
        if (count < 0 || count > MOST_BYTES) return null
        val out = ByteArray(count)
        var got = 0
        while (got < count) {
            val n = read(out, got, count - got)
            if (n < 0) return null
            got += n
        }
        return out
    }

    private fun InputStream.skipExactly(count: Int): Boolean {
        var left = count.toLong()
        val scratch = ByteArray(8192)
        while (left > 0) {
            val n = read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n < 0) return false
            left -= n
        }
        return true
    }
}

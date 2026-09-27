package app.winters.octo.desktop.lyrics

import app.winters.octo.lyrics.songFileLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Lyrics read from the head of a song file as it streams in.
class SongTagsTest {
    private val lrc = "[00:01.00]First line\n[00:04.50]Second line"

    // An ID3v2 tag of these frames, then some audio.
    private fun id3(version: Int, frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, data) ->
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(if (version == 4) syncSafe(data.size) else ByteBuffer.allocate(4).putInt(data.size).array())
            body.write(byteArrayOf(0, 0))
            body.write(data)
        }
        val tag = body.toByteArray()
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), 0, 0) + syncSafe(tag.size) + tag + ByteArray(64) { 0x55 }
    }

    private fun syncSafe(n: Int) = byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())

    // A USLT frame: encoding, language, description, then the words.
    private fun uslt(encoding: Int, description: String, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encoding)
        out.write("eng".toByteArray())
        when (encoding) {
            1 -> {
                out.write(description.toByteArray(Charsets.UTF_16))
                out.write(byteArrayOf(0, 0))
                out.write(text.toByteArray(Charsets.UTF_16))
            }
            else -> {
                out.write(description.toByteArray(Charsets.UTF_8))
                out.write(0)
                out.write(text.toByteArray(Charsets.UTF_8))
            }
        }
        return out.toByteArray()
    }

    private fun txxx(name: String, value: String) = byteArrayOf(3) + name.toByteArray() + byteArrayOf(0) + value.toByteArray()

    // Throws if anything past `limit` bytes is read: the tags must be all
    // that is fetched.
    private class Limited(private val bytes: ByteArray, private val limit: Int) : InputStream() {
        private var at = 0

        override fun read(): Int {
            check(at < limit) { "read past the tags" }
            return if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            check(at < limit) { "read past the tags" }
            if (at >= bytes.size) return -1
            val n = minOf(len, bytes.size - at, limit - at)
            System.arraycopy(bytes, at, b, off, n)
            at += n
            return n
        }
    }

    @Test
    fun readsId3v23LyricsInUtf16() {
        val file = id3(3, listOf("TIT2" to byteArrayOf(0) + "Song".toByteArray(), "USLT" to uslt(1, "", lrc)))
        val tags = SongTags.read(Limited(file, file.size - 64))
        assertEquals(lrc, tags["LYRICS"]?.single())
        assertEquals(lrc, songFileLyrics(tags))
    }

    @Test
    fun readsId3v24LyricsAndTheirDescriptionsInUtf8() {
        val file = id3(4, listOf("USLT" to uslt(3, "Clean", "plain words"), "TXXX" to txxx("SYNCEDLYRICS", lrc)))
        val tags = SongTags.read(Limited(file, file.size - 64))
        assertEquals("plain words", tags["LYRICS:CLEAN"]?.single())
        assertEquals(lrc, tags["SYNCEDLYRICS"]?.single())
        // Timed text wins over plain.
        assertEquals(lrc, songFileLyrics(tags))
    }

    @Test
    fun readsFlacCommentsPastThePicture() {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        // STREAMINFO, 34 bytes.
        out.write(byteArrayOf(0, 0, 0, 34))
        out.write(ByteArray(34))
        // A picture of 3000 bytes.
        out.write(byteArrayOf(6, 0, (3000 shr 8).toByte(), (3000 and 0xFF).toByte()))
        out.write(ByteArray(3000))
        // The comments, last.
        val comments = ByteArrayOutputStream()
        fun le(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
        val vendor = "encoder".toByteArray()
        comments.write(le(vendor.size))
        comments.write(vendor)
        val entries = listOf("TITLE=Song", "LYRICS=$lrc")
        comments.write(le(entries.size))
        entries.forEach {
            val e = it.toByteArray(Charsets.UTF_8)
            comments.write(le(e.size))
            comments.write(e)
        }
        val block = comments.toByteArray()
        out.write(byteArrayOf((0x80 or 4).toByte(), 0, (block.size shr 8).toByte(), (block.size and 0xFF).toByte()))
        out.write(block)
        val tagsEnd = out.size()
        out.write(ByteArray(100) { 0x33 })
        val file = out.toByteArray()
        val tags = SongTags.read(Limited(file, tagsEnd))
        assertEquals(lrc, tags["LYRICS"]?.single())
        assertEquals("Song", tags["TITLE"]?.single())
    }

    @Test
    fun anythingElseHasNoTags() {
        assertTrue(SongTags.read("OggS and more".byteInputStream()).isEmpty())
        assertTrue(SongTags.read("ID".byteInputStream()).isEmpty())
        // A tag that says it is larger than anything sensible.
        val huge = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F)
        assertTrue(SongTags.read(huge.inputStream()).isEmpty())
    }
}

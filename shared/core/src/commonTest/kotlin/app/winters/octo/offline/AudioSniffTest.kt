package app.winters.octo.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Each kind of audio file is told by its first bytes, after any ID3 tag.
class AudioSniffTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun sniff(head: ByteArray) = sniffAudio(head, head.size)

    // An Ogg page with one packet that starts with `packet`.
    private fun oggPage(packet: ByteArray): ByteArray {
        val header = ascii("OggS") + bytes(0, 2) + ByteArray(8) + ByteArray(4) + ByteArray(4) + ByteArray(4) + bytes(1, packet.size)
        return header + packet
    }

    // An ID3v2 tag of `body` bytes of zero, its size in four seven-bit bytes.
    private fun id3(body: Int): ByteArray {
        val size = bytes((body shr 21) and 0x7F, (body shr 14) and 0x7F, (body shr 7) and 0x7F, body and 0x7F)
        return ascii("ID3") + bytes(4, 0, 0) + size + ByteArray(body)
    }

    @Test
    fun oggOpusAndOggVorbisAreToldApart() {
        assertEquals("audio/opus", sniff(oggPage(ascii("OpusHead") + bytes(1, 2))))
        assertEquals("audio/ogg", sniff(oggPage(bytes(1) + ascii("vorbis") + bytes(0, 0))))
    }

    @Test
    fun adtsIsNotAnMp3() {
        assertEquals("audio/aac", sniff(bytes(0xFF, 0xF1, 0x50, 0x80)))
        assertEquals("audio/aac", sniff(bytes(0xFF, 0xF9, 0x50, 0x80)))
        // MPEG-1 Layer III at 128 kbps, 44.1 kHz.
        assertEquals("audio/mpeg", sniff(bytes(0xFF, 0xFB, 0x90, 0x64)))
        // MPEG-2 Layer III: layer bits 01, version bits 10.
        assertEquals("audio/mpeg", sniff(bytes(0xFF, 0xF3, 0x90, 0x64)))
        // A sync word with a reserved version or a bad bitrate is no frame.
        assertNull(sniff(bytes(0xFF, 0xEB, 0x90, 0x64)))
        assertNull(sniff(bytes(0xFF, 0xFB, 0xF0, 0x64)))
    }

    @Test
    fun anId3TagIsPassedOver() {
        assertEquals("audio/mpeg", sniff(id3(20) + bytes(0xFF, 0xFB, 0x90, 0x64)))
        assertEquals("audio/flac", sniff(id3(30) + ascii("fLaC") + ByteArray(4)))
        // A tag longer than the bytes read names nothing yet.
        assertNull(sniffAudio(id3(500) + ascii("fLaC"), 64))
    }

    @Test
    fun containersAreKnownByTheirMarks() {
        assertEquals("audio/flac", sniff(ascii("fLaC") + ByteArray(8)))
        assertEquals("audio/mp4", sniff(bytes(0, 0, 0, 0x20) + ascii("ftypM4A ")))
        assertEquals("audio/wav", sniff(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WAVEfmt ")))
        assertEquals("audio/webm", sniff(bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F)))
        assertEquals("audio/x-caf", sniff(ascii("caff") + bytes(0, 1, 0, 0)))
    }

    @Test
    fun junkNamesNothing() {
        assertNull(sniff(ascii("<!DOCTYPE html>")))
        assertNull(sniff(ByteArray(0)))
        assertNull(sniff(ByteArray(64)))
    }

    @Test
    fun aListingAndItsBytesAreTheSameKindAcrossNames() {
        assertTrue(sameAudioKind("audio/ogg", "audio/opus"))
        assertTrue(sameAudioKind("audio/x-flac", "audio/flac"))
        assertTrue(sameAudioKind(null, "audio/mpeg"))
        assertTrue(!sameAudioKind("audio/flac", "audio/mpeg"))
        assertTrue(!sameAudioKind("audio/mp4", "audio/aac"))
    }
}

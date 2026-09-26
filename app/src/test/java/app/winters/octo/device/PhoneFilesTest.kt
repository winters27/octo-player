package app.winters.octo.device

import app.winters.octo.catalog.SourceTrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneFilesTest {
    private fun copy(id: String, source: String, song: String, uri: String?, mime: String? = "audio/mpeg") = SourceTrackEntity(
        id = id, sourceId = source, nativeId = id, title = "Midnight City", searchKey = "", sortKey = "",
        artist = "M83", artistId = "", album = "Hurry Up, We're Dreaming", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = 244_000, addedAt = 0, mimeType = mime, sizeBytes = null, artwork = null, uri = uri,
        albumOrder = 0, relinkKey = "", genre = "", mergedId = song,
    )

    private val phoneA = copy("device:1", DEVICE, "a", "content://media/external/audio/media/1")
    private val phoneA2 = copy("device:2", DEVICE, "a", "content://media/external/audio/media/2", "audio/flac")
    private val serverA = copy("server:x:9", "server:x", "a", "https://music.example/rest/stream?id=9")
    private val phoneB = copy("device:3", DEVICE, "b", "content://media/external/audio/media/3", "audio/flac")
    private val serverC = copy("server:x:10", "server:x", "c", null)

    @Test
    fun onlyThePhonesOwnFilesCount() {
        val files = phoneFiles(listOf(phoneA, serverA, phoneB, serverC))
        assertEquals(listOf("content://media/external/audio/media/1", "content://media/external/audio/media/3"), files.map { it.uri })
        assertEquals(listOf("a", "b"), files.map { it.trackId })
        // A phone row not yet merged into a song, or with no media address, is left out.
        assertEquals(emptyList<PhoneFile>(), phoneFiles(listOf(copy("device:4", DEVICE, "", "content://media/external/audio/media/4"))))
        assertEquals(emptyList<PhoneFile>(), phoneFiles(listOf(copy("device:5", DEVICE, "e", "file:///sdcard/Music/e.mp3"))))
    }

    @Test
    fun deletingTakesEveryPhoneFileOfEachSong() {
        val files = phoneFiles(listOf(phoneB, phoneA, serverA, phoneA2))
        val plan = deletePlan(listOf("a", "b"), files)
        assertEquals(listOf("a", "b"), plan?.trackIds)
        assertEquals(
            listOf(
                "content://media/external/audio/media/1",
                "content://media/external/audio/media/2",
                "content://media/external/audio/media/3",
            ),
            plan?.uris,
        )
    }

    @Test
    fun aSongOnlyOnAServerIsNeverDeleted() {
        val files = phoneFiles(listOf(serverA, serverC, phoneB))
        assertNull(deletePlan(listOf("c"), files))
        // Picked with a phone song, it is simply left out.
        assertEquals(listOf("b"), deletePlan(listOf("c", "b", "b"), files)?.trackIds)
    }

    @Test
    fun sharingSendsOneFilePerSongInTheOrderPicked() {
        val files = phoneFiles(listOf(phoneA, phoneA2, phoneB))
        assertEquals(
            listOf("content://media/external/audio/media/3", "content://media/external/audio/media/1"),
            filesToShare(listOf("b", "a", "c"), files).map { it.uri },
        )
    }

    @Test
    fun theShareNamesTheFilesType() {
        assertEquals("audio/mpeg", shareType(listOf("audio/mpeg")))
        assertEquals("audio/flac", shareType(listOf("audio/flac", "AUDIO/FLAC")))
        assertEquals("audio/*", shareType(listOf("audio/flac", "audio/mpeg")))
        assertEquals("audio/*", shareType(listOf(null)))
        assertEquals("audio/*", shareType(listOf("audio/flac", null)))
        assertEquals("*/*", shareType(listOf("audio/mpeg", "application/ogg")))
    }

    @Test
    fun eachSoundChoiceSetsItsOwnKind() {
        // Android's kinds: ringtone 1, notification 2, alarm 4.
        assertEquals(listOf(1, 2, 4), PhoneSound.entries.map { it.type })
        assertEquals(listOf("Ringtone", "Notification sound", "Alarm"), PhoneSound.entries.map { it.label })
        assertEquals(PhoneSound.Ringtone, phoneSoundAt(0))
        assertEquals(PhoneSound.Notification, phoneSoundAt(1))
        assertEquals(PhoneSound.Alarm, phoneSoundAt(2))
        assertNull(phoneSoundAt(3))
        assertNull(phoneSoundAt(-1))
        assertEquals("Set as your ringtone", PhoneSound.Ringtone.done)
        assertEquals("Set as your notification sound", PhoneSound.Notification.done)
        assertEquals("Set as your alarm", PhoneSound.Alarm.done)
    }
}

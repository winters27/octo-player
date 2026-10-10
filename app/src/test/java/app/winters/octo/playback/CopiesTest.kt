package app.winters.octo.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import app.winters.octo.catalog.SourceTrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CopiesTest {
    private fun copy(
        id: String,
        source: String,
        mime: String?,
        bitrate: Int? = null,
        sampleRate: Int? = null,
        bitDepth: Int? = null,
        size: Long? = null,
        ms: Long = 240_000,
    ) = SourceTrackEntity(
        id = id, sourceId = source, nativeId = id, title = "Hotline Bling", searchKey = "", sortKey = "",
        artist = "Drake", artistId = "", album = "Views", albumId = "", trackNo = null, discNo = null, year = null,
        durationMs = ms, addedAt = 0, mimeType = mime, sizeBytes = size, artwork = null, uri = "content://media/$id",
        albumOrder = 0, relinkKey = "", genre = "", bitrate = bitrate, sampleRate = sampleRate, bitDepth = bitDepth,
    )

    // A 4-minute phone MP3 of 7.68 MB works out to 256 kbps.
    private val phoneMp3 = copy("p", "device", "audio/mpeg", size = 7_680_000)
    private val phoneFlac = copy("p", "device", "audio/flac", size = 30_000_000)
    private val serverFlac = copy("s", "server:music.example", "audio/flac", bitrate = 900, sampleRate = 44_100, bitDepth = 16)
    private val serverMp3 = copy("s", "server:music.example", "audio/mpeg", bitrate = 320)

    @Test
    fun phoneFirstPlaysThePhoneCopy() {
        val picked = chooseCopy(listOf(serverFlac, phoneMp3), CopyPreference.PhoneFirst, serverReachable = true)
        assertEquals("p", picked?.id)
    }

    @Test
    fun phoneFirstStreamsWhenThePhoneFileIsGone() {
        val picked = chooseCopy(listOf(phoneMp3, serverFlac), CopyPreference.PhoneFirst, serverReachable = true) { it.id == "p" }
        assertEquals("s", picked?.id)
    }

    @Test
    fun bestQualityStreamsTheBetterServerCopy() {
        val picked = chooseCopy(listOf(phoneMp3, serverFlac), CopyPreference.BestQuality, serverReachable = true)
        assertEquals("s", picked?.id)
    }

    @Test
    fun bestQualityPlaysThePhoneCopyWhenTheServerIsOutOfReach() {
        val picked = chooseCopy(listOf(phoneMp3, serverFlac), CopyPreference.BestQuality, serverReachable = false)
        assertEquals("p", picked?.id)
    }

    @Test
    fun bestQualityKeepsThePhoneCopyWhenItIsBetter() {
        val picked = chooseCopy(listOf(serverMp3, phoneFlac), CopyPreference.BestQuality, serverReachable = true)
        assertEquals("p", picked?.id)
    }

    @Test
    fun aTieGoesToThePhone() {
        val server = copy("s", "server:music.example", "audio/mpeg", bitrate = 256)
        val picked = chooseCopy(listOf(server, phoneMp3), CopyPreference.BestQuality, serverReachable = true)
        assertEquals("p", picked?.id)
    }

    @Test
    fun aServerOnlySongIsStillHandedOverWhenOffline() {
        // So it fails at once and is skipped.
        val picked = chooseCopy(listOf(serverFlac), CopyPreference.PhoneFirst, serverReachable = false)
        assertEquals("s", picked?.id)
    }

    @Test
    fun noCopiesMeansNothingToPlay() {
        assertNull(chooseCopy(emptyList(), CopyPreference.BestQuality, serverReachable = true))
    }

    @Test
    fun losslessBeatsAHigherBitrate() {
        val lossy = copy("a", "device", "audio/mpeg", bitrate = 1_411_000)
        assertEquals(1, compareQuality(serverFlac, lossy))
    }

    @Test
    fun higherSampleRateThenDeeperBitsWin() {
        val hiRes = copy("h", "server:x", "audio/flac", bitrate = 900, sampleRate = 96_000, bitDepth = 16)
        val deep = copy("d", "server:x", "audio/flac", bitrate = 900, sampleRate = 44_100, bitDepth = 24)
        assertEquals(1, compareQuality(hiRes, serverFlac))
        assertEquals(1, compareQuality(deep, serverFlac))
        assertEquals(1, compareQuality(hiRes, deep))
    }

    @Test
    fun unknownFactsAreSkippedForTheBitrate() {
        // The phone knows no sample rate, so the estimated bitrate decides.
        val bigPhoneFlac = copy("p", "device", "audio/flac", size = 90_000_000)
        assertEquals(1, compareQuality(bigPhoneFlac, serverFlac))
    }

    @Test
    fun bitrateIsEstimatedFromSizeAndLength() {
        assertEquals(256_000, bitrateOf(phoneMp3))
        assertNull(bitrateOf(copy("x", "device", "audio/mpeg")))
    }

    @Test
    fun serverBitratesInKilobitsAreReadAsSuch() {
        assertEquals(320_000, bitrateOf(serverMp3))
        assertEquals(320_000, bitrateOf(copy("x", "server:x", "audio/mpeg", bitrate = 320_000)))
    }

    @Test
    fun originalAsksForTheFileAsItIs() {
        val request = streamRequest("audio/flac", 900_000, StreamQuality.Original)
        assertEquals(mapOf("format" to "raw"), request.params)
        assertEquals("audio/flac", request.mimeType("audio/flac"))
    }

    @Test
    fun aSmallerSizeMakesAnMp3() {
        val request = streamRequest("audio/flac", 900_000, StreamQuality.Kbps192)
        assertEquals(mapOf("format" to "mp3", "maxBitRate" to "192", "estimateContentLength" to "true"), request.params)
        assertEquals("audio/mpeg", request.mimeType("audio/flac"))
    }

    @Test
    fun aLossyFileWithinTheSizePlaysAsItIs() {
        assertEquals(StreamRequest(null), streamRequest("audio/mp4", 128_000, StreamQuality.Kbps192))
        assertEquals(StreamRequest(192), streamRequest("audio/mp4", 256_000, StreamQuality.Kbps192))
        assertEquals(StreamRequest(192), streamRequest("audio/mpeg", null, StreamQuality.Kbps192))
    }

    @Test
    fun theBadgeReadsTheTranscodedStream() {
        val mime = streamRequest("audio/flac", 900_000, StreamQuality.Kbps192).mimeType("audio/flac")
        // Before the stream opens, from what was asked for.
        assertEquals("MP3", audioQuality(null, mime, Format.NO_VALUE, Format.NO_VALUE, Format.NO_VALUE)?.full)
        // Once open, from what the decoder reports.
        val open = audioQuality("audio/mpeg", mime, C.ENCODING_PCM_16BIT, 44_100, 192_000)!!
        assertEquals("MP3", open.label)
        assertEquals("MP3 · 192 kbps", open.full)
    }

    private val downloaded = downloadedCopy(serverFlac, "file:///music/Octo/song.flac", "audio/flac", 30_000_000)

    @Test
    fun aDownloadPlaysBeforeTheStream() {
        for (preference in CopyPreference.entries) {
            val picked = chooseCopy(listOf(serverFlac), downloaded, streamInstead = false, preference, serverReachable = true)
            assertEquals(preference.name, "file:///music/Octo/song.flac", picked?.uri)
        }
    }

    @Test
    fun aDownloadPlaysWithNoConnection() {
        val picked = chooseCopy(listOf(serverFlac), downloaded, streamInstead = true, CopyPreference.PhoneFirst, serverReachable = false)
        assertEquals("file:///music/Octo/song.flac", picked?.uri)
    }

    @Test
    fun preferringStreamsOnWifiStreamsEvenWhenDownloaded() {
        val picked = chooseCopy(listOf(serverFlac), downloaded, streamInstead = true, CopyPreference.PhoneFirst, serverReachable = true)
        assertEquals("s", picked?.id)
    }

    @Test
    fun aPhoneFileStillComesFirstWhenThereIsOne() {
        val picked = chooseCopy(listOf(serverFlac, phoneMp3), downloaded, streamInstead = false, CopyPreference.PhoneFirst, serverReachable = true)
        assertEquals("p", picked?.id)
        // Best quality weighs the phone's file against the download, never the stream.
        val best = chooseCopy(listOf(serverFlac, phoneMp3), downloaded, streamInstead = false, CopyPreference.BestQuality, serverReachable = true)
        assertEquals(downloaded.id, best?.id)
    }

    @Test
    fun withoutADownloadNothingChanges() {
        val picked = chooseCopy(listOf(serverFlac, phoneMp3), null, streamInstead = false, CopyPreference.BestQuality, serverReachable = true)
        assertEquals("s", picked?.id)
    }

    @Test
    fun aDownloadIsACopyOnThePhone() {
        assertEquals(false, downloaded.isServerCopy)
        assertEquals(DOWNLOAD_SOURCE, downloaded.sourceId)
        assertEquals(serverFlac.bitDepth, downloaded.bitDepth)
        // One made smaller keeps only what is still true of it.
        val small = downloadedCopy(serverFlac, "file:///music/Octo/song.mp3", "audio/mpeg", 5_760_000)
        assertEquals("audio/mpeg", small.mimeType)
        assertNull(small.bitDepth)
        assertEquals(192_000, bitrateOf(small))
    }

    @Test
    fun theTempoComesFromThePlayingCopyOrAnyOther() {
        val phone = phoneMp3.copy(bpm = 0)
        val server = serverFlac.copy(bpm = 128)
        assertEquals(128, songBpm(phone, listOf(phone, server)))
        assertEquals(96, songBpm(phone.copy(bpm = 96), listOf(phone, server)))
        assertEquals(128, songBpm(null, listOf(phone, server)))
        assertNull(songBpm(phone, listOf(phone)))
    }

    @Test
    fun onlyASensibleStoredTempoReachesTheAnalysis() {
        assertEquals(128.0, tagBpm(128)!!, 0.0)
        assertNull(tagBpm(0))
        assertNull(tagBpm(-5))
        assertNull(tagBpm(null))
    }
}

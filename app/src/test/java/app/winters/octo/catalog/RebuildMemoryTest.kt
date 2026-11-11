package app.winters.octo.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

// A library of 110,000 server songs ran a phone out of memory in the rebuild
// that follows a sync: every source row was held whole, and again as a
// merged song, and again as a copy to write back. The library is now planned
// from slim rows and written 500 songs at a time. This runs that work in a
// small JVM (an Android app gets 256 MB, with the rest of the app resident)
// and keeps the whole-rows way from coming back: held whole, the same work
// needs more than 224 MB; this needs about 112. A child JVM, so the limit holds whatever flags the
// test run has.
class RebuildMemoryTest {
    @Test
    fun aLibraryOfAHundredAndTenThousandSongsIsRebuiltInALittleMemory() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val child = ProcessBuilder(
            java, "-Xmx144m", "-cp", System.getProperty("java.class.path"), RebuildProbe::class.java.name,
        ).redirectErrorStream(true).start()
        val output = child.inputStream.bufferedReader().readText()
        assertTrue("rebuild probe timed out", child.waitFor(180, TimeUnit.SECONDS))
        assertEquals("rebuild probe died:\n$output", 0, child.exitValue())
        assertTrue(output, output.contains("REBUILT 110000 110000"))
    }
}

// Run in a small JVM by the test above.
object RebuildProbe {
    private const val SOURCE = "server:scale:4533"
    private const val COUNT = 110_000
    private const val PER_ALBUM = 11
    private fun fresh(text: String) = String(text.toCharArray())

    // A song's whole row, as the database hands it back: every string its own object.
    private fun whole(n: Int): SourceTrackEntity {
        val albumNo = n / PER_ALBUM
        val artistNo = albumNo / 8
        return SourceTrackEntity(
            id = fresh("$SOURCE:s$n"), sourceId = fresh(SOURCE), nativeId = fresh("s$n"),
            title = fresh("Song $n with a title of ordinary length"), searchKey = fresh("song $n with a title of ordinary length artist $artistNo"),
            sortKey = fresh("song $n with a title of ordinary length"), artist = fresh("Artist $artistNo"),
            artistId = fresh("$SOURCE:ar-$artistNo"), album = fresh("Album number $albumNo of the scale test"),
            albumId = fresh("$SOURCE:al-$albumNo"), trackNo = n % PER_ALBUM + 1, discNo = 1, year = 2000 + albumNo % 25,
            durationMs = 200_000, addedAt = 1_760_000_000_000, mimeType = fresh("audio/x-flac"), sizeBytes = 24_000_000L,
            artwork = fresh("mf-s${n}_6aafffab"), uri = null, albumOrder = n % PER_ALBUM,
            relinkKey = fresh("artist $artistNo|album number $albumNo of the scale test|1|${n % PER_ALBUM + 1}|song $n with a title of ordinary length|200"),
            genre = fresh("Electronic"), genres = fresh("Electronic"), artists = fresh("Artist $artistNo"),
            mbRecordingId = fresh("0e1d2c3b-4a59-4687-9786-${n.toString().padStart(12, '0')}"),
        )
    }

    // What the merge reads of a source: slim rows, and the albums and artists.
    private fun read(): SourceRows {
        val rows = ArrayList<MergeRow>(COUNT)
        val albums = ArrayList<SourceAlbumEntity>()
        val artists = ArrayList<SourceArtistEntity>()
        for (n in 0 until COUNT) {
            val albumNo = n / PER_ALBUM
            val artistNo = albumNo / 8
            if (n % PER_ALBUM == 0) {
                albums += SourceAlbumEntity(
                    id = fresh("$SOURCE:al-$albumNo"), sourceId = fresh(SOURCE), nativeId = fresh("al-$albumNo"),
                    title = fresh("Album number $albumNo of the scale test"), searchKey = fresh("album number $albumNo of the scale test"),
                    sortKey = fresh("album number $albumNo of the scale test"), artist = fresh("Artist $artistNo"),
                    artistId = fresh("$SOURCE:ar-$artistNo"), year = 2000 + albumNo % 25, songCount = PER_ALBUM, durationMs = 2_400_000,
                    addedAt = 1_760_000_000_000, artwork = fresh("al-${albumNo}_6aafffab"),
                )
            }
            if (n % (PER_ALBUM * 8) == 0) {
                artists += SourceArtistEntity(
                    id = fresh("$SOURCE:ar-$artistNo"), sourceId = fresh(SOURCE), name = fresh("Artist $artistNo"),
                    searchKey = fresh("artist $artistNo"), sortKey = fresh("artist $artistNo"), albumCount = 8, songCount = PER_ALBUM * 8, artwork = null,
                )
            }
            rows += whole(n).toMergeRow()
        }
        return SourceRows(SOURCE, onPhone = false, rows, albums, artists)
    }

    // A function of its own, so the rows are let go of once the plan is made.
    private fun planned(): MergePlan = planMerge(listOf(read()))

    @JvmStatic
    fun main(args: Array<String>) {
        val plan = planned()
        var written = 0
        // The library written 500 songs at a time, each chunk's whole rows read afresh.
        plan.songs.chunked(500).forEach { chunk ->
            val bases = chunk.map { whole(it.id.substringAfterLast(":s").toInt()) }.associateBy { it.id }
            written += chunk.mapNotNull { song -> bases[song.id]?.let(song::toTrack) }.size
        }
        // The copies' library songs, one at a time.
        var linked = 0
        for ((id, mergedId) in plan.mergedIds) if (id == mergedId) linked++
        println("REBUILT ${plan.songs.size} $written copies=$linked albums=${plan.albums.size} artists=${plan.artists.size}")
    }
}

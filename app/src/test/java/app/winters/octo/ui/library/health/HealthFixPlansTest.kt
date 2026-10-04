package app.winters.octo.ui.library.health

import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.health.FixStep
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.TagChange
import app.winters.octo.subsonic.LibraryActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Library health's fixes planned from the phone's report: what shows for
// which server, and the steps each fix sends, by the server's own ids.
class HealthFixPlansTest {
    private val server = "server:home"

    private fun copy(
        id: String,
        title: String,
        merged: String,
        source: String = server,
        mime: String = "audio/flac",
        bitrate: Int? = 900,
        depth: Int? = 16,
        album: String = "Album",
        genre: String = "Rock",
    ) = SourceTrackEntity(
        id = "$source/$id", sourceId = source, nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = "Artist", artistId = "ar", album = album, albumId = "al-$album", trackNo = 1, discNo = 1, year = 2020,
        durationMs = 200_000, addedAt = 0, mimeType = mime, sizeBytes = null, artwork = "art", uri = null, albumOrder = 0,
        relinkKey = "", genre = genre, bitrate = bitrate, sampleRate = 44_100, bitDepth = depth, mergedId = merged,
    )

    private fun track(
        id: String,
        title: String,
        album: String = "Album",
        albumId: String = "al-$album",
        year: Int? = 2020,
        genre: String = "Rock",
        artwork: String? = "art",
    ) = TrackEntity(
        id = id, sourceId = server, nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = "Artist", artistId = "ar", album = album, albumId = albumId, trackNo = 1, discNo = 1, year = year,
        durationMs = 200_000, addedAt = 0, mimeType = "audio/flac", sizeBytes = null, artwork = artwork, uri = null, genre = genre,
    )

    private val all = listOf("remove", "restore", "retag", "undo", "joinAlbum", "lookup", "cover", "upgrade")
    private fun actions(vararg names: String, admin: Boolean = true, dryRun: Boolean = false) =
        LibraryActions(enabled = true, allowed = true, dryRun = dryRun, actions = names.toList(), admin = admin)

    // Holocene twice on the server: a FLAC on Bon Iver with no genre, and
    // an MP3 on a single of its own.
    private fun twice(source: String = server): PhoneHealth {
        val tracks = listOf(track("t1", "Holocene", album = "Bon Iver"), track("t2", "Holocene", album = "Holocene"))
        val copies = listOf(
            copy("flac", "Holocene", merged = "t1", source = source, album = "Bon Iver", genre = ""),
            copy("mp3", "Holocene", merged = "t2", source = source, mime = "audio/mpeg", bitrate = 320, depth = null, album = "Holocene", genre = "Folk"),
        )
        return phoneHealth(tracks, emptyList(), copies)
    }

    @Test
    fun aServerWithEveryActionOffersEveryFix() {
        val offers = healthOffers(actions(*all.toTypedArray()))

        assertEquals(HealthOffers(true, true, true, true, true, true, true, true), offers)
        HealthCheck.entries.forEach { assertTrue(it.name, offers.fixesAll(it)) }
        assertTrue(offers.looksUp(HealthCheck.NoYear))
        assertTrue(offers.looksUp(HealthCheck.NoTrackNumber))
        assertFalse(offers.looksUp(HealthCheck.NoCover))
        assertFalse(offers.looksUp(HealthCheck.Duplicates))
    }

    @Test
    fun noServerANonAdminOrARehearsalOffersNothingToChange() {
        assertEquals(HealthOffers(), healthOffers(null))
        val notAdmin = healthOffers(actions(*all.toTypedArray(), admin = false))
        assertEquals(HealthOffers(upgrade = true), notAdmin)
        assertEquals(HealthOffers(), healthOffers(actions(*all.toTypedArray(), dryRun = true)))
    }

    @Test
    fun anOlderServerThatOnlyRemovesOffersDuplicatesWithoutTheirFills() {
        val offers = healthOffers(actions("remove"))

        assertTrue(offers.fixDuplicates)
        assertTrue(offers.remove)
        assertFalse(offers.edit)
        assertFalse(offers.restore)
        assertFalse(offers.fixesAll(HealthCheck.NoYear))
        assertFalse(offers.fixesAll(HealthCheck.SplitAlbums))
        // A look-up is only offered when what it finds can be written.
        assertFalse(healthOffers(actions("lookup")).lookUp)
    }

    @Test
    fun aSetOfCopiesKeepsTheBestFillsItAndRemovesTheOtherOnTheServer() {
        val health = twice()
        val plan = health.duplicatePlan(0, server)!!

        assertEquals("flac", plan.keep.nativeId)
        assertEquals(listOf("mp3"), plan.remove.map { it.nativeId })
        assertEquals(listOf(TagChange("genre", null, "Folk", "mp3")), plan.fills)
        assertEquals(
            listOf(FixStep.Retag("flac", "Holocene", mapOf("genre" to "Folk")), FixStep.Remove("mp3", "Holocene", copy = true)),
            plan.serverSteps(plan.fills, edit = true),
        )
        // A server that cannot write tags only removes.
        assertEquals(listOf(FixStep.Remove("mp3", "Holocene", copy = true)), plan.serverSteps(plan.fills, edit = false))
        assertEquals(listOf(plan to plan.serverSteps(plan.fills, edit = true)), health.fixAllDuplicates(server, edit = true))
    }

    @Test
    fun keepingAnotherCopyRemovesTheBestAndAPickedValueIsWritten() {
        val health = twice()
        val mp3 = health.copyIn(0, "t2")!!
        val plan = health.duplicatePlan(0, server, keep = mp3)!!

        assertEquals("mp3", plan.keep.nativeId)
        assertEquals(listOf("flac"), plan.remove.map { it.nativeId })
        // The albums disagree; picking the FLAC's album writes it.
        val album = plan.differs.single { it.tag == "album" }
        assertEquals(mapOf("mp3" to "Holocene", "flac" to "Bon Iver"), album.values)
        assertEquals(listOf(TagChange("album", "Holocene", "Bon Iver", "flac")), plan.picked(mapOf("album" to "flac")))
        // Picking the kept copy's own value changes nothing.
        assertTrue(plan.picked(mapOf("album" to "mp3")).isEmpty())
    }

    @Test
    fun copiesOnAnotherSourceGetNoFix() {
        val health = twice(source = "server:other")

        assertEquals(1, health.report.duplicates.size)
        assertNull(health.duplicatePlan(0, server))
        assertNull(health.duplicatePlan(0, null))
        assertTrue(health.fixAllDuplicates(server, edit = true).isEmpty())
    }

    @Test
    fun aSplitAlbumJoinsOntoItsLargestPartLedBySongTheServerHas() {
        val tracks = listOf(
            track("a", "One", album = "It Was Fun", albumId = "x"),
            track("b", "Two", album = "It Was Fun", albumId = "x"),
            track("c", "Three", album = "It Was Fun", albumId = "y"),
        )
        val health = phoneHealth(tracks, emptyList(), emptyList())
        val ids = mapOf("a" to "na", "b" to "nb", "c" to "nc")

        val (join, steps) = health.joinPlan(0, ids)!!
        assertEquals("Moves 1 song onto It Was Fun, the part with 2 songs.", join.words)
        assertEquals(listOf(FixStep.JoinAlbum("nc", "Three", like = "na")), steps)
        // A lead the server does not have gives way to the next song.
        assertEquals(listOf(FixStep.JoinAlbum("nc", "Three", like = "nb")), health.joinPlan(0, ids - "a")!!.second)
        // Nothing to move on the server: no join.
        assertNull(health.joinPlan(0, ids - "c"))
        assertEquals(steps, health.joinAll(ids))
        assertEquals(1, health.fixAllPreview(HealthCheck.SplitAlbums, healthOffers(actions("joinAlbum")), server, ids).count)
    }

    @Test
    fun aMissingYearIsFilledFromItsAlbumAndACoverLookedFor() {
        val tracks = listOf(
            track("a", "One", albumId = "x"),
            track("b", "Two", albumId = "x", year = null, artwork = null),
            track("c", "Three", albumId = "x"),
            // Alone on its album, with nothing to take a year from.
            track("d", "Four", album = "Single", albumId = "s", year = null),
        )
        val health = phoneHealth(tracks, emptyList(), emptyList())
        val ids = mapOf("a" to "na", "b" to "nb", "c" to "nc", "d" to "nd")

        assertEquals(listOf(FixStep.Retag("nb", "Two", mapOf("year" to "2020"))), health.fillSteps(HealthCheck.NoYear, ids))
        // A song the server does not have is left alone.
        assertTrue(health.fillSteps(HealthCheck.NoYear, ids - "b").isEmpty())
        assertEquals(listOf(FixStep.AddCover("nb", "Two")), health.coverSteps(ids))

        val preview = health.fixAllPreview(HealthCheck.NoYear, healthOffers(actions("retag", "undo")), server, ids)
        assertEquals(1, preview.count)
        assertEquals(listOf("Two" to "Year: 2020"), preview.lines)
        // Not offered at all: nothing planned.
        assertEquals(0, health.fixAllPreview(HealthCheck.NoYear, healthOffers(actions("remove")), server, ids).count)
    }

    @Test
    fun fixAllForDuplicatesPreviewsEachSet() {
        val preview = twice().fixAllPreview(HealthCheck.Duplicates, healthOffers(actions("remove", "retag", "undo")), server, emptyMap())

        assertEquals(1, preview.count)
        assertEquals(
            listOf("Keeps Holocene (FLAC, 16-bit, 44.1 kHz)" to "Removes Holocene (MP3, 320 kbps). Fills in Genre: Folk."),
            preview.lines,
        )
        assertEquals(listOf(FixStep.Retag("flac", "Holocene", mapOf("genre" to "Folk")), FixStep.Remove("mp3", "Holocene", copy = true)), preview.steps)
    }

    @Test
    fun aBulkLookUpTakesAtMostABatchOfSongsTheServerHas() {
        val songs = (1..30).map { track("t$it", "Song $it") }
        val ids = songs.drop(1).associate { it.id to "n${it.id}" }

        val batch = lookupBatch(songs, ids)
        assertEquals(LOOKUP_BATCH, batch.size)
        assertEquals("t2" to "nt2", batch.first().let { it.first.id to it.second })
        assertNull(retagStep("n1", "One", emptyList()))
        assertEquals(
            FixStep.Retag("n1", "One", mapOf("track" to "3", "year" to "1998")),
            retagStep("n1", "One", listOf(TagChange("track", null, "3"), TagChange("year", "1997", "1998"))),
        )
    }
}

package app.winters.octo.ui.library.health

import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.catalog.read
import app.winters.octo.health.FixStep
import app.winters.octo.health.HealthCheck
import app.winters.octo.health.SplitBasis
import app.winters.octo.health.TagChange
import app.winters.octo.health.words
import app.winters.octo.listening.timed
import app.winters.octo.subsonic.LibraryActions
import app.winters.octo.testing.openTestCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Library health's fixes planned from the phone's report: what shows for
// which server, and the steps each fix sends, by the server's own ids.
@RunWith(RobolectricTestRunner::class)
class HealthFixPlansTest {
    private val dbs = ArrayList<app.winters.octo.catalog.CatalogDatabase>()

    @After
    fun close() = dbs.forEach { it.close() }

    private val server = "server:home"
    private var seq = 0L

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
        relinkKey = "", genre = genre, bitrate = bitrate, sampleRate = 44_100, bitDepth = depth, mergedId = merged, seq = seq++,
    )

    private fun track(
        id: String,
        title: String,
        album: String = "Album",
        albumId: String = "al-$album",
        year: Int? = 2020,
        genre: String = "Rock",
        artwork: String? = "art",
        trackNo: Int? = 1,
    ) = TrackEntity(
        id = id, sourceId = server, nativeId = id, title = title, searchKey = title.lowercase(), sortKey = title.lowercase(),
        artist = "Artist", artistId = "ar", album = album, albumId = albumId, trackNo = trackNo, discNo = 1, year = year,
        durationMs = 200_000, addedAt = 0, mimeType = "audio/flac", sizeBytes = null, artwork = artwork, uri = null, genre = genre,
    )

    // One copy of each song on the server, named `n` and the song's id,
    // for the songs `ids` names.
    private fun onServer(tracks: List<TrackEntity>, ids: Set<String> = tracks.map { it.id }.toSet()) =
        tracks.filter { it.id in ids }.map { copy("n${it.id}", it.title, merged = it.id, album = it.album, genre = it.genre) }

    // A report of these songs and copies, in a library of its own.
    private suspend fun rig(tracks: List<TrackEntity>, copies: List<SourceTrackEntity>): HealthRig {
        val db = openTestCatalog().also(dbs::add)
        db.putHealth(emptyList(), tracks, copies)
        return HealthRig(db).keys().also { it.built() }
    }

    private val all = listOf("remove", "restore", "retag", "undo", "joinAlbum", "lookup", "cover", "upgrade")
    private fun actions(vararg names: String, admin: Boolean = true, dryRun: Boolean = false) =
        LibraryActions(enabled = true, allowed = true, dryRun = dryRun, actions = names.toList(), admin = admin)

    // Holocene twice on the server: a FLAC on Bon Iver with no genre, and
    // an MP3 on a single of its own.
    private suspend fun twice(source: String = server): HealthRig {
        val tracks = listOf(track("t1", "Holocene", album = "Bon Iver"), track("t2", "Holocene", album = "Holocene"))
        val copies = listOf(
            copy("flac", "Holocene", merged = "t1", source = source, album = "Bon Iver", genre = ""),
            copy("mp3", "Holocene", merged = "t2", source = source, mime = "audio/mpeg", bitrate = 320, depth = null, album = "Holocene", genre = "Folk"),
        )
        return rig(tracks, copies)
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
    fun aSetOfCopiesKeepsTheBestFillsItAndRemovesTheOtherOnTheServer() = timed(60) {
        val rig = twice()
        val group = (rig.page(HealthCheck.Duplicates, server).first().row as HealthRow.Copies).group
        val plan = duplicatePlan(group, server)!!

        assertEquals("flac", plan.keep.nativeId)
        assertEquals(listOf("mp3"), plan.remove.map { it.nativeId })
        assertEquals(listOf(TagChange("genre", null, "Folk", "mp3")), plan.fills)
        val steps = listOf(FixStep.Retag("flac", "Holocene", mapOf("genre" to "Folk")), FixStep.Remove("mp3", "Holocene", copy = true))
        assertEquals(steps, plan.serverSteps(plan.fills, edit = true))
        // A server that cannot write tags only removes.
        assertEquals(listOf(FixStep.Remove("mp3", "Holocene", copy = true)), plan.serverSteps(plan.fills, edit = false))
        assertEquals(steps, rig.steps(rig.plans.open(HealthCheck.Duplicates, server, edit = true)!!))
    }

    @Test
    fun keepingAnotherCopyRemovesTheBestAndAPickedValueIsWritten() = timed(60) {
        val rig = twice()
        val song = rig.page(HealthCheck.Duplicates, server).map { it.row }.filterIsInstance<HealthRow.Song>().single { it.track.id == "t2" }
        val mp3 = song.copy!!
        val plan = duplicatePlan(song.group!!, server, keep = mp3)!!

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
    fun copiesOnAnotherSourceGetNoFix() = timed(60) {
        val rig = twice(source = "server:other")
        val group = (rig.page(HealthCheck.Duplicates, server).first().row as HealthRow.Copies).group

        assertNull(duplicatePlan(group, server))
        assertNull(duplicatePlan(group, null))
        assertEquals(0, rig.plans.count(HealthCheck.Duplicates, server, edit = true)!!.count)
    }

    @Test
    fun aSplitAlbumJoinsOntoItsLargestPartLedBySongTheServerHas() = timed(120) {
        val tracks = listOf(
            track("a", "One", album = "It Was Fun", albumId = "x"),
            track("b", "Two", album = "It Was Fun", albumId = "x"),
            track("c", "Three", album = "It Was Fun", albumId = "y"),
        )
        val every = rig(tracks, onServer(tracks))
        val plan = every.plans.open(HealthCheck.SplitAlbums, server, edit = true)!!
        assertEquals(1, plan.count)
        assertEquals(listOf("It Was Fun by Artist, shown as 2 albums" to "Moves 1 song onto It Was Fun, the part with 2 songs."), plan.lines)
        assertEquals(listOf(FixStep.JoinAlbum("nc", "Three", like = "na")), every.steps(plan))
        assertTrue((every.page(HealthCheck.SplitAlbums, server).first().row as HealthRow.Split).joinable)
        // A lead the server does not have gives way to the next song.
        val noLead = rig(tracks, onServer(tracks, setOf("b", "c")))
        assertEquals(listOf(FixStep.JoinAlbum("nc", "Three", like = "nb")), noLead.steps(noLead.plans.open(HealthCheck.SplitAlbums, server, edit = true)!!))
        // Nothing to move on the server: no join.
        val nothing = rig(tracks, onServer(tracks, setOf("a", "b")))
        assertEquals(0, nothing.plans.count(HealthCheck.SplitAlbums, server, edit = true)!!.count)
        assertFalse((nothing.page(HealthCheck.SplitAlbums, server).first().row as HealthRow.Split).joinable)
    }

    @Test
    fun anAlbumFiledUnderEachOfItsArtistsJoinsOntoTheLargerPart() = timed(60) {
        val title = "\$ome \$exy \$ongs 4 U"
        val tracks = listOf(
            track("p1", "CELIBACY", album = title, albumId = "pnd").copy(artist = "PARTYNEXTDOOR"),
            track("p2", "CN TOWER", album = title, albumId = "pnd").copy(artist = "PARTYNEXTDOOR"),
            track("p3", "NOKIA", album = title, albumId = "pnd").copy(artist = "Drake"),
            track("d1", "GIMME A HUG", album = title, albumId = "drake", year = null).copy(artist = "Drake"),
        )
        val rig = rig(tracks, onServer(tracks))
        val heading = rig.page(HealthCheck.SplitAlbums, server).first().row as HealthRow.Split

        assertTrue(heading.detail, heading.detail.startsWith("One album filed under two artists: the part by PARTYNEXTDOOR has NOKIA by Drake."))
        val plan = rig.plans.open(HealthCheck.SplitAlbums, server, edit = true)!!
        assertEquals(listOf(FixStep.JoinAlbum("nd1", "GIMME A HUG", like = "np1")), rig.steps(plan))
        assertEquals("Moves 1 song onto $title, the part with 3 songs. Their album artist becomes PARTYNEXTDOOR.", plan.lines.single().second)
    }

    @Test
    fun aSourcesReleaseGroupTiesPartsByDifferentArtists() = timed(60) {
        val tracks = listOf(
            track("z1", "Time", album = "Inception", albumId = "zimmer").copy(artist = "Hans Zimmer"),
            track("b1", "Old Souls", album = "Inception", albumId = "balfe").copy(artist = "Lorne Balfe"),
        )
        val copies = listOf(
            copy("z", "Time", merged = "z1", album = "Inception").copy(mbReleaseGroupId = "5b5f2a1e-1111-4111-8111-111111111111"),
            copy("b", "Old Souls", merged = "b1", album = "Inception").copy(mbReleaseGroupId = "5b5f2a1e-1111-4111-8111-111111111111"),
        )

        assertEquals(0, rig(tracks, emptyList()).built().count(HealthCheck.SplitAlbums))
        val joined = rig(tracks, copies)
        val reasons = joined.db.read("SELECT reasons FROM health_split WHERE owner = '${HealthBuild.OWNER}'") { decodeReasons(it.getString(0)) }.single()
        assertEquals(SplitBasis.SameReleaseGroup, reasons.single().basis)
    }

    @Test
    fun aMissingYearIsFilledFromItsAlbumAndACoverLookedFor() = timed(120) {
        val tracks = listOf(
            track("a", "One", albumId = "x"),
            track("b", "Two", albumId = "x", year = null, artwork = null),
            track("c", "Three", albumId = "x"),
            // Alone on its album, with nothing to take a year from.
            track("d", "Four", album = "Single", albumId = "s", year = null),
        )
        val every = rig(tracks, onServer(tracks))

        val year = every.plans.open(HealthCheck.NoYear, server, edit = true)!!
        assertEquals(1, year.count)
        assertEquals(listOf("Two" to "Year: 2020"), year.lines)
        assertEquals(listOf(FixStep.Retag("nb", "Two", mapOf("year" to "2020"))), every.steps(year))
        assertEquals(listOf(FixStep.AddCover("nb", "Two")), every.steps(every.plans.open(HealthCheck.NoCover, server, edit = true)!!))
        // A song the server does not have is left alone.
        val without = rig(tracks, onServer(tracks, setOf("a", "c", "d")))
        assertEquals(0, without.plans.count(HealthCheck.NoYear, server, edit = true)!!.count)
    }

    @Test
    fun fixAllForDuplicatesPreviewsEachSet() = timed(60) {
        val rig = twice()
        val plan = rig.plans.open(HealthCheck.Duplicates, server, edit = true)!!

        assertEquals(1, plan.count)
        assertEquals(listOf("Keeps Holocene (FLAC, 16-bit, 44.1 kHz)" to "Removes Holocene (MP3, 320 kbps). Fills in Genre: Folk."), plan.lines)
        assertEquals(listOf(FixStep.Retag("flac", "Holocene", mapOf("genre" to "Folk")), FixStep.Remove("mp3", "Holocene", copy = true)), rig.steps(plan))
    }

    @Test
    fun aBulkLookUpTakesAtMostABatchOfSongsTheServerHas() = timed(60) {
        val songs = (1..30).map { track("t$it", "Song %02d".format(it), albumId = "x", trackNo = null) }
        val rig = rig(songs, onServer(songs, songs.drop(1).map { it.id }.toSet()))

        val batch = rig.plans.lookUpBatch(HealthCheck.NoTrackNumber, server)
        assertEquals(LOOKUP_BATCH, batch.size)
        assertEquals("t2" to "nt2", batch.first().let { it.first.id to it.second })
        assertNull(retagStep("n1", "One", emptyList()))
        assertEquals(
            FixStep.Retag("n1", "One", mapOf("track" to "3", "year" to "1998")),
            retagStep("n1", "One", listOf(TagChange("track", null, "3"), TagChange("year", "1997", "1998"))),
        )
    }
}

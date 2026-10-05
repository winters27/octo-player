package app.winters.octo.health

import app.winters.octo.subsonic.Album
import app.winters.octo.subsonic.ArtistRef
import app.winters.octo.subsonic.RecordLabel
import app.winters.octo.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Albums split across album artists: one album filed under each of the
// artists it is credited to, and the albums of one name that only look
// like that.
class SplitAcrossArtistsTest {
    private var next = 0

    // A song as Navidrome lists it: its credited artists, its album artist
    // and the album id the server gave its part.
    private fun song(
        title: String,
        artist: String,
        album: String,
        albumId: String,
        albumArtist: String = artist,
        year: Int? = null,
        track: Int? = null,
        artists: List<String> = listOf(artist),
        id: String = "s${next++}",
    ) = Song(
        id = id, title = title, artist = artist, album = album, albumId = albumId, duration = 200, suffix = "flac",
        year = year, track = track, displayAlbumArtist = albumArtist,
        artists = artists.map { ArtistRef(name = it) }, albumArtists = listOf(ArtistRef(name = albumArtist)),
    )

    private fun splits(songs: List<Song>, fields: HealthFields<Song> = SubsonicHealth) = findSplitAlbums(songs, fields)

    // The library as the live server had it on 2026-10-04: PARTYNEXTDOOR's
    // 15 songs (one of them, NOKIA, by Drake), Drake's five with no year or
    // track, and Pimmie's interlude, each under its own album artist.
    private val title = "\$ome \$exy \$ongs 4 U"
    private val partynextdoor = listOf(
        "CELIBACY", "CN TOWER", "DEEPER", "DIE TRYING", "GLORIOUS", "GREEDY", "LASERS", "MEET YOUR PADRE",
        "MOTH BALLS", "OMW", "SOMEBODY LOVES ME", "SOMETHING ABOUT YOU", "SPIDER-MAN SUPERMAN", "WHEN HE'S GONE",
    ).map { song(it, "PARTYNEXTDOOR", title, "08qrDbXNlx9NTxtgugKes1", year = 2025) } +
        song("NOKIA", "Drake", title, "08qrDbXNlx9NTxtgugKes1", albumArtist = "PARTYNEXTDOOR", year = 2025, track = 14, id = "nokia")
    private val drake = listOf("BRIAN STEEL", "CRYING IN CHANEL", "GIMME A HUG", "RAINING IN HOUSTON", "SMALL TOWN FAME")
        .map { song(it, "Drake", title, "3jmLzEVlwtrlq8XfVjei9k") }
    private val pimmie = listOf(song("PIMMIE'S DILEMMA", "Pimmie", title, "5SHl4krFWPwjViGmMeA0TB"))

    @Test
    fun aCollaborationAlbumFiledUnderEachArtistIsOneAlbum() {
        val found = splits(partynextdoor + drake + pimmie).single()

        assertEquals(listOf("08qrDbXNlx9NTxtgugKes1", "3jmLzEVlwtrlq8XfVjei9k"), found.parts.map { it.albumId })
        assertEquals(listOf(15, 5), found.parts.map { it.songs.size })
        assertEquals("PARTYNEXTDOOR", found.artist)
        assertEquals(listOf(SplitReason(SplitBasis.SharedArtist, artist = "Drake", other = "PARTYNEXTDOOR", song = "NOKIA")), found.reasons)
        assertEquals("$title by PARTYNEXTDOOR, shown as 2 albums", found.heading())
        assertEquals(
            "One album filed under two artists: the part by PARTYNEXTDOOR has NOKIA by Drake. " +
                "The album artist differs: PARTYNEXTDOOR, Drake. The year differs: 2025, none.",
            found.summary(),
        )
    }

    @Test
    fun joiningItGivesDrakesSongsTheLargerPartsAlbumArtist() {
        val join = albumJoin(splits(partynextdoor + drake).single())

        assertEquals(drake.map { it.id }.toSet(), join.moving.map { it.id }.toSet())
        assertEquals("08qrDbXNlx9NTxtgugKes1", join.lead.albumId)
        assertTrue(join.steps(SubsonicHealth).all { it is FixStep.JoinAlbum && it.like == join.lead.id })
        assertEquals("Moves 5 songs onto $title, the part with 15 songs. Their album artist becomes PARTYNEXTDOOR.", join.words)
    }

    @Test
    fun aPartWithNothingTyingItToTheOthersIsLeftAlone() {
        // Pimmie's interlude is on the album, but nothing in the library
        // says so: no song by Pimmie on the other parts, no shared code.
        val found = splits(partynextdoor + pimmie)

        assertTrue(found.isEmpty())
    }

    @Test
    fun aCreditNamingBothArtistsLeadsTheJoin() {
        val both = listOf("Big Rings", "Diamonds Dancing").map { song(it, "Drake & Future", "What a Time to Be Alive", "both", year = 2015, artists = listOf("Drake", "Future")) }
        val future = listOf("Digital Dash", "Live From the Gutter", "Jumpman", "Scholarships", "30 for 30 Freestyle")
            .map { song(it, "Future", "What a Time to Be Alive", "future", year = 2015) }

        val found = splits(both + future).single()
        val join = albumJoin(found)

        assertEquals(listOf("both", "future"), found.parts.map { it.albumId })
        assertEquals(listOf(SplitReason(SplitBasis.SharedArtist, artist = "Future", other = "Drake & Future")), found.reasons)
        assertEquals("One album filed under two artists: the part by Drake & Future names Future too.", found.reasons.single().words())
        assertEquals(
            "Moves 5 songs onto What a Time to Be Alive, the part with 2 songs, since its album artist names every part's artist. " +
                "Their album artist becomes Drake & Future.",
            join.words,
        )
    }

    // Albums of one name that are not one album

    @Test
    fun aTitleManyArtistsUseIsNeverJoinedOnACreditAlone() {
        val found = splits(
            listOf(
                song("Bohemian Rhapsody", "Queen", "Greatest Hits", "queen"),
                song("Under Pressure", "Queen & David Bowie", "Greatest Hits", "queen", albumArtist = "Queen", artists = listOf("Queen", "David Bowie")),
                song("Heroes", "David Bowie", "Greatest Hits", "bowie"),
                song("Under Pressure", "Queen & David Bowie", "Greatest Hits", "bowie", albumArtist = "David Bowie", artists = listOf("Queen", "David Bowie")),
                song("One More Time", "Daft Punk", "Live", "daft"),
                song("Harder Better Faster Stronger", "Daft Punk", "Live", "daft"),
                song("Get Lucky", "Daft Punk feat. Pharrell Williams", "Live", "pharrell", albumArtist = "Pharrell Williams", artists = listOf("Daft Punk", "Pharrell Williams")),
                song("Untitled 01", "Kendrick Lamar", "Untitled", "kendrick"),
                song("Untitled 02", "Kendrick Lamar & SZA", "Untitled", "sza", albumArtist = "SZA", artists = listOf("Kendrick Lamar", "SZA")),
            ),
        )

        assertTrue(found.isEmpty())
    }

    @Test
    fun anAlbumNamedAfterItsArtistIsNeverJoinedOnACreditAlone() {
        val found = splits(
            listOf(
                song("Enter Sandman", "Metallica", "Metallica", "black"),
                song("Nothing Else Matters", "Metallica", "Metallica", "black"),
                song("Nothing Else Matters", "Apocalyptica & Metallica", "Metallica", "tribute", albumArtist = "Apocalyptica", artists = listOf("Apocalyptica", "Metallica")),
            ),
        )

        assertTrue(found.isEmpty())
    }

    @Test
    fun aCreditDoesNotJoinAlbumsOfOneNameFromDifferentYears() {
        val found = splits(
            listOf(
                song("A Case of You", "Joni Mitchell", "Blue", "joni", year = 1971),
                song("River", "Joni Mitchell", "Blue", "joni", year = 1971),
                song("Blue", "LeAnn Rimes", "Blue", "leann", year = 1996),
                song("River", "LeAnn Rimes & Joni Mitchell", "Blue", "leann", albumArtist = "LeAnn Rimes", year = 1996, artists = listOf("LeAnn Rimes", "Joni Mitchell")),
            ),
        )

        assertTrue(found.isEmpty())
    }

    @Test
    fun albumsOfOneNameByArtistsWithNothingInCommonStayApart() {
        val found = splits(
            listOf(
                song("A Case of You", "Joni Mitchell", "Blue", "joni"),
                song("Semi-Charmed Life", "Third Eye Blind", "Blue", "third-eye-blind"),
                song("Blue", "LeAnn Rimes", "Blue", "leann"),
            ),
        )

        assertTrue(found.isEmpty())
    }

    @Test
    fun aDeluxeEditionByAnotherCreditIsAnotherAlbum() {
        val found = splits(
            listOf(
                song("God's Plan", "Drake", "Scorpion", "standard", year = 2018),
                song("Nonstop", "Drake", "Scorpion", "standard", year = 2018),
                song("In My Feelings", "Drake & City Girls", "Scorpion (Deluxe)", "deluxe", albumArtist = "City Girls", year = 2018, artists = listOf("Drake", "City Girls")),
            ),
        )

        assertTrue(found.isEmpty())
    }

    // Codes and labels the parts share

    // The songs' fields with release facts by album id.
    private fun facts(
        releases: Map<String, String> = emptyMap(),
        groups: Map<String, String> = emptyMap(),
        barcodes: Map<String, String> = emptyMap(),
        labels: Map<String, String> = emptyMap(),
    ) = object : HealthFields<Song> by SubsonicHealth {
        override fun releaseId(song: Song) = releases[song.albumId]
        override fun releaseGroupId(song: Song) = groups[song.albumId]
        override fun barcode(song: Song) = barcodes[song.albumId]
        override fun labels(song: Song) = listOfNotNull(labels[song.albumId])
    }

    // A soundtrack filed under its composer on one part and its performer
    // on the other: nothing in the credits ties them.
    private val score = listOf(
        song("Time", "Hans Zimmer", "Inception", "zimmer", year = 2010),
        song("Dream Is Collapsing", "Hans Zimmer", "Inception", "zimmer", year = 2010),
        song("Old Souls", "Lorne Balfe", "Inception", "balfe", year = 2010),
    )

    @Test
    fun theSameReleaseOrReleaseGroupJoinsParts() {
        val byRelease = splits(score, facts(releases = mapOf("zimmer" to "A1B2C3D4-0000-4000-8000-000000000001", "balfe" to "a1b2c3d4-0000-4000-8000-000000000001"))).single()
        val byGroup = splits(score, facts(groups = mapOf("zimmer" to "5b5f2a1e-1111-4111-8111-111111111111", "balfe" to "5b5f2a1e-1111-4111-8111-111111111111"))).single()

        assertEquals(listOf("zimmer", "balfe"), byRelease.parts.map { it.albumId })
        assertEquals("The parts are tagged as the same MusicBrainz release.", byRelease.reasons.single().words())
        assertEquals("The parts are tagged as the same MusicBrainz release group.", byGroup.reasons.single().words())
    }

    @Test
    fun aBarcodeWrittenAsUpcOrEanJoinsParts() {
        val found = splits(score, facts(barcodes = mapOf("zimmer" to "093624966126", "balfe" to "0093624966126"))).single()

        assertEquals("The parts carry the same barcode, 93624966126.", found.reasons.single().words())
    }

    @Test
    fun theSameLabelJoinsPartsOnlyInTheSameYear() {
        val labels = mapOf("zimmer" to "Reprise Records", "balfe" to "Reprise Records")
        val found = splits(score, facts(labels = labels)).single()
        val unknownYear = splits(score.map { if (it.albumId == "balfe") it.copy(year = null) else it }, facts(labels = labels))
        val otherLabel = splits(score, facts(labels = mapOf("zimmer" to "Reprise Records", "balfe" to "WaterTower Music")))

        assertEquals("The parts came out on the same label in the same year: Reprise Records, 2010.", found.reasons.single().words())
        assertTrue(unknownYear.isEmpty())
        assertTrue(otherLabel.isEmpty())
    }

    @Test
    fun aCodeNeverJoinsPartsFromDifferentYears() {
        val years = score.map { if (it.albumId == "balfe") it.copy(year = 2020) else it }

        assertTrue(splits(years, facts(groups = mapOf("zimmer" to "g", "balfe" to "g"))).isEmpty())
    }

    @Test
    fun theServersAlbumListGivesTheReleaseAndLabel() {
        val albums = listOf(
            Album(id = "zimmer", name = "Inception", musicBrainzId = "r1", recordLabels = listOf(RecordLabel("Reprise Records"))),
            Album(id = "balfe", name = "Inception", musicBrainzId = "r1"),
        )

        val found = checkLibrary(score, SubsonicAlbumHealth(albums)).splitAlbums.single()

        assertEquals(SplitBasis.SameRelease, found.reasons.single().basis)
        assertEquals(listOf("Reprise Records"), SubsonicAlbumHealth(albums).labels(score[0]))
    }

    @Test
    fun partsByOneAlbumArtistNeedNoReason() {
        val found = splits(
            listOf(
                song("Angel", "Massive Attack", "Mezzanine", "a", year = 1998),
                song("Teardrop", "Massive Attack", "Mezzanine", "b", year = 1998),
            ),
        ).single()

        assertTrue(found.reasons.isEmpty())
        assertEquals("Something else in the tags differs, often a release date or an album id on only some songs.", found.summary())
    }
}

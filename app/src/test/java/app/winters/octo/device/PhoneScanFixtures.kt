package app.winters.octo.device

import android.app.Application
import androidx.room.RoomRawQuery
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.FileTagsEntity
import app.winters.octo.catalog.SourceAlbumEntity
import app.winters.octo.catalog.SourceArtistEntity
import app.winters.octo.catalog.SourceTrackEntity
import app.winters.octo.catalog.withKeys
import app.winters.octo.device.today.TodayPhoneScan
import app.winters.octo.device.today.todayPhoneScan
import app.winters.octo.testing.FakeMediaStore
import app.winters.octo.testing.PhoneFile
import app.winters.octo.testing.PhoneFiles
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random

// The phone's media library clock in the scan fixtures: a day past every
// file's own times, so a time "more than a day from now" is a choice.
internal const val PHONE_NOW = 1_800_000_000L

// A phone as a test lists it: the files in the media library's order and
// the tags each file's own reading gives, for the files that can be read.
internal class TestPhoneFiles(val files: List<PhoneFile>, val tags: Map<Long, FileTags>) {
    fun listed(): PhoneFiles = PhoneFiles(files.size) { files[it] }
}

// The saved tags a scan would have kept for these files, as read now.
internal fun TestPhoneFiles.saved(tagsVersion: Int = TAGS_VERSION): List<FileTagsEntity> =
    files.mapNotNull { f -> tags[f.id]?.let { savedTags(f.id, f.modifiedSeconds, f.sizeBytes, it, tagsVersion) } }

internal suspend fun CatalogDatabase.saveTags(tags: List<FileTagsEntity>) = tags.chunked(500).forEach { dao().upsertFileTags(it) }

// A phone whose files exercise every rule of the build and every tie it
// can meet: albums named alike with other cases and accents, names split
// by release or by album artist into two or three albums, partly tagged
// names, compilations, file-name numbers (usable, repeated, and artist
// names that start with a number), discs, genre spellings of one family,
// equal counts and lengths, emoji and the replacement character in names
// and folders, several sort names per artist, equal added times, a folder
// switched off (WhatsApp), music on two storages, files whose tags cannot
// be read, and one big untagged "Unknown album" of `big` files.
internal fun skewedPhone(seed: Int = 7, big: Int = 5_000): TestPhoneFiles {
    val random = Random(seed)
    val files = ArrayList<PhoneFile>()
    val tags = HashMap<Long, FileTags>()
    var id = 1L

    fun add(name: String, folder: String?, read: FileTags?, volume: String = "external_primary", modified: Long = 1_600_000_000L + id * 7, fallback: PhoneFile.() -> PhoneFile = { this }) {
        val file = PhoneFile(id = id, displayName = name, relativePath = folder, volume = volume, modifiedSeconds = modified, addedSeconds = modified + 50, durationMs = 120_000L + id * 13 % 90_000)
            .fallback()
        files += file
        if (read != null) tags[id] = read
        id++
    }

    val genres = listOf(listOf("Hip Hop"), listOf("hip-hop"), listOf("HipHop", "Rap"), listOf("Rock"), listOf("rock", "Pop"), listOf("Synthwave"), emptyList())
    // Ordinary albums by twenty artists, some on the SD card, with sort names
    // that change from album to album.
    for (album in 0 until 60) {
        val artist = album % 20
        val songs = 2 + album % 14
        val volume = if (album % 9 == 4) "1a2b-3c4d" else "external_primary"
        val sameAdded = album % 20 == 3
        for (n in 1..songs) {
            val genre = genres[(album + n) % genres.size]
            add(
                name = "%02d Song %d.flac".format(n, n),
                folder = "Music/Artist $artist/Album $album/",
                read = FileTags(
                    title = "Song $album-$n",
                    artist = if (n % 5 == 0) "Artist $artist feat. Guest" else "Artist $artist",
                    albumArtist = if (album % 3 == 0) null else "Artist $artist",
                    album = "Album $album",
                    trackNo = if (album % 7 == 1) null else n,
                    discNo = if (album % 11 == 2) 1 + n % 2 else null,
                    year = 1990 + album % 30,
                    originalYear = if (album % 13 == 5) 1970 else null,
                    genres = genre,
                    sortAlbum = if (album % 8 == 1 && n == 2) "Album $album, The" else null,
                    sortAlbumArtist = if (album % 4 == 2) "Artist $artist, sorted ${album % 3}" else null,
                    trackGain = if (n % 3 == 0) -6.5f else null,
                ),
                volume = volume,
                modified = if (sameAdded) 1_650_000_000L else 1_600_000_000L + id * 7,
            )
        }
    }
    // One name in three spellings, with equal counts: the first, longest wins.
    listOf("Café Society", "CAFÉ SOCIETY", "Cafe Society ", "Café Society", "CAFÉ SOCIETY", "Cafe Society ").forEachIndexed { n, title ->
        add("Cafe $n.mp3", "Music/Cafe/", FileTags(title = "Cafe $n", artist = "Woody", album = title, trackNo = n + 1))
    }
    // Every file names a release: three albums of one name.
    listOf("MB-A", "mb-b", "MB-A", "MB-C", "mb-b", "mb-a").forEachIndexed { n, release ->
        add("hits $n.flac", "Music/Hits/", FileTags(title = "Hit $n", artist = "Various", album = "Greatest Hits", mbAlbumId = release, trackNo = n + 1))
    }
    // Every file names an album artist: two albums of one name.
    listOf("Queen", "ABBA", "queen", "ABBA").forEachIndexed { n, by ->
        add("live $n.flac", "Music/Live/", FileTags(title = "Live $n", artist = by, albumArtist = by, album = "Live", trackNo = n + 1))
    }
    // Only some files name an album artist: one album.
    listOf("Queen", null, "Queen", null).forEachIndexed { n, by ->
        add("mixed $n.flac", "Music/Mixed/", FileTags(title = "Mixed $n", artist = "Freddie", albumArtist = by, album = "Mixed"))
    }
    // A compilation, and one without the flag whose artists tie.
    listOf("A", "B", "C").forEachIndexed { n, who -> add("now $n.mp3", "Music/Now/", FileTags(title = "Now $n", artist = who, album = "Now 1", compilation = n == 1)) }
    listOf("Bea", "Al", "Al", "Bea").forEachIndexed { n, who -> add("tie $n.mp3", "Music/Tie/", FileTags(title = "Tie $n", artist = who, album = "Ties")) }
    // File-name numbers: usable, repeated, and an artist's name.
    listOf("02 - Beta.mp3", "01. Alpha.mp3", "03 Gamma.mp3").forEach { add(it, "Music/Numbers/", FileTags(title = it.drop(4), artist = "Num", album = "Numbers")) }
    listOf("01 One.mp3", "01 Uno.mp3").forEach { add(it, "Music/Dupes/", FileTags(title = it, artist = "Dup", album = "Dupes")) }
    listOf("50 Cent - In Da Club.mp3", "50 Cent - Candy Shop.mp3").forEach { add(it, "Music/50/", FileTags(artist = "50 Cent", album = "Massacre")) }
    // Emoji and the replacement character in names and folders.
    listOf("😀 smile.flac", "� broken.flac", "plain.flac", "😀 again.flac").forEach {
        add(it, "Music/😀 Folder/�/", FileTags(title = it, artist = "Emo 😀", album = "Album 😀"))
    }
    // Untagged files named after their folder, and files whose tags cannot
    // be read, with the media library's own reading.
    repeat(5) { n -> add("loose $n.mp3", "Music/Loose Tapes/", null) }
    repeat(6) { n ->
        add("store $n.mp3", "Music/Store/", null) {
            copy(title = "Store $n", artist = "Shop", album = "From The Store", albumArtist = if (n % 2 == 0) "Shop" else null, track = 1001 + n, genre = "Hip Hop")
        }
    }
    // A switched-off folder.
    repeat(4) { n -> add("voice $n.opus", "WhatsApp/Audio/", FileTags(title = "Voice $n", artist = "Friend", album = "Voice notes")) }
    // The big untagged album, with no folder name to stand in.
    repeat(big) { n ->
        add("track$n.mp3", null, FileTags(title = "Untitled ${n % 997}", artist = "Artist ${random.nextInt(300)}", genres = genres[n % genres.size]))
    }
    return TestPhoneFiles(files, tags)
}

// The phone some time later: one file in twenty gone, some edited (read
// again, and unreadable now), some moved to another folder with their tags
// as they were, new ones in, and the whole list in another order.
internal fun TestPhoneFiles.changed(seed: Int): TestPhoneFiles {
    val random = Random(seed)
    var next = files.maxOf { it.id } + 1
    val kept = files.filter { random.nextInt(20) != 0 }.map { f ->
        when (random.nextInt(25)) {
            0 -> f.copy(modifiedSeconds = f.modifiedSeconds + 3_600)
            1 -> f.copy(relativePath = "Music/Moved/")
            else -> f
        }
    }
    val added = (0 until 40).map { n ->
        PhoneFile(id = next++, displayName = "new $n.flac", relativePath = "Music/New/", title = "New $n", artist = "Newcomer", album = "Fresh")
    }
    val order = (kept + added).chunked(97).reversed().flatMap { chunk -> if (random.nextBoolean()) chunk.reversed() else chunk }
    return TestPhoneFiles(order, tags)
}

// Today's scan of what the fake media library lists now, against `saved`.
internal suspend fun todayOf(saved: List<FileTagsEntity>, excluded: Set<String> = emptySet()): TodayPhoneScan {
    val scanner = DeviceScanner(ApplicationProvider.getApplicationContext<Application>()).also { it.nowSeconds = { PHONE_NOW } }
    return todayPhoneScan(scanner.list(), saved, { null }, excluded)
}

// The phone's rows in the library, in the source's order.
internal suspend fun CatalogDatabase.phoneTracks(): List<SourceTrackEntity> =
    workDao().trackPage(RoomRawQuery("SELECT * FROM source_track WHERE sourceId = 'device' ORDER BY seq"))

// Where each phone song sits, by media id, from the library's stored
// places: what the folders page builds its tree from.
internal suspend fun CatalogDatabase.storedPaths(): Map<Long, List<String>> {
    val dao = phoneScanDao()
    val where = PhonePaths(dao.phoneVolumes().map { it.storageVolume })
    val paths = HashMap<Long, List<String>>()
    var after = -1L
    while (true) {
        val page = dao.phonePlaces(after, SCAN_PAGE)
        if (page.isEmpty()) return paths
        for (place in page) place.nativeId.toLongOrNull()?.let { paths[it] = where.of(place.folder, place.storageVolume) }
        after = page.last().seq
    }
}

internal suspend fun CatalogDatabase.phoneAlbums(): List<SourceAlbumEntity> =
    workDao().albumPage(RoomRawQuery("SELECT * FROM source_album WHERE sourceId = 'device' ORDER BY seq"))

internal suspend fun CatalogDatabase.phoneArtists(): List<SourceArtistEntity> =
    workDao().artistPage(RoomRawQuery("SELECT * FROM source_artist WHERE sourceId = 'device' ORDER BY seq"))

// Checks the library's phone rows, the folders and where each file sits
// against today's scan, column by column and place by place: the songs,
// albums and artists in today's order, their places rising (a scan keeps a
// row's earlier place where the order holds, so the numbers are its own).
// The library's own song ids (mergedId) are the merge's and left out.
internal suspend fun assertPhoneIsToday(db: CatalogDatabase, phone: DeviceLibrary, today: TodayPhoneScan) {
    val listed = FakeMediaStore.current!!.files.let { all -> (0 until all.count).associate { all.file(it).let { f -> f.id to f } } }
    val tracks = today.tracks.map { t ->
        val file = listed.getValue(t.nativeId.toLong())
        t.copy(seq = 0, mergedId = "", folder = file.relativePath, fileName = file.displayName, storageVolume = file.volume).withKeys()
    }
    val placed = db.phoneTracks()
    val live = placed.map { it.copy(seq = 0, mergedId = "") }
    assertEquals("songs", tracks.size, live.size)
    tracks.zip(live).forEach { (want, got) -> assertEquals("song ${want.id}", want, got) }
    assertTrue("places rise", placed.zipWithNext().all { (a, b) -> a.seq < b.seq })
    val albums = db.phoneAlbums()
    assertEquals("albums", today.albums.map { it.copy(seq = 0) }, albums.map { it.copy(seq = 0) })
    assertTrue("album places rise", albums.zipWithNext().all { (a, b) -> a.seq < b.seq })
    val artists = db.phoneArtists()
    assertEquals("artists", today.artists.map { it.copy(seq = 0) }, artists.map { it.copy(seq = 0) })
    assertTrue("artist places rise", artists.zipWithNext().all { (a, b) -> a.seq < b.seq })
    assertEquals("folders", today.folders, phone.folders.value)
    assertEquals("paths", today.paths, db.storedPaths())
    val files = db.phoneTracks().associate { it.nativeId.toLong() to relativeFile(it.folder, it.fileName!!) }
    assertEquals("files", today.files, files)
}

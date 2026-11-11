package app.winters.octo.playback

import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.catalog.TrackEntity
import app.winters.octo.listening.NOW
import app.winters.octo.listening.TotalsRig
import app.winters.octo.player.PlayerPrefs
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.flow.flowOf

// A radio over a test library, its plays counted by a real totals rig.
internal class RadioRig(
    val db: CatalogDatabase,
    val merge: CatalogMerge = db.testMerge(null),
    page: Int = RADIO_PAGE,
    prefs: PlayerPrefs = PlayerPrefs(),
) {
    val totals = TotalsRig(db, merge)
    var suggestions: List<TrackEntity> = emptyList()
    val radio = LibraryRadio(db, totals.totals, { _, _ -> suggestions }, flowOf(prefs), page) { NOW }

    suspend fun started(): RadioRig = apply { totals.started() }
}

// A library song as the radio reads it.
internal fun radioTrack(
    id: String,
    sortKey: String = id,
    artist: String = "Artist",
    artistId: String = "ar",
    genre: String = "Rock",
    albumId: String = "al",
    year: Int? = 1995,
    rating: Int = 0,
    durationMs: Long = 200_000,
    title: String = "Title $id",
) = TrackEntity(
    id = id, sourceId = "device", nativeId = id, title = title, searchKey = sortKey, sortKey = sortKey,
    artist = artist, artistId = artistId, album = "Album $albumId", albumId = albumId, trackNo = null, discNo = null, year = year,
    durationMs = durationMs, addedAt = 0, mimeType = null, sizeBytes = null, artwork = null, uri = null,
    genre = genre, rating = rating,
)

internal suspend fun CatalogDatabase.putTracks(tracks: List<TrackEntity>) = tracks.chunked(500).forEach { dao().insertTracks(it) }

internal suspend fun CatalogDatabase.likeAll(ids: List<String>, at: Long = NOW) =
    ids.forEach { userDao().like(LikedTrackEntity(it, "", at)) }

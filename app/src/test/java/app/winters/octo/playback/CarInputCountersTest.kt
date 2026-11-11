package app.winters.octo.playback

import app.winters.octo.catalog.LikedTrackEntity
import app.winters.octo.listening.ListeningStore
import app.winters.octo.listening.ListeningSync
import app.winters.octo.listening.MemoryPrefs
import app.winters.octo.listening.TestRebuilds
import app.winters.octo.listening.timed
import app.winters.octo.offline.DownloadBook
import app.winters.octo.offline.DownloadStatus
import app.winters.octo.offline.downloadRow
import app.winters.octo.testing.openTestCatalog
import app.winters.octo.testing.testMerge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The car's start-up inputs for likes, downloads and playlists as bounded
// values: a count of like changes, the downloaded-set counter and each
// playlist's id, name and song count. A like, a finished download and a
// playlist renamed each tell the car once; progress ticks and failures
// tell it nothing.
@RunWith(RobolectricTestRunner::class)
class CarInputCountersTest {
    private val db = openTestCatalog()

    @After
    fun close() = db.close()

    @Test
    fun eachKindTellsTheCarOnceAndTicksAndFailuresNever() = timed(30) {
        val rows = db.listeningDao()
        val listening = ListeningSync({ null }, db.sourceDao(), db.userDao(), db.dao(), rows, ListeningStore(MemoryPrefs(), MutableStateFlow(null), rows), TestRebuilds())
        val likes = LikeStore(db.userDao(), db.dao(), listening, db, app.winters.octo.catalog.SongIds(db, db.testMerge(null)))
        val book = DownloadBook(db.downloadDao())
        db.dao().insertTracks(listOf(songRow("device:s0", "al0", "art:0")))
        db.playlist("p", listOf("device:s0"))
        book.upsert(listOf(downloadRow("device:s0"), downloadRow("device:s1")))
        val summaries = db.summaries()
        val told = Channel<CarInput>(Channel.UNLIMITED)
        // Each input's first value, which the car never hears, says it is
        // watching.
        val likesWatched = CompletableDeferred<Unit>()
        val playlistsWatched = CompletableDeferred<Unit>()
        coroutineScope {
            val car = launch {
                carInputs(
                    listOf(
                        CarInput.Liked to likes.changes.onEach { likesWatched.complete(Unit) },
                        CarInput.Downloads to book.doneVersion,
                        CarInput.Playlists to summaries.all.map { all -> all.map { Triple(it.id, it.name, it.songCount) } }
                            .onEach { playlistsWatched.complete(Unit) },
                    ),
                ).collect { told.send(it) }
            }
            suspend fun next() = withTimeout(5_000) { told.receive() }
            withTimeout(5_000) { likesWatched.await() }
            withTimeout(5_000) { playlistsWatched.await() }

            db.userDao().like(LikedTrackEntity("device:s0", "key:device:s0", 1))
            assertEquals(CarInput.Liked, next())

            book.markDownloading("server:h", "device:s0")
            repeat(10) { book.setProgress("server:h", "device:s0", it / 10f) }
            repeat(3) { book.setState("server:h", "device:s1", DownloadStatus.Failed) }
            book.finish("server:h", "device:s0", "/m/s0.flac", 10, "audio/flac", 10)
            assertEquals("the ticks and failures before it told nothing", CarInput.Downloads, next())

            db.userDao().renamePlaylist("p", "Road", now = 5)
            assertEquals(CarInput.Playlists, next())

            db.userDao().unlike("device:s0")
            assertEquals(CarInput.Liked, next())
            assertTrue("each told once", told.tryReceive().isFailure)
            car.cancel()
        }
    }
}

package app.winters.octo.listening

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.data.accountOfServerKey
import app.winters.octo.testing.openTestCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// What keeping in step remembers is kept per account, as rows, and what an
// older version kept in its preferences file is taken in once.
@RunWith(RobolectricTestRunner::class)
class ListeningStoreTest {
    private lateinit var db: CatalogDatabase

    @Before
    fun open() {
        db = openTestCatalog()
    }

    @After
    fun close() = db.close()

    private val oldValues
        get() = mutablePreferencesOf(
            stringSetPreferencesKey("synced_stars@home") to setOf("s1", "s2"),
            stringSetPreferencesKey("pending_plays@home") to setOf("1000 s1", "900 s4"),
            stringSetPreferencesKey("sent_plays@home") to setOf("2 3000,4000 s1"),
            stringSetPreferencesKey("synced_ratings@work") to setOf("4 s9"),
            stringPreferencesKey("unrelated") to "kept",
        )

    @Test
    fun anOlderVersionsValuesAnswerTheSameAndLeaveThePreferencesFile() = runBlocking {
        val prefs = MemoryPrefs(oldValues)
        val dao = db.listeningDao()
        val store = ListeningStore(prefs, MutableStateFlow("home"), dao)
        assertEquals(listOf(PendingPlay("s4", 900), PendingPlay("s1", 1_000)), store.pending("home"))
        assertEquals(mapOf("s1" to SentPlays(2, listOf(3_000, 4_000))), store.sent.first())
        assertEquals(setOf("s1", "s2"), dao.syncedStarsAmong("home", listOf("s1", "s2", "s3")).toSet())
        assertEquals(4, dao.syncedRatingsAmong("work", listOf("s9")).single().rating)
        val left = prefs.data.first().asMap().keys.map { it.name }
        assertEquals(listOf("unrelated"), left)
    }

    @Test
    fun aCrashBeforeTheOldValuesGoTakesNothingInTwice() = runBlocking {
        val dao = db.listeningDao()
        // The rows and the mark committed; the old values were not removed.
        dao.importAll(importOf(oldValues), 1)
        val changed = oldValues.apply { this[stringSetPreferencesKey("synced_stars@home")] = setOf("s1", "s2", "s3") }
        val prefs = MemoryPrefs(changed)
        val store = ListeningStore(prefs, MutableStateFlow("home"), dao)
        assertEquals(2, store.pending("home").size)
        assertEquals(setOf("s1", "s2"), dao.syncedStarsAmong("home", listOf("s1", "s2", "s3")).toSet())
        assertEquals(listOf("unrelated"), prefs.data.first().asMap().keys.map { it.name })
    }

    @Test
    fun playsWaitingForAServerStayWithItAcrossASwitch() = runBlocking {
        val account = MutableStateFlow<String?>("home")
        val dao = db.listeningDao()
        val store = ListeningStore(MemoryPrefs(), account, dao)
        store.addPending("home", PendingPlay("song-1", 1_000))
        store.addSyncedStars("home", listOf("song-1"))
        account.value = "work"
        store.addPending("work", PendingPlay("song-9", 2_000))
        assertEquals(listOf(PendingPlay("song-1", 1_000)), store.pending("home"))
        assertEquals(listOf(PendingPlay("song-9", 2_000)), store.pending("work"))
        assertEquals(listOf("song-1"), dao.syncedStarsAmong("home", listOf("song-1")))
        assertTrue(dao.syncedStarsAmong("work", listOf("song-1")).isEmpty())
        store.finishPending("home", PendingPlay("song-1", 1_000), sent = true)
        account.value = "home"
        assertEquals(setOf("song-1"), store.sent.first().keys)
        store.forget("work")
        assertTrue(store.pending("work").isEmpty())
        assertTrue(store.pending("home").isEmpty())
    }

    @Test
    fun anOlderVersionsPlaysBelongToItsServer() = runBlocking {
        val url = "https://music.example.com/"
        val home = accountOfServerKey("winters@$url")
        val old = mutablePreferencesOf(
            stringPreferencesKey("server") to "winters@$url",
            stringSetPreferencesKey("pending_plays") to setOf("1000 song-1"),
            stringSetPreferencesKey("synced_stars") to setOf("song-2"),
        )
        val dao = db.listeningDao()
        val store = ListeningStore(MemoryPrefs(old), MutableStateFlow(home), dao)
        assertEquals(listOf(PendingPlay("song-1", 1_000)), store.pending(home))
        assertEquals(listOf("song-2"), dao.syncedStarsAmong(home, listOf("song-2")))
        assertTrue(store.pending("other").isEmpty())
    }

    @Test
    fun pastTheCapTheOldestPlaysGo() = runBlocking {
        val store = ListeningStore(MemoryPrefs(), MutableStateFlow("home"), db.listeningDao())
        for (n in 1..MAX_PENDING_PLAYS + 5) store.addPending("home", PendingPlay("s", n.toLong()))
        val left = store.pending("home")
        assertEquals(MAX_PENDING_PLAYS, left.size)
        assertEquals(6L, left.first().startedAt)
    }
}

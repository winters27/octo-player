package app.winters.octo.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.winters.octo.listening.FavouriteKind
import app.winters.octo.listening.FavouriteSyncStore
import app.winters.octo.listening.ListeningStore
import app.winters.octo.listening.PendingPlay
import app.winters.octo.livelists.accountKey
import app.winters.octo.playlists.PlaylistSyncStore
import app.winters.octo.playlists.RecentPlaylists
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// What the phone keeps for each server is kept per account: a switch to
// another server and back loses none of it, and what an older version kept
// for its one server becomes that server's.
class AccountStoresTest {
    private class MemoryPrefs(start: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(start)
        private val lock = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    private val url = "https://music.example.com/"
    private val home = accountKey("winters", url)

    @Test
    fun anOlderVersionsKeyIsTheKeptServersId() {
        assertEquals(home, accountOfServerKey("winters@$url"))
        assertEquals(accountKey("me@mail.test", url), accountOfServerKey("me@mail.test@$url"))
    }

    @Test
    fun playsWaitingForAServerStayWithItAcrossASwitch() = runBlocking {
        val account = MutableStateFlow<String?>("home")
        val store = ListeningStore(MemoryPrefs(), account)
        store.addPending("home", PendingPlay("song-1", 1_000))
        store.updateSynced("home") { it + "song-1" }
        account.value = "work"
        store.addPending("work", PendingPlay("song-9", 2_000))
        assertEquals(listOf(PendingPlay("song-1", 1_000)), store.pending("home"))
        assertEquals(listOf(PendingPlay("song-9", 2_000)), store.pending("work"))
        assertEquals(setOf("song-1"), store.synced("home"))
        assertTrue(store.synced("work").isEmpty())
        store.finishPending("home", PendingPlay("song-1", 1_000), sent = true)
        account.value = "home"
        assertEquals(setOf("song-1"), store.sent.first().keys)
        store.forget("work")
        assertTrue(store.pending("work").isEmpty())
        assertTrue(store.pending("home").isEmpty())
    }

    @Test
    fun anOlderVersionsPlaysBelongToItsServer() = runBlocking {
        val old = mutablePreferencesOf(
            stringPreferencesKey("server") to "winters@$url",
            stringSetPreferencesKey("pending_plays") to setOf("1000 song-1"),
            stringSetPreferencesKey("synced_stars") to setOf("song-2"),
        )
        val store = ListeningStore(MemoryPrefs(old), MutableStateFlow(home))
        assertEquals(setOf("song-2"), store.synced(home))
        assertEquals(listOf(PendingPlay("song-1", 1_000)), store.pending(home))
        assertTrue(store.pending("other").isEmpty())
    }

    @Test
    fun favouriteStarsAreKeptPerServer() = runBlocking {
        val old = mutablePreferencesOf(
            stringPreferencesKey("server") to "winters@$url",
            stringSetPreferencesKey("synced_albums") to setOf("al-1"),
        )
        val store = FavouriteSyncStore(MemoryPrefs(old))
        assertEquals(setOf("al-1"), store.synced(home, FavouriteKind.Album))
        store.updateSynced("work", FavouriteKind.Album) { it + "al-9" }
        assertEquals(setOf("al-1"), store.synced(home, FavouriteKind.Album))
        assertEquals(setOf("al-9"), store.synced("work", FavouriteKind.Album))
    }

    @Test
    fun playlistLinksGoOnlyForAnotherAccountOnTheSameAddress() = runBlocking {
        val old = mutablePreferencesOf(
            stringPreferencesKey("server") to "winters@$url",
            stringSetPreferencesKey("deleted") to setOf("pl-1"),
        )
        val store = PlaylistSyncStore(MemoryPrefs(old))
        assertEquals(setOf("pl-1"), store.deleted(home))
        assertFalse("the account an older version linked them for keeps them", store.claim("server:music.example.com", home))
        assertFalse("another server's links are its own", store.claim("server:work.example.com", "work"))
        assertFalse(store.claim("server:music.example.com", home))
        assertTrue("another account on the same address lets them go", store.claim("server:music.example.com", "guest"))
        store.forget(home, "server:music.example.com")
        assertTrue(store.deleted(home).isEmpty())
    }

    @Test
    fun recentPlaylistsArePerServerAndStartFromTheOneOldList() = runBlocking {
        val account = MutableStateFlow("home")
        val old = mutablePreferencesOf(stringPreferencesKey("recent") to "a\nb")
        val recent = RecentPlaylists(MemoryPrefs(old), account)
        assertEquals(listOf("a", "b"), recent.ids.first())
        recent.used("c")
        assertEquals(listOf("c", "a", "b"), recent.ids.first())
        account.value = "work"
        assertEquals("another server starts from the old list", listOf("a", "b"), recent.ids.first())
        recent.used("z")
        assertEquals(listOf("z", "a", "b"), recent.ids.first())
        account.value = "home"
        assertEquals(listOf("c", "a", "b"), recent.ids.first())
    }
}

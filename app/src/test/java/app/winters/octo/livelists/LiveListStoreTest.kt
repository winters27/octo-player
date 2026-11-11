package app.winters.octo.livelists

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.winters.octo.data.Session
import app.winters.octo.data.SessionState
import app.winters.octo.query.FilterPresets
import app.winters.octo.query.LibraryQuery
import app.winters.octo.query.QuerySort
import app.winters.octo.subsonic.Credentials
import app.winters.octo.subsonic.SubsonicClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The phone's live lists: kept per account and read back after a restart.
// The songs each picks are LiveListSongsTest's.
class LiveListStoreTest {
    private val account = MutableStateFlow("one")
    private var now = 1_000L

    // The store's values in memory: the file-backed store cannot replace
    // its file on a Windows JVM, which the phone never is.
    private class MemoryPrefs : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            lock.withLock { transform(state.value).also { state.value = it } }
    }

    private val data = MemoryPrefs()

    private fun store() = LiveListStore(data, account) { now }

    private val favourites = LibraryQuery(listOf(FilterPresets.Favourites), sort = QuerySort("Title"))

    @Test
    fun listsAreKeptAndChangedPerAccount() = runBlocking {
        val store = store()
        assertEquals(emptyList<LiveList>(), store.lists.first())
        val made = store.save(LiveList.new(" Favorites ", favourites, 0, "f"))
        assertEquals("Favorites", made.name)
        assertEquals(1_000L, made.created)
        now = 2_000
        val copy = store.duplicate(made)
        store.rename("f", "Hearts")
        assertEquals(listOf("Hearts", "Favorites (copy)"), store.lists.first().map { it.name })
        assertEquals(2_000L, store.byId("f")?.changed)

        // Another account starts with none, and the first keeps its own.
        account.value = "two"
        assertEquals(emptyList<LiveList>(), store.lists.first())
        store.save(LiveList.new("Theirs", favourites, 0, "t"))
        account.value = "one"
        assertEquals(listOf("f", copy.id), store.lists.first().map { it.id })

        store.remove("f")
        assertEquals(listOf(copy.id), store.lists.first().map { it.id })
        assertNull(store.byId("f"))
        // Read again, as after a restart.
        assertEquals(listOf("Favorites (copy)"), store().lists.first().map { it.name })
    }

    @Test
    fun theAccountIsTheSignedInServersOrThePhonesOwn() {
        assertNull(accountOf(SessionState.Loading))
        assertEquals(PHONE_ACCOUNT, accountOf(SessionState.SignedOut))
        fun signedIn(user: String, url: String) = SessionState.SignedIn(
            Session(SubsonicClient(url.toHttpUrl(), Credentials(user, "pw"), OkHttpClient()), null, null, isOcto = false, adminReachable = false, extensions = emptySet()),
        )
        val a = accountOf(signedIn("winters", "https://music.example/"))
        assertEquals(a, accountOf(signedIn("winters", "https://music.example/")))
        assertTrue(a != accountOf(signedIn("guest", "https://music.example/")))
        assertEquals(accountKey("winters", "https://music.example/"), a)
    }

}

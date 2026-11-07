package app.winters.octo.connection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import java.net.InetAddress

// Helpers for tests of the app's own connection: a phone id kept in memory,
// a connection security as the app makes one (Robolectric, for the app's
// context), and a test server at an address by IP, which counts as the home
// network, so gateway headers go to it over plain http.

internal class MemoryPrefs : DataStore<Preferences> {
    private val current = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = current

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(current.value).also { current.value = it }
}

internal fun testDevices(): DeviceIds = DeviceIds(MemoryPrefs()) { "Pixel 9" }

internal fun testSecurity(): ConnectionSecurity = ConnectionSecurity(ApplicationProvider.getApplicationContext(), testDevices())

internal fun MockWebServer.startHome(): HttpUrl {
    start(InetAddress.getByName("127.0.0.1"), 0)
    return url("/").newBuilder().host("127.0.0.1").build()
}

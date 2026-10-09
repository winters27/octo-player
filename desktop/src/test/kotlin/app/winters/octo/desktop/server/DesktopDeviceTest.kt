package app.winters.octo.desktop.server

import app.winters.octo.desktop.FakeServer
import app.winters.octo.desktop.secrets.SessionOnlySecrets
import app.winters.octo.desktop.settings.DesktopOs
import app.winters.octo.desktop.settings.SettingsStore
import app.winters.octo.subsonic.DEVICE_ID_HEADER
import app.winters.octo.subsonic.DEVICE_NAME_HEADER
import app.winters.octo.subsonic.DeviceIdentity
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// The computer's id and name go to the signed-in server only, the id is
// made once and kept, and the app names itself and its system to every host.
class DesktopDeviceTest {
    @get:Rule val folder = TemporaryFolder()

    private val file get() = File(folder.root, "settings.json")

    @Test
    fun theIdIsMadeOnceAndKept() {
        val settings = SettingsStore(file)
        assertEquals("", settings.current.deviceId)
        val id = deviceId(settings)
        assertTrue(id.length == 36)
        assertEquals(id, deviceId(settings))
        // A later run has the same one.
        assertEquals(id, deviceId(SettingsStore(file)))
    }

    @Test
    fun theUserAgentNamesVersionAndSystem() {
        assertEquals("Octo/1.6.0 (Windows)", desktopUserAgent("1.6.0", DesktopOs.Windows))
        assertEquals("Octo/1.6.0 (macOS)", desktopUserAgent("1.6.0", DesktopOs.Mac))
        assertEquals("Octo/dev (Linux)", desktopUserAgent(null, DesktopOs.Linux))
        assertEquals("Octo/dev (Linux)", desktopUserAgent(" ", DesktopOs.Linux))
    }

    @Test
    fun theComputerNameComesFromTheSystem() {
        assertEquals("STUDIO-PC", computerName { if (it == "COMPUTERNAME") "STUDIO-PC" else null })
        assertEquals("studio", computerName { if (it == "HOSTNAME") "studio" else null })
    }

    @Test
    fun deviceHeadersOnlyToOwnServer() = runTest {
        FakeServer().use { own ->
            FakeServer().use { other ->
                own.answer("ping")
                own.answer("getOpenSubsonicExtensions", """"openSubsonicExtensions":[]""")
                other.answer("ping")
                val settings = SettingsStore(file)
                val security = ServerSecurity(settings) { DeviceIdentity(deviceId(settings), "Studio PC") }
                val http = security.install(OkHttpClient.Builder().addInterceptor(userAgentOf("Octo/1.6.0 (Windows)"))).build()
                val accounts = Accounts(settings, SessionOnlySecrets(), http, security)
                val done = accounts.signIn(own.address, "winters", "pw") as SignInOutcome.Done
                // Once signed in, every call names the computer.
                done.connection.client.ping()

                val mine = own.calls.last()
                assertEquals(settings.current.deviceId, mine.headers[DEVICE_ID_HEADER])
                // The name goes percent-encoded, as any name may.
                assertEquals("Studio%20PC", mine.headers[DEVICE_NAME_HEADER])
                assertEquals("Octo/1.6.0 (Windows)", mine.headers["User-Agent"])

                http.newCall(Request.Builder().url(other.address.toHttpUrl().resolve("rest/ping")!!).build()).execute().close()
                val elsewhere = other.calls.last()
                assertNull(elsewhere.headers[DEVICE_ID_HEADER])
                assertNull(elsewhere.headers[DEVICE_NAME_HEADER])
                // The app's own name goes everywhere, as on the phone.
                assertEquals("Octo/1.6.0 (Windows)", elsewhere.headers["User-Agent"])
                // And the engine is given them for its own requests.
                assertEquals("Studio%20PC", security.deviceHeaders()[DEVICE_NAME_HEADER])
                assertEquals("Studio PC", security.deviceName())
            }
        }
    }
}

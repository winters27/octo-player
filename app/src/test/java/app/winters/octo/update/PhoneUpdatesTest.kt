package app.winters.octo.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneUpdatesTest {
    // A store held in memory, standing in for the phone's.
    private class MemoryStore : DataStore<Preferences> {
        val current = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = current

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(current.value).also { current.value = it }
    }

    @Test
    fun theUpdateSettingsStartAtTheirDefaultsAndAreKept() = runBlocking {
        val store = MemoryStore()
        assertEquals(UpdatePrefs(), UpdateSettings(store).prefs.first())
        UpdateSettings(store).apply {
            setCheckAutomatically(false)
            setInstall(InstallWhen.OnQuit)
            setEarlyVersions(true)
        }
        // Read again, as after a restart.
        assertEquals(UpdatePrefs(checkAutomatically = false, install = InstallWhen.OnQuit, earlyVersions = true), UpdateSettings(store).prefs.first())
    }

    private val installed = ApkFacts("app.winters.octo", 300, setOf("aa"), setOf("aa"))

    @Test
    fun onlyThisAppNewerAndSignedWithItsKeyInstalls() {
        assertNull(apkRefusal(installed, ApkFacts("app.winters.octo", 310, setOf("aa"), setOf("aa"))))
        assertNotNull(apkRefusal(installed, null))
        assertNotNull(apkRefusal(installed, ApkFacts("app.winters.octo.debug", 310, setOf("aa"))))
        assertNotNull(apkRefusal(installed, ApkFacts("app.winters.octo", 300, setOf("aa"))))
        assertNotNull(apkRefusal(installed, ApkFacts("app.winters.octo", 299, setOf("aa"))))
        assertEquals("the download is signed with another key", apkRefusal(installed, ApkFacts("app.winters.octo", 310, setOf("bb"), setOf("bb"))))
        assertNotNull(apkRefusal(installed, ApkFacts("app.winters.octo", 310, emptySet())))
        assertNotNull(apkRefusal(installed.copy(signers = emptySet()), ApkFacts("app.winters.octo", 310, setOf("aa"))))
    }

    @Test
    fun aKeyHandedOnToANewOneStillCounts() {
        // Signed with a new key whose history names the one this copy has.
        assertNull(apkRefusal(installed, ApkFacts("app.winters.octo", 310, setOf("cc"), setOf("aa", "cc"))))
    }

    @Test
    fun onlyTheReleaseBuildUpdatesItself() {
        assertNull(phoneUpdaterOff(debug = false, forced = false, version = "1.2.0", hasKeys = true))
        assertNotNull(phoneUpdaterOff(debug = true, forced = false, version = "1.2.0", hasKeys = true))
        assertNotNull(phoneUpdaterOff(debug = false, forced = false, version = "0.2.0.189-debug", hasKeys = true))
        assertNotNull(phoneUpdaterOff(debug = false, forced = false, version = "1.2.0", hasKeys = false))
        assertNull(phoneUpdaterOff(debug = true, forced = true, version = "1.2.0", hasKeys = true))
        // This test build is one of those: it never looks for updates.
        assertNotNull(phoneUpdaterOff(app.winters.octo.BuildConfig.DEBUG, app.winters.octo.BuildConfig.UPDATES_FORCED, app.winters.octo.BuildConfig.VERSION_NAME, true))
    }

    @Test
    fun thePhonesFileIsTheApk() {
        val assets = listOf(
            ManifestAsset("Octo-1.2.0-windows-x64.msi", "a".repeat(64), 1, "windows", "x64", "msi"),
            ManifestAsset("Octo-1.2.0-android.apk", "b".repeat(64), 1, "android", "any", "apk"),
        )
        assertEquals("Octo-1.2.0-android.apk", apkFor(assets)?.name)
        assertNull(apkFor(assets.take(1)))
    }
}

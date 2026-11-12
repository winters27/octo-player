package app.winters.octo.testing

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.winters.octo.catalog.CatalogDatabase
import app.winters.octo.catalog.CatalogMerge
import app.winters.octo.device.DeviceLibrary
import app.winters.octo.device.DeviceScanner
import app.winters.octo.device.FolderRules
import app.winters.octo.device.ScanTuning
import app.winters.octo.device.TagReader
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows.shadowOf

// Folders switched off for a test, set through `switchedOff`, and never
// written to the app's kept preferences, which one process shares across
// tests.
internal class TestFolderRules : FolderRules(ApplicationProvider.getApplicationContext()) {
    val switchedOff = MutableStateFlow<Set<String>>(emptySet())

    override val excluded get() = switchedOff
}

// The phone's side of this library, scanning `files` through the fake media
// library with access to the phone's music granted, merged by `merge`, with
// `space` bytes free, the folders `rules` switches off and the scan tuned by
// `tuning`; `now` sets the scan's clock.
internal fun CatalogDatabase.phoneLibrary(
    files: PhoneFiles,
    merge: CatalogMerge = testMerge(null),
    space: Long = Long.MAX_VALUE,
    tuning: ScanTuning = ScanTuning(),
    now: Long? = null,
    rules: FolderRules = TestFolderRules(),
): DeviceLibrary {
    val app = ApplicationProvider.getApplicationContext<Application>()
    shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE)
    FakeMediaStore.install(files)
    val scanner = DeviceScanner(app).also { scanner -> if (now != null) scanner.nowSeconds = { now } }
    return DeviceLibrary(app, scanner, TagReader(app), rules, this, merge) { space }.also { it.scan.tuning = tuning }
}

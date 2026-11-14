package app.winters.octo.testing

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import org.robolectric.util.ReflectionHelpers

// The app targets SDK 36, where clearing focus in touch mode leaves nothing
// focused. Robolectric runs the unit tests with no manifest, at target SDK
// 23, where Android hands focus back to the first focusable view after a
// clear. This rule gives a test the app's own behavior and puts the old one
// back after it.
class DeviceFocusRule : ExternalResource() {
    private var always = true

    override fun before() {
        // Settles the SDK's view flags first, so the one set below stays.
        View(ApplicationProvider.getApplicationContext())
        always = ReflectionHelpers.getStaticField(View::class.java, "sAlwaysAssignFocus")
        ReflectionHelpers.setStaticField(View::class.java, "sAlwaysAssignFocus", false)
    }

    override fun after() {
        ReflectionHelpers.setStaticField(View::class.java, "sAlwaysAssignFocus", always)
    }
}

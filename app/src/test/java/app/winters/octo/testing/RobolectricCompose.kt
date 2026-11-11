package app.winters.octo.testing

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf

// A Compose test rule for Robolectric tests. The unit tests run without the
// app's merged manifest and resources, so the empty activity Compose shows
// content in is declared to the package manager here, before the rule opens
// it, as the test manifest would declare it. Marks and rows are asserted
// through their state and semantics, never by drawing icons.
class RobolectricComposeRule : TestRule {
    val compose: ComposeContentTestRule = createComposeRule()

    override fun apply(base: Statement, description: Description): Statement {
        val inner = compose.apply(base, description)
        return object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                inner.evaluate()
            }
        }
    }
}

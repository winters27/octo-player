package app.winters.octo.ui.family

import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Every family link the camera or a browser hands over opens this app:
// invites, sign-ins to fill in, and sign-ins handed over from another device.
@RunWith(AndroidJUnit4::class)
class FamilyLinkFilterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun opensThisApp(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    }

    @Test
    fun aHandedOverSignInOpensThisApp() {
        assertTrue(opensThisApp("octo://handover?t=tok_9&k=a_b-c&s=https%3A%2F%2Fmusic.example.com&h=http%3A%2F%2F192.168.1.20%3A4533&u=alex"))
    }

    @Test
    fun invitesAndSignInsStillOpenThisApp() {
        assertTrue(opensThisApp("octo://join?server=https%3A%2F%2Fmusic.example.com&invite=tok_sam"))
        assertTrue(opensThisApp("octo://signin?server=https%3A%2F%2Fmusic.example.com&username=alex"))
    }
}

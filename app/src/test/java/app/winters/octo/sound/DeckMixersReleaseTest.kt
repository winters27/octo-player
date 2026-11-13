package app.winters.octo.sound

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A mixer made for a service starts its own mix, builder and gate threads;
// releasing it ends all three, so a service that comes and goes leaves no
// thread behind.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeckMixersReleaseTest {
    private fun mixThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("octo-mix") }

    @Test
    fun releaseEndsTheMixersThreads() {
        val before = mixThreads().toSet()
        val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        val mixer = DeckMixers.create(ApplicationProvider.getApplicationContext(), attributes, 1, object : MixListener {})
        val started = mixThreads().filter { it !in before }
        assertEquals("the mix, builder and gate threads", 3, started.size)

        assertTrue("the mixer released", mixer.release())
        for (t in started) t.join(2_000)
        assertTrue("threads left: ${started.filter { it.isAlive }.map { it.name }}", started.none { it.isAlive })
    }
}

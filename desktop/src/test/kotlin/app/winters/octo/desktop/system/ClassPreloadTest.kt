package app.winters.octo.desktop.system

import org.junit.Assert.assertTrue
import org.junit.Test

// The start's class list still fits the app: most of what it names is
// there to load. When dependencies change and this fails, make the list
// again (scripts/startup-classes.py); a stale list only reads ahead less.
class ClassPreloadTest {
    @Test
    fun mostListedClassesStillExist() {
        val (loaded, listed) = loadListed(javaClass.classLoader)
        assertTrue("no list", listed > 1000)
        assertTrue("only $loaded of $listed listed classes load; make the list again", loaded >= listed * 9 / 10)
    }
}

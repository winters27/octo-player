package app.winters.octo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVersionTest {
    private fun v(text: String) = PlayerVersion.parse(text)!!

    @Test
    fun versionsOrderByTheirNumbers() {
        assertTrue(v("1.2.0") > v("1.1.9"))
        assertTrue(v("1.10.0") > v("1.9.0"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.0.324") > v("1.0.99"))
        assertEquals(v("1.2.3"), v("1.2.3"))
    }

    @Test
    fun anEarlyVersionComesBeforeItsRelease() {
        assertTrue(v("1.3.0-beta.1") < v("1.3.0"))
        assertTrue(v("1.3.0-beta.1") > v("1.2.9"))
        assertTrue(v("1.3.0-beta.2") > v("1.3.0-beta.1"))
        assertTrue(v("1.3.0-beta.10") > v("1.3.0-beta.9"))
        assertTrue(v("1.3.0-rc.1") > v("1.3.0-beta.5"))
        // Numbers come before words, and a longer list after its start.
        assertTrue(v("1.3.0-1") < v("1.3.0-alpha"))
        assertTrue(v("1.3.0-beta") < v("1.3.0-beta.1"))
        assertTrue(v("1.3.0-beta.1").isEarly)
    }

    @Test
    fun whatIsNotAVersionIsNull() {
        assertNull(PlayerVersion.parse("Development build"))
        assertNull(PlayerVersion.parse(null))
        assertNull(PlayerVersion.parse("1.2"))
        assertNull(PlayerVersion.parse("1.2.3.4"))
        // The phone's test builds carry four numbers and a suffix.
        assertNull(PlayerVersion.parse("0.2.0.189-debug"))
        assertNull(PlayerVersion.parse("01.2.3"))
        assertNull(PlayerVersion.parse("v1.2.3"))
        assertNull(PlayerVersion.parse("1.2.3-"))
    }

    @Test
    fun onlyThisAppsOwnTagsNameAVersion() {
        assertEquals(v("1.2.3"), versionOfTag("desktop-v1.2.3", PlayerApp.Desktop))
        assertEquals(v("0.2.0"), versionOfTag("android-v0.2.0", PlayerApp.Android))
        assertEquals(v("1.3.0-beta.1"), versionOfTag("desktop-v1.3.0-beta.1", PlayerApp.Desktop))
        // The other app's.
        assertNull(versionOfTag("android-v0.2.0", PlayerApp.Desktop))
        assertNull(versionOfTag("desktop-v1.2.3", PlayerApp.Android))
        // The server's, dated or with a bare v.
        for (tag in listOf("2026.09.23", "2026.09.23.1", "v1.2.3", "v2026.09.23", "1.2.3")) {
            assertNull(tag, versionOfTag(tag, PlayerApp.Desktop))
            assertNull(tag, versionOfTag(tag, PlayerApp.Android))
        }
        assertNull(versionOfTag("desktop-v1.2", PlayerApp.Desktop))
        assertNull(versionOfTag("desktop-1.2.3", PlayerApp.Desktop))
    }

    // The server's image workflow builds on tags matching v* and
    // [0-9][0-9][0-9][0-9].*; a player tag must never match either, or
    // publishing a player would publish a server image too.
    @Test
    fun playerTagsNeverMatchTheServersImageTags() {
        val serverPatterns = listOf(Regex("v.*"), Regex("[0-9][0-9][0-9][0-9]\\..*"))
        for (tag in listOf("desktop-v1.2.3", "android-v0.2.0", "desktop-v1.3.0-beta.1", "android-v10.0.0")) {
            assertTrue(tag, serverPatterns.none { it.matches(tag) })
            assertTrue(tag, PlayerApp.entries.any { versionOfTag(tag, it) != null })
        }
    }

    private fun release(tag: String, draft: Boolean = false, pre: Boolean = false) = GitHubRelease(tag, draft = draft, prerelease = pre)

    private val releases = listOf(
        release("2026.09.23"),
        release("2026.12.31"),
        release("v9.9.9"),
        release("desktop-v1.1.0"),
        release("desktop-v1.2.0"),
        release("desktop-v1.4.0", draft = true),
        release("desktop-v1.3.0-beta.1", pre = true),
        release("android-v5.0.0"),
    )

    @Test
    fun theNewestFinalReleaseOfThisAppIsOffered() {
        val found = newestUpdate(releases, PlayerApp.Desktop, v("1.0.324"), early = false)
        assertEquals("desktop-v1.2.0", found?.release?.tag)
    }

    @Test
    fun draftsAndEarlyVersionsWaitUnlessAskedFor() {
        val early = newestUpdate(releases, PlayerApp.Desktop, v("1.2.0"), early = true)
        assertEquals("desktop-v1.3.0-beta.1", early?.release?.tag)
        assertNull(newestUpdate(releases, PlayerApp.Desktop, v("1.2.0"), early = false))
        // A tag that says early is early, whatever GitHub's flag says.
        val mislabelled = listOf(release("desktop-v1.5.0-rc.1"))
        assertNull(newestUpdate(mislabelled, PlayerApp.Desktop, v("1.2.0"), early = false))
        // And a release GitHub flags early is early whatever its tag.
        val flagged = listOf(release("desktop-v1.5.0", pre = true))
        assertNull(newestUpdate(flagged, PlayerApp.Desktop, v("1.2.0"), early = false))
    }

    @Test
    fun anEarlyVersionIsOfferedItsRelease() {
        val list = listOf(release("desktop-v1.3.0"), release("desktop-v1.3.0-beta.1", pre = true))
        assertEquals("desktop-v1.3.0", newestUpdate(list, PlayerApp.Desktop, v("1.3.0-beta.1"), early = false)?.release?.tag)
    }

    @Test
    fun nothingNewerIsNothing() {
        assertNull(newestUpdate(releases, PlayerApp.Desktop, v("1.2.0"), early = false))
        assertNull(newestUpdate(releases, PlayerApp.Android, v("5.0.0"), early = true))
        assertEquals("android-v5.0.0", newestUpdate(releases, PlayerApp.Android, v("0.2.0"), early = false)?.release?.tag)
    }

    @Test
    fun githubsListIsRead() {
        val text = """[{"tag_name":"desktop-v1.2.0","name":"Octo for Windows, macOS and Linux 1.2.0","draft":false,"prerelease":false,
            "body":"Notes","extra":1,"assets":[{"name":"update.json","size":512,"browser_download_url":"https://example.com/update.json","id":7}]}]"""
        val list = parseReleases(text)
        assertEquals("desktop-v1.2.0", list.single().tag)
        assertEquals(512L, list.single().assets.single().size)
    }
}

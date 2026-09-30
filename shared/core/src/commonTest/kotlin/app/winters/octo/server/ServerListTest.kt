package app.winters.octo.server

import app.winters.octo.livelists.accountKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The words both apps show about kept servers, and how an older app's one
// server becomes the list.
class ServerListTest {
    private data class Kept(val id: String, val username: String, val address: String, val label: String = "", val signedOut: Boolean = false)

    private fun settle(listed: List<Kept>, legacy: Kept?) = settleServers(
        listed,
        legacy,
        id = { it.id },
        same = { a, b -> a.username == b.username && a.address == b.address },
        adopt = { old, match -> if (match == null) old else old.copy(id = match.id, label = old.label.ifEmpty { match.label }, signedOut = false) },
    )

    @Test
    fun aServerIsCalledByItsNameElseItsHost() {
        assertEquals("Home", serverName(" Home ", "https://music.example.com/"))
        assertEquals("music.example.com", serverName("", "https://music.example.com:4533/"))
        assertEquals("not an address", serverName("", "not an address/"))
    }

    @Test
    fun theRowSaysWhoAndWhere() {
        assertEquals("winters at music.example.com", accountLine("winters", "https://music.example.com/"))
        assertEquals("music.example.com:4533", accountLine("", "http://music.example.com:4533/"))
    }

    @Test
    fun theStatusLineSaysHowTheServerAnswered() {
        assertEquals("Octo 0.9.3", statusLine("octo", "0.9.3", false, null))
        assertEquals("Octo 0.9.3 · Answered in 38 ms", statusLine("octo", "0.9.3", false, ServerCheck(Reach.Answers, 38)))
        assertEquals("Octo 0.9.3", statusLine("octo", "0.9.3", false, ServerCheck(Reach.Answers, 38), inUse = true))
        assertEquals("Octo 0.9.3 · Out of reach right now", statusLine("octo", "0.9.3", false, ServerCheck(Reach.Unreachable)))
        assertEquals("Octo 0.9.3 · Didn't take the saved password", statusLine("octo", "0.9.3", false, ServerCheck(Reach.WrongPassword)))
        assertEquals("Signed out. Sign in again to use it.", statusLine("octo", "0.9.3", true, ServerCheck(Reach.Answers, 38)))
        assertEquals("Switching to it now", statusLine(null, null, false, null, switching = true))
        assertTrue(ServerCheck(Reach.Unreachable).isWarning())
        assertFalse(ServerCheck(Reach.Answers).isWarning())
        assertFalse(null.isWarning())
    }

    @Test
    fun theSwitchNoticeSaysWhenMusicStopped() {
        assertNull(switchNotice("Work", null, stoppedMusic = true))
        assertEquals("Now on Work.", switchNotice("Work", "Home", stoppedMusic = false))
        assertEquals(
            "Now on Work. The music from Home stopped, and its queue is kept for when you come back.",
            switchNotice("Work", "Home", stoppedMusic = true),
        )
    }

    @Test
    fun aNewIdIsTheAccountsKeyUnlessTaken() {
        val first = accountKey("winters", "https://music.test/")
        assertEquals(first, freshServerId("winters", "https://music.test/", emptySet()))
        assertEquals("$first-2", freshServerId("winters", "https://music.test/", setOf(first)))
        assertEquals("$first-3", freshServerId("winters", "https://music.test/", setOf(first, "$first-2")))
    }

    @Test
    fun theOneServerOfAnOlderAppBecomesAListOfOneInUse() {
        val legacy = Kept("k", "winters", "https://music.test/")
        val (list, inUse) = settle(emptyList(), legacy)
        assertEquals(listOf(legacy), list)
        assertEquals(legacy, inUse)
    }

    @Test
    fun theListStandsAloneWhenNoServerIsInUse() {
        val home = Kept("h", "winters", "https://home.test/")
        val (list, inUse) = settle(listOf(home, home), null)
        assertEquals(listOf(home), list)
        assertNull(inUse)
    }

    @Test
    fun theServerInUseIsBroughtUpToDateInItsPlace() {
        val home = Kept("h", "winters", "https://home.test/", label = "Home", signedOut = true)
        val work = Kept("w", "brandon", "https://work.test/")
        val (list, inUse) = settle(listOf(home, work), Kept("other", "winters", "https://home.test/"))
        assertEquals(listOf("h", "w"), list.map { it.id })
        assertEquals("Home", inUse?.label)
        assertEquals(false, inUse?.signedOut)
    }
}

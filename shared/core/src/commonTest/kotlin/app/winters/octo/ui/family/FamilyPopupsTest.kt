package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyLinkChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

// Which link the popups show (anywhere or at home only), how links and
// addresses read, the countdown, and the steps for each app.
class FamilyPopupsTest {
    private val anywhere = "https://example.com/octo/family/join#invite=t9"
    private val home = "http://192.168.1.20:4533/family/join#invite=t9"

    @Test
    fun anywhereIsTheDefaultWhenTheServerHasIt() {
        val options = LinkOptions(anywhere, home)
        assertTrue(options.choosable)
        assertEquals(LinkReach.Anywhere, options.default)
        assertEquals(anywhere, options.linkFor(LinkReach.Anywhere))
        assertEquals(home, options.linkFor(LinkReach.Home))
    }

    @Test
    fun withoutAnOutsideAddressHomeIsTheDefaultAndAnywhereIsUnavailable() {
        val options = LinkOptions(null, home, anywhereAvailable = false)
        assertTrue(options.choosable)
        assertEquals(LinkReach.Home, options.default)
        assertNull(options.linkFor(LinkReach.Anywhere))
        assertEquals("https://music.example.com/admin/#status", dashboardStatusPage("https://music.example.com/"))
    }

    @Test
    fun someoneKeptHomeOnlyGetsTheHomeLink() {
        val options = LinkOptions(anywhere, home, awayAllowed = false)
        assertFalse(options.choosable)
        assertEquals(LinkReach.Home, options.default)
        assertEquals(home, options.linkFor(LinkReach.Anywhere))
    }

    @Test
    fun anInvitesOneLinkStaysTheOnlyChoice() {
        val one = inviteLinkOptions(anywhere, null, anywhereAvailable = true, homeOnly = false)
        assertFalse(one.choosable)
        assertEquals(anywhere, one.linkFor(one.default))
        val homeOnly = inviteLinkOptions(home, null, anywhereAvailable = false, homeOnly = true)
        assertEquals(home, homeOnly.linkFor(homeOnly.default))
        val both = inviteLinkOptions(anywhere, FamilyLinkChoices(anywhere, home), anywhereAvailable = true, homeOnly = false)
        assertEquals(home, both.linkFor(LinkReach.Home))
    }

    @Test
    fun aLinkShowsWhereItGoesAndItsServer() {
        assertEquals("example.com/octo/family/join…#invite=t9", shownLink(anywhere))
        assertEquals("music.example.com/family/signin…#t=tok_1", shownLink("https://music.example.com/family/signin#t=tok_1&k=key&s=x"))
        assertEquals("music.example.com/family/join", shownLink("https://music.example.com/family/join"))
        assertEquals("music.example.com…#invite=winters", shownLink("https://music.example.com/family/join#invite=winters", 34))
        assertEquals("https://example.com/octo", linkServer(anywhere))
        assertEquals("https://example.com/octo", linkServer("https://example.com/octo/family/signin#t=1"))
        assertEquals("http://192.168.1.20:4533", linkServer(home))
        assertEquals("example.com/octo", shownServer("https://example.com/octo/"))
    }

    @Test
    fun theChoicesSayWhichAddressTheyUse() {
        val options = LinkOptions(anywhere, home)
        assertEquals("Uses https://example.com", reachLine(LinkReach.Anywhere, options))
        assertEquals("Uses your home network (192.168.1.20:4533)", reachLine(LinkReach.Home, options))
        assertNull(reachLine(LinkReach.Anywhere, LinkOptions(null, home, anywhereAvailable = false)))
        assertEquals("Where will you use it?", reachQuestion(own = true))
        assertEquals("Where will they use it?", reachQuestion(own = false))
    }

    @Test
    fun theInvitePopupIsTitledForWhatItIs() {
        assertEquals("Invite Sam", InviteSheet("Sam", "sam", anywhere).title)
        assertEquals("New sign-in link for Sam", InviteSheet("Sam", "sam", anywhere, reset = true).title)
        assertEquals("Sam picks a password, then signs in on any app.", inviteNext("Sam"))
    }

    @Test
    fun theCountdownRunsToTheEnd() {
        val expires = "2026-10-20T18:02:00Z"
        assertEquals(102L, secondsLeft(expires, Instant.parse(expires).minusSeconds(102)))
        assertEquals("Works once · expires in 1:42", countdownLine(102))
        assertFalse(countdownWarns(30))
        assertTrue(countdownWarns(29))
        assertEquals("Works once · getting a new code", countdownLine(0))
        assertNull(countdownAnnouncement(31))
        assertEquals("Thirty seconds left", countdownAnnouncement(30))
    }

    @Test
    fun everyAppHasTwoOrThreeStepsWithTheServerAndUsername() {
        val steps = loginSteps("https://music.example.com", "alex")
        assertEquals(listOf("Octo", "Symfonium", "Feishin", "Amperfy", "Substreamer", "Tempo"), steps.map { it.app })
        steps.forEach { app ->
            assertTrue(app.app, app.steps.size in 2..3)
            assertTrue(app.app, app.steps.any { "https://music.example.com" in it })
            // One login: no app password anywhere.
            assertFalse(app.app, app.steps.any { "app password" in it })
        }
    }
}

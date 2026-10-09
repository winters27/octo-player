package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyDeviceKind
import app.winters.octo.subsonic.FamilyMember
import app.winters.octo.subsonic.FamilyPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// The Add a device and invite popups: the countdown, codes renewed before
// they expire, the app password view, and asking again after a failure.
class DeviceSheetTest {
    private val start = Instant.parse("2026-10-20T18:00:00Z").toEpochMilli()
    private val now = AtomicLong(start)
    private val server = FamilyFakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val model = FamilyModel({ server.client() }, { true }, scope, pollMs = 60_000, clock = now::get, tickMs = 10)
    private val made = AtomicInteger(0)

    // Each code lasts ten minutes from when it was made, by the test's clock.
    private fun answerCodes() = server.answer("addFamilyDevice") { call ->
        val n = made.incrementAndGet()
        val kind = call.url.queryParameter("kind")
        if (kind == "SubsonicApp") {
            """"familyDeviceAdded":{"deviceId":"p_$n","kind":"SubsonicApp","appPassword":"ABCDEFGHJKMNPQRS","server":"https://music.example.com","username":"alex"}"""
        } else {
            val expires = Instant.ofEpochMilli(now.get() + 600_000)
            """"familyDeviceAdded":{"deviceId":"d_$n","kind":"OctoApp","pairCode":"${482912 + n}","expires":"$expires","server":"https://music.example.com","username":"alex"}"""
        }
    }

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun until(what: String, check: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!check()) delay(5) }
        assertTrue(what, check())
    }

    // Long enough for many of the popup's looks at the clock.
    private fun settle() = runBlocking { delay(200) }

    private fun codeCalls() = server.called("addFamilyDevice").filter { it.url.queryParameter("kind") == "OctoApp" }

    private fun openWithCode() {
        answerCodes()
        model.addDevice("Laptop", FamilyDeviceKind.OctoApp)
        until("first code") { model.sheet?.code != null && model.sheet?.loading == false }
        assertEquals("d_1", model.sheet!!.code!!.deviceId)
    }

    @Test
    fun theCountdownRunsToExpiry() {
        val expires = "2026-10-20T18:10:00Z"
        val at = { s: Long -> Instant.parse(expires).minusSeconds(s) }
        assertEquals(582L, secondsLeft(expires, at(582)))
        assertEquals("Works once · expires in 9:42", countdownLine(secondsLeft(expires, at(582))))
        assertFalse(countdownWarns(60))
        assertTrue(countdownWarns(59))
        assertEquals("Works once · expires in 0:05", countdownLine(5))
        assertEquals(0L, secondsLeft(expires, Instant.parse(expires).plusSeconds(30)))
        assertEquals("Works once · getting a new code", countdownLine(0))
        assertEquals("Works once", countdownLine(secondsLeft(null, at(1))))
        // Read aloud only at one minute left and at the end, never every second.
        assertNull(countdownAnnouncement(61))
        assertEquals("One minute left", countdownAnnouncement(60))
        assertEquals("This code expired. Getting a new one.", countdownAnnouncement(0))
    }

    @Test
    fun codesAndPasswordsAreGroupedAndLongLinksKeepBothEnds() {
        assertEquals("482 913", groupedCode("482913"))
        assertEquals("ABCD-EFGH-JKMN-PQRS", groupedPassword("ABCDEFGHJKMNPQRS"))
        assertEquals("ABCD-EFGH-JKMN-PQRS", groupedPassword("ABCD-EFGH-JKMN-PQRS"))
        val link = "https://music.example.com/family/join#u=alex&c=482913"
        val short = middleEllipsized(link, 30)
        assertEquals(30, short.length)
        assertTrue(short.startsWith("https://music.e"))
        assertTrue(short.endsWith("c=482913"))
        assertEquals("https://a.b/c", middleEllipsized("https://a.b/c", 30))
    }

    @Test
    fun aCodeIsRenewedBeforeItExpiresAndEndsTheOldOne() {
        openWithCode()
        val expires = expiresAtMs(model.sheet!!.code!!.expires)!!
        now.set(expires - RENEW_LEAD_MS - 1_000)
        settle()
        assertEquals("not yet", 1, codeCalls().size)

        now.set(expires - RENEW_LEAD_MS + 1_000)
        until("renewed") { model.sheet?.renewed == 1 }
        assertEquals("d_2", model.sheet!!.code!!.deviceId)
        assertEquals("482914", model.sheet!!.code!!.pairCode)
        assertEquals("d_1", codeCalls()[1].url.queryParameter("replaces"))
        assertFalse(model.sheet!!.refreshFailed)
        settle()
        assertEquals("once per code", 2, codeCalls().size)
    }

    @Test
    fun renewingPausesWhileHiddenAndCatchesUpOnReturn() {
        openWithCode()
        model.sheetOnScreen(false)
        now.set(expiresAtMs(model.sheet!!.code!!.expires)!! + 60_000)
        settle()
        assertEquals("paused", 1, codeCalls().size)

        model.sheetOnScreen(true)
        until("replaced at once") { model.sheet?.renewed == 1 }
        assertEquals("d_1", codeCalls()[1].url.queryParameter("replaces"))
    }

    @Test
    fun renewingStopsAfterAnHourAndAsks() {
        openWithCode()
        // Renewed on time until the hour is up.
        var renewals = 0
        while (now.get() - start < RENEW_FOR_MS - 600_000) {
            now.set(expiresAtMs(model.sheet!!.code!!.expires)!! - 1_000)
            renewals += 1
            until("renewal $renewals") { model.sheet?.renewed == renewals }
        }
        now.set(expiresAtMs(model.sheet!!.code!!.expires)!! - 1_000)
        until("asks instead") { model.sheet?.stale == true }
        settle()
        assertEquals("no code past the hour", renewals + 1, codeCalls().size)

        // Make a new code asks for one, ending the last, and renews again.
        val last = model.sheet!!.code!!.deviceId
        model.newCode()
        until("new code") { model.sheet?.stale == false && model.sheet?.code?.deviceId != last && model.sheet?.loading == false }
        assertEquals(last, codeCalls().last().url.queryParameter("replaces"))
        now.set(expiresAtMs(model.sheet!!.code!!.expires)!! - 1_000)
        until("renewing again") { model.sheet?.renewed == renewals + 1 }
    }

    @Test
    fun aFailedRenewalIsTriedAgainEveryTenSeconds() {
        openWithCode()
        server.failWith("addFamilyDevice", 0, "Busy")
        now.set(expiresAtMs(model.sheet!!.code!!.expires)!! - 1_000)
        until("failed") { model.sheet?.refreshFailed == true }
        settle()
        assertEquals(2, codeCalls().size)

        now.addAndGet(RENEW_RETRY_MS - 1_000)
        settle()
        assertEquals("waits ten seconds", 2, codeCalls().size)
        answerCodes()
        now.addAndGet(1_000)
        until("tried again") { model.sheet?.refreshFailed == false && model.sheet?.renewed == 1 }
        assertEquals("d_1", codeCalls().last().url.queryParameter("replaces"))
    }

    @Test
    fun closingThePopupStopsRenewing() {
        openWithCode()
        model.dismissAdded()
        now.set(expiresAtMs("2026-10-20T18:10:00Z")!! + 60_000)
        settle()
        assertEquals(1, codeCalls().size)
        assertNull(model.sheet)
    }

    @Test
    fun theAppPasswordViewShowsItsRowsAndStepsForEachApp() {
        openWithCode()
        model.showOtherApps()
        until("password") { model.sheet?.password != null }
        val sheet = model.sheet!!
        assertEquals(DeviceSheetView.OtherApps, sheet.view)
        assertEquals("Add Symfonium or another app", sheet.title)
        assertEquals("https://music.example.com", sheet.server("fallback"))
        assertEquals("alex", sheet.username("fallback"))
        assertEquals("ABCD-EFGH-JKMN-PQRS", groupedPassword(sheet.password!!.appPassword!!))
        assertEquals("SubsonicApp", server.called("addFamilyDevice").last().url.queryParameter("kind"))

        val steps = otherAppSteps("https://music.example.com", "alex")
        assertEquals(listOf("Symfonium", "Feishin", "Amperfy", "Substreamer", "Tempo"), steps.map { it.app })
        steps.forEach { app ->
            assertTrue(app.app, app.steps.size in 2..3)
            assertTrue(app.app, app.steps.any { "https://music.example.com" in it })
        }

        // Back to the QR code keeps the code it had, and the password is not asked for again.
        model.backToCode()
        assertEquals("d_1", model.sheet!!.code!!.deviceId)
        model.showOtherApps()
        settle()
        assertEquals(1, server.called("addFamilyDevice").count { it.url.queryParameter("kind") == "SubsonicApp" })
    }

    @Test
    fun aMembersPopupIsTitledForThem() {
        answerCodes()
        model.addMemberDevice(FamilyMember(username = "sam", displayName = "Sam"))
        until("code") { model.sheet?.code != null }
        assertEquals("Add a device for Sam", model.sheet!!.title)
        model.showOtherApps()
        assertEquals("Add Symfonium or another app for Sam", model.sheet!!.title)
        until("password") { model.sheet?.password != null }
        assertTrue(server.called("addFamilyDevice").all { it.url.queryParameter("username") == "sam" })
    }

    @Test
    fun aCodeThatCouldNotBeMadeSaysSoAndTriesAgain() {
        server.failWith("addFamilyDevice", 0, "Busy")
        model.addDevice("", FamilyDeviceKind.OctoApp)
        assertTrue("skeleton first", model.sheet!!.waiting)
        until("error") { model.sheet?.error != null }
        assertEquals(CODE_FAILED, model.sheet!!.error)
        assertFalse(model.sheet!!.waiting)
        answerCodes()
        model.retrySheet()
        until("code") { model.sheet?.code != null }
        assertNull(model.sheet!!.error)
    }

    @Test
    fun anInviteGetsANewLink() {
        server.raw("members") { """{"member":{"username":"sam","displayName":"Sam","role":"Kid"},"inviteLink":"https://music.example.com/family/join#invite=t9"}""" }
        server.raw("invite") { """{"inviteLink":"https://music.example.com/family/join#invite=t10"}""" }
        model.addMember("sam", "Sam", FamilyPreset.Kid)
        until("invite") { model.invite != null }
        assertEquals("Invite Sam", model.invite!!.title)
        model.sendNewLink()
        until("new link") { model.invite?.url?.endsWith("t10") == true }
        assertEquals("/api/family/members/sam/invite", server.called("invite").single().url.encodedPath)
        assertEquals("Sam scans this with their phone", inviteCaption("Sam"))
        assertEquals("Sam picks a password, then adds their devices.", inviteNext("Sam"))
    }

    @Test
    fun aMemberKeptHomeGetsHomeLinksOnly() {
        server.raw("members") {
            """{"member":{"username":"sam","displayName":"Sam","role":"Kid"},"inviteLink":"https://example.com/family/join#invite=t9",
            "links":{"anywhere":"https://example.com/family/join#invite=t9","home":"http://192.168.1.20:4533/family/join#invite=t9"},"anywhereAvailable":true}"""
        }
        server.raw("sam") { """{"username":"sam","displayName":"Sam","role":"Kid"}""" }
        model.addMember("sam", "Sam", FamilyPreset.Kid, away = false)
        until("invite") { model.invite != null }
        val edit = server.called("sam").single()
        assertEquals("PUT", edit.method)
        assertEquals("""{"edits":{"away":false}}""", edit.body!!.utf8())
        val options = model.invite!!.options
        assertFalse(options.choosable)
        assertEquals(LinkReach.Home, options.default)
        assertEquals("http://192.168.1.20:4533/family/join#invite=t9", options.linkFor(options.default))

        answerCodes()
        model.addMemberDevice(FamilyMember(username = "sam", displayName = "Sam"))
        assertFalse(model.sheet!!.awayAllowed)
        model.dismissAdded()

        // Allowed away (the switch's default), Anywhere comes first.
        model.closeInvite()
        model.addMember("sam", "Sam", FamilyPreset.Kid)
        until("second invite") { model.invite != null }
        assertEquals("""{"edits":{"away":true}}""", server.called("sam").last().body!!.utf8())
        assertEquals(LinkReach.Anywhere, model.invite!!.options.default)
        model.addMemberDevice(FamilyMember(username = "sam", displayName = "Sam"))
        assertTrue(model.sheet!!.awayAllowed)
    }
}

// Which link the popups show: anywhere or at home only.
class LinkChoiceTest {
    private val anywhere = "https://example.com/octo/family/join#u=alex&c=482913"
    private val home = "http://192.168.1.20:4533/family/join#u=alex&c=482913"

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
    fun aLinkShowsWhereItGoesAndTheAddressToType() {
        assertEquals("example.com/octo/family/join", shownLink("https://example.com/octo/family/join#u=alex&c=482913"))
        assertEquals("https://example.com/octo", linkServer("https://example.com/octo/family/join#u=alex&c=482913"))
        assertEquals("http://192.168.1.20:4533", linkServer(home))
    }

    @Test
    fun someoneKeptHomeOnlyGetsTheHomeLink() {
        val options = LinkOptions(anywhere, home, awayAllowed = false)
        assertFalse(options.choosable)
        assertEquals(LinkReach.Home, options.default)
        assertEquals(home, options.linkFor(LinkReach.Anywhere))
    }

    @Test
    fun anOlderServersOneLinkStaysTheOnlyChoice() {
        val added = app.winters.octo.subsonic.FamilyDeviceAdded(kind = FamilyDeviceKind.OctoApp, pairCode = "482913", username = "alex", server = "https://example.com/octo", home = "http://192.168.1.20:4533")
        val options = deviceLinkOptions(added, "fallback")
        assertFalse(options.choosable)
        assertEquals("https://example.com/octo/family/join#u=alex&c=482913&home=http%3A%2F%2F192.168.1.20%3A4533", options.linkFor(options.default))

        val homeOnly = deviceLinkOptions(added.copy(homeOnly = true), "fallback")
        assertEquals(LinkReach.Home, homeOnly.default)
        assertFalse(homeOnly.anywhereAvailable)

        val both = deviceLinkOptions(added.copy(links = app.winters.octo.subsonic.FamilyLinkChoices(anywhere, home)), "fallback")
        assertEquals(anywhere, both.linkFor(LinkReach.Anywhere))
        assertEquals(home, both.linkFor(LinkReach.Home))
    }
}

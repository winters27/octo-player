package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyDeviceKind
import app.winters.octo.subsonic.FamilyRequest
import app.winters.octo.subsonic.FamilyRequestOutcome
import app.winters.octo.subsonic.FamilyRequestState
import app.winters.octo.subsonic.RequestQuality
import app.winters.octo.ui.family.FamilyFakeServer.Companion.me
import app.winters.octo.ui.family.FamilyFakeServer.Companion.request
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The Family view's state, against a pretend server with Family on.
class FamilyModelTest {
    private val server = FamilyFakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var supported = true
    private val answered = mutableListOf<FamilyRequest>()
    private val model = FamilyModel({ server.client() }, { supported }, scope, answered = { answered += it }, pollMs = 60_000)

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun until(what: String, check: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!check()) delay(10) }
        assertTrue(what, check())
    }

    @Test
    fun aListenerSeesTheirPlanRequestsDevicesAndSaved() = runBlocking {
        server.answer("getFamilyRequests") { """"familyRequests":{"request":[${request("r1")},${request("r2", "Done", "AlreadyShared")}]}""" }
        server.answer("getFamilyDevices") {
            """"familyDevices":{"device":[{"id":"d_1","username":"alex","name":"Pixel 9","kind":"OctoApp","app":"Octo 1.6 (Android)","created":"","lastSeen":"","place":"Away","playing":null,"current":true}]}"""
        }
        server.answer("getStarred2") {
            """"starred2":{"song":[{"id":"ext-deezer-song-1","title":"Found","isExternal":true},{"id":"tr-1","title":"Mine"}],
            "album":[{"id":"ext-deezer-album-2","name":"Found album","isExternal":true}]}"""
        }
        model.refresh()

        assertEquals("Alex, Listener", planTitle(model.me!!))
        assertFalse(model.manages)
        assertEquals(listOf(FamilySection.Plan, FamilySection.Saved, FamilySection.Requests, FamilySection.Devices), model.sections())
        assertEquals(listOf("r1", "r2"), model.requests.map { it.id })
        assertEquals("Already in the family library", requestStateLine(model.requests[1]))
        assertEquals("Pixel 9", model.devices.single().name)
        // Only outside songs and albums are Saved; library songs are not.
        assertEquals(listOf("ext-deezer-song-1"), model.saved.songs.map { it.id })
        assertEquals(listOf("ext-deezer-album-2"), model.saved.albums.map { it.id })
        // A listener never asks for everyone's requests.
        assertTrue(server.called("getFamilyRequests").none { it.url.queryParameter("all") == "true" })
    }

    @Test
    fun aManagerAlsoSeesMembersAndTheInbox() = runBlocking {
        server.answer("getFamily") {
            """"family":{"me":${me(role = "Owner", addToLibrary = "Direct", approveRequests = true, manageFamily = true, managed = false)},
            "manager":{"members":[{"username":"alex","displayName":"Alex","role":"Listener","suspended":false,"devices":2,"playingNow":true,"pendingRequests":1,"storageUsedBytes":5400000000,"storageLimitGb":10}],
            "pendingRequests":1,"liveStreams":1}}"""
        }
        server.answer("getFamilyRequests") { call ->
            if (call.url.queryParameter("all") == "true") """"familyRequests":{"request":[${request("r9", username = "alex")}]}"""
            else """"familyRequests":{}"""
        }
        model.refresh()
        assertTrue(model.manages)
        assertEquals(FamilySection.entries, model.sections())
        assertEquals("Listener · 2 devices · Playing now · 1 request waiting", memberLine(model.info!!.manager!!.members.single()))
        assertEquals(listOf("r9"), model.inbox.map { it.id })
        assertEquals("Pending", server.called("getFamilyRequests").first { it.url.queryParameter("all") == "true" }.url.queryParameter("state"))

        server.answer("decideFamilyRequest") { """"familyRequest":${request("r9", "Approved")}""" }
        model.approve("r9", "Enjoy")
        until("approved") { model.said == "Approved Song · Artist" }
        val decided = server.called("decideFamilyRequest").single().url
        assertEquals("true", decided.queryParameter("approve"))
        assertEquals("Enjoy", decided.queryParameter("note"))

        model.decline("r9")
        until("declined twice") { server.called("decideFamilyRequest").size == 2 }
        assertEquals("false", server.called("decideFamilyRequest")[1].url.queryParameter("approve"))
    }

    @Test
    fun aRequestTheFamilyAlreadyHasComesBackDone() = runBlocking {
        server.answer("requestCopy") { """"familyRequest":${request("r5", "Done", "AddedFromFamily")}""" }
        val made = model.request("ext-deezer-song-5", RequestQuality.Flac)
        assertEquals(FamilyRequestOutcome.AddedFromFamily, made?.outcome)
        assertEquals("Added from the family library", model.said)
        assertEquals("Flac", server.called("requestCopy").single().url.queryParameter("quality"))
        // The phone's notices are told it was shown.
        assertEquals(listOf("r5"), answered.map { it.id })
    }

    @Test
    fun aRefusedRequestSaysWhyInTheServersWords() = runBlocking {
        server.failWith("requestCopy", 50, "You have used all 10 requests this week.")
        assertNull(model.request("ext-deezer-song-5", RequestQuality.Best))
        assertEquals("You have used all 10 requests this week.", model.said)
        assertFalse(model.working)
    }

    @Test
    fun cancellingSigningOutAndAddingADevice() = runBlocking {
        server.answer("cancelFamilyRequest") { """"familyRequest":${request("r1", "Cancelled")}""" }
        model.cancel("r1")
        until("cancelled") { model.said == "Request cancelled" }

        server.answer("addFamilyDevice") {
            """"familyDeviceAdded":{"deviceId":"d_9","kind":"OctoApp","appPassword":null,"pairCode":"482913","expires":"2026-10-20T18:15:00Z","server":"https://music.example.com","username":"alex"}"""
        }
        model.addDevice("Laptop", FamilyDeviceKind.OctoApp)
        until("code shown") { model.added != null }
        assertEquals("482913", model.added?.pairCode)
        model.dismissAdded()
        assertNull(model.added)

        server.answer("signOutFamilyDevice") { "" }
        model.signOut(app.winters.octo.subsonic.FamilyDevice(id = "d_2", name = "Symfonium"))
        until("signed out") { model.said == "Symfonium is signed out" }
        assertEquals("d_2", server.called("signOutFamilyDevice").single().url.queryParameter("id"))
    }

    @Test
    fun removingASharedSongShowsTheServersReason() = runBlocking {
        server.failWith("removeFromMyLibrary", 50, "This song is in the shared library.")
        model.removeFromMyLibrary("tr-1", "Song")
        until("refused") { model.said == "This song is in the shared library." }
        server.answer("removeFromMyLibrary") { "" }
        model.removeFromMyLibrary("tr-2", "Other song")
        until("removed") { model.said == "Removed Other song from your library" }
    }

    @Test
    fun aManagerAddsAMemberAndSeesTheirInviteAsAQrCode() = runBlocking {
        server.raw("members") { """{"member":{"username":"sam","displayName":"Sam","role":"Kid"},"inviteLink":"https://music.example.com/family/join#invite=t9"}""" }
        model.addMember("sam", "Sam", app.winters.octo.subsonic.FamilyPreset.Kid)
        until("invite shown") { model.shown != null }
        assertEquals("Invite Sam", model.shown!!.title)
        assertEquals("https://music.example.com/family/join#invite=t9", model.shown!!.url)
        // Signed as every call is, never with a password.
        val call = server.called("members").single().url
        assertEquals("alex", call.queryParameter("u"))
        assertNull(call.queryParameter("p"))
        assertTrue(server.called("auth").isEmpty())
        model.closeShown()
        assertNull(model.shown)
    }

    @Test
    fun aManagerAddsADeviceForAMemberWithNoPassword() = runBlocking {
        server.answer("addFamilyDevice") { call ->
            """"familyDeviceAdded":{"deviceId":"d_5","kind":"OctoApp","pairCode":"104729","username":"${call.url.queryParameter("username")}"}"""
        }
        model.addMemberDevice(app.winters.octo.subsonic.FamilyMember(username = "sam", displayName = "Sam"))
        until("code shown") { model.added != null }
        assertEquals("104729", model.added!!.pairCode)
        assertEquals("sam", model.added!!.username)
        assertEquals("Sam", model.addedFor)
        assertEquals("sam", server.called("addFamilyDevice").single().url.queryParameter("username"))
        model.dismissAdded()
        assertNull(model.addedFor)
        // Nothing asked the family page for a password.
        assertTrue(server.called("auth").isEmpty())
    }

    @Test
    fun aDeviceAddedWithACodeHasAnHttpsLink() {
        val added = app.winters.octo.subsonic.FamilyDeviceAdded(kind = app.winters.octo.subsonic.FamilyDeviceKind.OctoApp, pairCode = "482913", username = "alex")
        assertEquals("https://music.example.com/family/join#u=alex&c=482913", addedDeviceLink(added, "https://music.example.com"))
        assertEquals("https://other.example.com/family/join#u=alex&c=482913", addedDeviceLink(added.copy(server = "https://other.example.com"), "https://music.example.com"))
        assertNull(addedDeviceLink(added.copy(pairCode = null, appPassword = "ABCD"), "https://music.example.com"))
    }

    @Test
    fun theAccountsQualityAndThisDevicesModeAreSetOnTheServer() = runBlocking {
        var mode = "Account"
        server.answer("getFamilyDevices") {
            """"familyDevices":{"device":[{"id":"d_1","name":"Pixel 9","kind":"OctoApp","current":true,"quality":"$mode"},{"id":"d_2","name":"Other","current":false}]}"""
        }
        server.answer("setFamilyQuality") { """"quality":{"home":"Original","away":"DataSaver"}""" }
        server.answer("setFamilyDeviceQuality") { call ->
            mode = call.url.queryParameter("mode")!!
            """"device":{"id":"d_1","current":true,"quality":"$mode"}"""
        }
        model.plan()
        assertEquals(app.winters.octo.subsonic.DeviceQualityMode.Account, model.deviceMode)
        model.setQuality(away = app.winters.octo.subsonic.StreamQuality.DataSaver)
        until("quality sent") { server.called("setFamilyQuality").isNotEmpty() }
        assertEquals("DataSaver", server.called("setFamilyQuality").single().url.queryParameter("away"))
        until("read again") { !model.working }
        model.setDeviceMode(app.winters.octo.subsonic.DeviceQualityMode.App)
        until("mode sent") { server.called("setFamilyDeviceQuality").isNotEmpty() }
        val sent = server.called("setFamilyDeviceQuality").single().url
        assertEquals("d_1", sent.queryParameter("id"))
        assertEquals("App", sent.queryParameter("mode"))
        until("mode kept") { model.deviceMode == app.winters.octo.subsonic.DeviceQualityMode.App }
    }

    @Test
    fun withoutTheExtensionNothingIsAsked() = runBlocking {
        supported = false
        model.refresh()
        assertNull(model.info)
        assertNull(model.plan())
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun thePlanIsReadOnceForTheRestOfTheApp() = runBlocking {
        val first = model.plan()
        assertNotNull(first)
        model.plan()
        assertEquals(1, server.called("getFamily").size)
        model.forget()
        assertNull(model.info)
    }

    @Test
    fun aServerOutOfReachSaysSo() = runBlocking {
        server.close()
        model.refresh()
        assertEquals("Octo can't reach the server right now.", model.problem)
    }

    @Test
    fun theSheetLinesFollowTheAnswer() {
        assertEquals("Requested. You'll hear when it's decided", requestAnswerLine(FamilyRequest(state = FamilyRequestState.Pending)))
        assertEquals("Already in the family library", requestAnswerLine(FamilyRequest(state = FamilyRequestState.Done, outcome = FamilyRequestOutcome.AlreadyShared)))
        assertEquals("Downloaded", requestAnswerLine(FamilyRequest(state = FamilyRequestState.Done, outcome = FamilyRequestOutcome.Downloaded)))
    }
}

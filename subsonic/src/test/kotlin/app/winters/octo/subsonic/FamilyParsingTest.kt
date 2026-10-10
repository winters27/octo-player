package app.winters.octo.subsonic

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// The octoFamily extension's calls and answers, read from fixtures shaped
// like an Octo server's.
class FamilyParsingTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun client() = SubsonicClient(server.url("/"), Credentials("alex", "secret"), OkHttpClient())

    private fun answer(name: String) = server.enqueue(
        MockResponse.Builder()
            .body(requireNotNull(javaClass.getResource("/fixtures/$name.json")).readText(Charsets.UTF_8))
            .build(),
    )

    @Test
    fun theExtensionIsFoundWhenListed() = runTest {
        answer("getOpenSubsonicExtensionsFamily")
        assertTrue(client().supports(OCTO_FAMILY))
        answer("getOpenSubsonicExtensionsOcto")
        assertFalse(client().supports(OCTO_FAMILY))
    }

    @Test
    fun aListenerReadsTheirPlanWithoutTheManagerPart() = runTest {
        answer("getFamilyListener")
        val family = client().family()

        val url = server.takeRequest().url
        assertEquals("/rest/getFamily", url.encodedPath)
        assertEquals("alex", url.queryParameter("u"))

        val me = family.me
        assertEquals("alex", me.username)
        assertEquals("Alex", me.displayName)
        assertEquals(FamilyRole.Listener, me.role)
        assertTrue(me.managed)
        assertEquals(3, me.requestsThisWeek)
        assertEquals(5_400_000_000L, me.storageUsedBytes)
        assertEquals(FamilyPlace.Home, me.place)
        assertEquals("d_7Qm2abc", me.deviceId)
        val can = me.abilities
        assertEquals(AddToLibrary.Request, can.addToLibrary)
        assertEquals(RequestQuality.Best, can.requestQuality)
        assertFalse(can.autoApprove)
        assertEquals(10, can.weeklyRequestLimit)
        assertEquals(192, can.streamCap)
        assertEquals(128, can.awayCap)
        assertTrue(can.away)
        assertEquals(2, can.devicesAtOnce)
        assertFalse(can.downloadFiles)
        assertFalse(can.offlineCopies)
        assertFalse(can.share)
        assertTrue(can.importPlaylists)
        assertFalse(can.cleanOnly)
        assertTrue(can.familyPlaylistsEdit)
        assertFalse(can.manageFamily)
        assertFalse(can.approveRequests)
        assertEquals(10, can.storageLimitGb)
        assertTrue(can.instantFromFamily)
        assertNull(family.manager)
        // The account's own audio quality, and the family's limits on it.
        assertEquals(FamilyQuality(StreamQuality.Original, StreamQuality.Standard, 192, 128), me.quality)
    }

    @Test
    fun theOwnerReadsTheWholeFamily() = runTest {
        answer("getFamilyOwner")
        val family = client().family()
        assertEquals(FamilyRole.Owner, family.me.role)
        // The owner has no device id.
        assertNull(family.me.deviceId)
        assertEquals(FamilyPlace.Away, family.me.place)
        val manager = requireNotNull(family.manager)
        assertEquals(1, manager.pendingRequests)
        assertEquals(3, manager.liveStreams)
        assertEquals(listOf("alex", "sam"), manager.members.map { it.username })
        val alex = manager.members[0]
        assertEquals(FamilyRole.Listener, alex.role)
        assertEquals(2, alex.devices)
        assertTrue(alex.playingNow)
        assertEquals(1, alex.pendingRequests)
        assertEquals(5_400_000_000L, alex.storageUsedBytes)
        assertEquals(10, alex.storageLimitGb)
        val sam = manager.members[1]
        assertEquals(FamilyRole.Kid, sam.role)
        assertTrue(sam.suspended)
    }

    @Test
    fun valuesFromANewerServerReadAsTheDefaults() = runTest {
        answer("getFamilyFuture")
        val me = client().family().me
        assertEquals("zoe", me.username)
        // A role this app does not know keeps the server's own name.
        assertNull(me.role)
        assertEquals("Guest", me.roleName)
        assertEquals(AddToLibrary.Direct, me.abilities.addToLibrary)
        assertEquals(RequestQuality.Best, me.abilities.requestQuality)
        assertEquals(FamilyPlace.Home, me.place)
    }

    @Test
    fun requestsAreReadWithEveryState() = runTest {
        answer("getFamilyRequests")
        val requests = client().familyRequests()
        val url = server.takeRequest().url
        assertEquals("/rest/getFamilyRequests", url.encodedPath)
        // Mine by default: no `all`.
        assertNull(url.queryParameter("all"))
        assertNull(url.queryParameter("state"))

        assertEquals(4, requests.size)
        val pending = requests[0]
        assertEquals("r_9xK2", pending.id)
        assertEquals(FamilyRequestKind.Song, pending.kind)
        assertEquals("ext-deezer-song-12345", pending.target)
        assertEquals("ext-deezer-song-12345", pending.coverArt)
        assertEquals(RequestQuality.Flac, pending.quality)
        assertEquals(FamilyRequestState.Pending, pending.state)
        assertEquals("2026-10-20T18:00:00Z", pending.created)
        assertNull(pending.decided)
        assertNull(pending.outcome)
        val done = requests[1]
        assertEquals(FamilyRequestKind.Album, done.kind)
        assertEquals(FamilyRequestState.Done, done.state)
        assertEquals(FamilyRequestOutcome.AddedFromFamily, done.outcome)
        assertEquals("tr-77", done.librarySongId)
        assertEquals("jordan", done.decidedBy)
        assertNull(done.coverArt)
        assertEquals(FamilyRequestState.Declined, requests[2].state)
        assertEquals("We have the live one", requests[2].note)
        assertEquals(RequestQuality.Mp3, requests[2].quality)
        assertEquals("No real FLAC copy found", requests[3].failure)
    }

    @Test
    fun aManagerAsksForEveryMembersPendingRequests() = runTest {
        answer("getFamilyRequestsEmpty")
        assertTrue(client().familyRequests(all = true, state = FamilyRequestState.Pending).isEmpty())
        val url = server.takeRequest().url
        assertEquals("true", url.queryParameter("all"))
        assertEquals("Pending", url.queryParameter("state"))
    }

    @Test
    fun aRequestForASongAlreadySharedComesBackDone() = runTest {
        answer("requestCopyAlreadyShared")
        val request = client().requestCopy("ext-deezer-song-12345", RequestQuality.Flac)
        val url = server.takeRequest().url
        assertEquals("/rest/requestCopy", url.encodedPath)
        assertEquals("ext-deezer-song-12345", url.queryParameter("id"))
        assertEquals("Flac", url.queryParameter("quality"))
        assertNull(url.queryParameter("kind"))
        assertEquals(FamilyRequestState.Done, request.state)
        assertEquals(FamilyRequestOutcome.AlreadyShared, request.outcome)
    }

    @Test
    fun aRequestNamesItsKindAndQualityWhenGiven() = runTest {
        answer("requestCopyPending")
        client().requestCopy("tr-1", RequestQuality.Mp3, FamilyRequestKind.Upgrade)
        val url = server.takeRequest().url
        assertEquals("Mp3", url.queryParameter("quality"))
        assertEquals("Upgrade", url.queryParameter("kind"))
    }

    @Test
    fun cancelAndDecideAnswerTheRequest() = runTest {
        answer("cancelFamilyRequest")
        assertEquals(FamilyRequestState.Cancelled, client().cancelFamilyRequest("r_9xK2").state)
        assertEquals("r_9xK2", server.takeRequest().url.queryParameter("id"))

        answer("decideFamilyRequest")
        val decided = client().decideFamilyRequest("r_9xK2", approve = true, note = " Enjoy ")
        val url = server.takeRequest().url
        assertEquals("/rest/decideFamilyRequest", url.encodedPath)
        assertEquals("true", url.queryParameter("approve"))
        assertEquals("Enjoy", url.queryParameter("note"))
        assertEquals(FamilyRequestState.Approved, decided.state)

        // A blank note is not sent.
        answer("decideFamilyRequest")
        client().decideFamilyRequest("r_9xK2", approve = false, note = "  ")
        val declined = server.takeRequest().url
        assertEquals("false", declined.queryParameter("approve"))
        assertNull(declined.queryParameter("note"))
    }

    @Test
    fun devicesAreReadWithWhatTheyPlay() = runTest {
        answer("getFamilyDevices")
        val devices = client().familyDevices(all = true)
        assertEquals("true", server.takeRequest().url.queryParameter("all"))
        assertEquals(2, devices.size)
        val phone = devices[0]
        assertEquals("Pixel 9", phone.name)
        assertEquals("2026-10-19T10:00:00Z", phone.firstSeen)
        assertEquals("Octo 1.6 (Android)", phone.app)
        assertEquals(FamilyPlace.Away, phone.place)
        assertEquals("Song", phone.playing?.title)
        assertTrue(phone.current)
        val other = devices[1]
        assertEquals(DeviceQualityMode.App, phone.quality)
        // A device that does not say follows the account.
        assertEquals(DeviceQualityMode.Account, other.quality)
        assertEquals("Symfonium", other.app)
        assertNull(other.playing)
        assertFalse(other.current)
    }

    @Test
    fun theAccountsQualityAndADevicesModeAreSet() = runTest {
        answer("setFamilyQuality")
        val now = client().setFamilyQuality(home = StreamQuality.High, away = StreamQuality.DataSaver)
        val url = server.takeRequest().url
        assertEquals("/rest/setFamilyQuality", url.encodedPath)
        assertEquals("High", url.queryParameter("home"))
        assertEquals("DataSaver", url.queryParameter("away"))
        assertEquals(StreamQuality.DataSaver, now.away)
        // One left out is not sent.
        answer("setFamilyQuality")
        client().setFamilyQuality(away = StreamQuality.Standard)
        assertNull(server.takeRequest().url.queryParameter("home"))

        answer("setFamilyDeviceQuality")
        val device = client().setFamilyDeviceQuality("d_7Qm2abc", DeviceQualityMode.App)
        val call = server.takeRequest().url
        assertEquals("d_7Qm2abc", call.queryParameter("id"))
        assertEquals("App", call.queryParameter("mode"))
        assertEquals(DeviceQualityMode.App, device.quality)
        assertEquals(listOf(0, 256, 160, 96), StreamQuality.entries.map { it.kbps })
    }

    @Test
    fun aQualityAnswerWithoutItsKeyStillReads() = runTest {
        answer("ok")
        assertEquals(StreamQuality.Standard, client().setFamilyQuality(away = StreamQuality.Standard).away)
        answer("ok")
        assertEquals(DeviceQualityMode.App, client().setFamilyDeviceQuality("d_1", DeviceQualityMode.App).quality)
    }

    @Test
    fun removingADeviceFromTheListSendsItsId() = runTest {
        answer("ok")
        client().forgetFamilyDevice("d_2")
        val url = server.takeRequest().url
        assertEquals("/rest/forgetFamilyDevice", url.encodedPath)
        assertEquals("d_2", url.queryParameter("id"))
    }

    @Test
    fun theLoginIsReadWithBothAddresses() = runTest {
        answer("getFamilyLogin")
        val login = client().familyLogin()
        assertEquals("/rest/getFamilyLogin", server.takeRequest().url.encodedPath)
        assertEquals("alex", login.username)
        assertEquals("https://music.example.com", login.servers.anywhere)
        assertEquals("http://192.168.1.20:4533", login.servers.home)
        assertTrue(login.anywhereAvailable)
        assertFalse(login.awayAllowed)
    }

    @Test
    fun changingTheFamilyPasswordSendsBothInTheBodyAndSwitchesTheClient() = runTest {
        answer("ok")
        answer("ok")
        val client = client()
        client.changeFamilyPassword("secret", "a new long one")
        val change = server.takeRequest()
        assertEquals("POST", change.method)
        assertEquals("/rest/changeFamilyPassword", change.url.encodedPath)
        val form = change.body!!.utf8()
        assertTrue(form.contains("current=secret"))
        assertTrue(form.contains("next=a+new+long+one"))
        // Neither password is in the address.
        assertNull(change.url.queryParameter("next"))
        // The client signs in with the new one from now on.
        client.ping()
        val next = server.takeRequest().url
        assertEquals(md5Hex("a new long one" + next.queryParameter("s")), next.queryParameter("t"))
    }

    @Test
    fun removingASharedSongIsRefusedInPlainWords() = runTest {
        answer("ok")
        client().removeFromMyLibrary("tr-5")
        val url = server.takeRequest().url
        assertEquals("/rest/removeFromMyLibrary", url.encodedPath)
        assertEquals("tr-5", url.queryParameter("id"))

        answer("removeFromMyLibraryShared")
        try {
            client().removeFromMyLibrary("tr-6")
            fail("A shared song cannot be removed")
        } catch (e: SubsonicException.Server) {
            assertEquals(50, e.code)
            assertTrue(e.message!!.contains("shared library"))
        }
    }

    @Test
    fun aFamilyPlaylistIsMade() = runTest {
        answer("createFamilyPlaylist")
        val made = client().createFamilyPlaylist(" Road trip ", approveAdditions = true)
        val url = server.takeRequest().url
        assertEquals("/rest/createFamilyPlaylist", url.encodedPath)
        assertEquals("Road trip", url.queryParameter("name"))
        assertEquals("true", url.queryParameter("approveAdditions"))
        assertEquals("of_road", made.id)
        assertEquals("Road trip", made.name)
    }

    @Test
    fun aSignInLinkFillsInTheServerAndUsername() {
        val link = parseFamilyLink(" octo://signin?server=https%3A%2F%2Fmusic.example.com%2Fnd&home=http%3A%2F%2F192.168.1.20%3A4533&username=alex%20smith ")
        assertEquals(FamilySignInPrefill("https://music.example.com/nd", "alex smith", "http://192.168.1.20:4533"), link)
        assertEquals(link, parseFamilyLink(familyAppLink(link!!)))
        // A bare address still names a server; no username is fine.
        assertEquals(FamilySignInPrefill("https://music.example.com"), parseFamilyLink("octo://signin?server=https%3A%2F%2Fmusic.example.com"))
        assertNull(parseFamilyLink("octo://signin?username=alex"))
        assertNull(parseFamilyLink("octo://album/al-1"))
    }

    @Test
    fun aHandOverLinkIsReadInBothFormsAndKeepsItsKey() {
        val web = familySignInUrl("https://music.example.com/nd/", "tok_1", "K-ey_0", "https://music.example.com/nd", "http://192.168.1.20:4533", "alex")
        assertEquals("https://music.example.com/nd/family/signin#t=tok_1&k=K-ey_0&s=https%3A%2F%2Fmusic.example.com%2Fnd&h=http%3A%2F%2F192.168.1.20%3A4533&u=alex", web)
        val link = FamilyHandOverLink("https://music.example.com/nd", "tok_1", "K-ey_0", "https://music.example.com/nd", "http://192.168.1.20:4533", "alex")
        assertEquals(link, parseFamilyLink(web))
        // The app's own form carries the same values in its query.
        val own = familyAppLink(link)
        assertEquals("octo://handover?" + web.substringAfter('#'), own)
        assertEquals(link, parseFamilyLink(own))
        // Neither the token nor the key reaches a log.
        assertFalse(link.toString().contains("tok_1"))
        assertFalse(link.toString().contains("K-ey"))
        // The fragment's values, as the family page hands them on.
        assertEquals(
            FamilyHandOverLink("https://music.example.com", "tok_9", "a_b-c", "https://music.example.com", "http://192.168.1.20:4533"),
            parseFamilyLink("octo://handover?t=tok_9&k=a_b-c&s=https%3A%2F%2Fmusic.example.com&h=http%3A%2F%2F192.168.1.20%3A4533"),
        )
        // An Android browser's intent form reads the same.
        assertEquals(
            FamilyHandOverLink("https://music.example.com", "tok_9", "a_b-c", "https://music.example.com", null, "alex"),
            parseFamilyLink("intent://handover?t=tok_9&k=a_b-c&s=https%3A%2F%2Fmusic.example.com&u=alex#Intent;scheme=octo;package=app.winters.octo;S.browser_fallback_url=https%3A%2F%2Fmusic.example.com%2Ffamily%2Fsignin%26back%3D1;end"),
        )
        // Without a token, a key or a server it is no hand-over.
        assertNull(parseFamilyLink("octo://handover?t=tok_9&s=https%3A%2F%2Fmusic.example.com"))
        assertNull(parseFamilyLink("octo://handover?k=a&s=https%3A%2F%2Fmusic.example.com"))
        assertNull(parseFamilyLink("octo://handover?t=tok_9&k=a"))
        // Not hand-overs.
        assertNull(parseFamilyLink("https://music.example.com/family/signin#t=tok_1"))
        assertNull(parseFamilyLink("https://music.example.com/family/signin"))
        assertNull(parseFamilyLink("https://music.example.com/family/other#t=1&k=2"))
    }

    @Test
    fun aHandOverIsStartedAndDecidedByOctoFamilyCalls() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","familySignIn":{"token":"tok_9","expires":"2026-10-20T18:02:00Z",
            "links":{"anywhere":"https://music.example.com/family/signin","home":"http://192.168.1.20:4533/family/signin"},
            "servers":{"anywhere":"https://music.example.com","home":"http://192.168.1.20:4533"},"anywhereAvailable":true}}}""").build())
        val client = client()
        val start = client.startFamilySignIn()
        assertEquals("tok_9", start.token)
        assertEquals("https://music.example.com/family/signin", start.links.anywhere)
        assertEquals("http://192.168.1.20:4533", start.servers.home)
        assertFalse(start.toString().contains("tok_9"))
        val started = server.takeRequest().url
        assertEquals("/rest/startFamilySignIn", started.encodedPath)
        assertEquals("alex", started.queryParameter("u"))

        // Nobody yet: the key is null or missing.
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","familySignInPending":null}}""").build())
        assertNull(client.familySignInPending("tok_9"))
        assertEquals("tok_9", server.takeRequest().url.queryParameter("token"))
        answer("ok")
        assertNull(client.familySignInPending("tok_9"))
        server.takeRequest()
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","familySignInPending":{"id":"r_1","deviceName":"Pixel 9","platform":"Android"}}}""").build())
        assertEquals(FamilySignInPending("r_1", "Pixel 9", "Android"), client.familySignInPending("tok_9"))
        assertEquals("/rest/getFamilySignInPending", server.takeRequest().url.encodedPath)

        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","familySignInDecided":{"state":"Allowed"}}}""").build())
        client.decideFamilySignIn("tok_9", allow = true)
        val decide = server.takeRequest().url
        assertEquals("/rest/decideFamilySignIn", decide.encodedPath)
        assertEquals("true", decide.queryParameter("allow"))

        answer("ok")
        client.putFamilySignInBox("tok_9", "c2VhbGVk")
        val box = server.takeRequest()
        assertEquals("POST", box.method)
        assertEquals("/rest/putFamilySignInBox", box.url.encodedPath)
        // The box goes in the body, never in the address.
        assertNull(box.url.queryParameter("box"))
        assertTrue(box.body!!.utf8().contains("box=c2VhbGVk"))
    }

    @Test
    fun theNewDeviceRedeemsAndWaitsWithNoSignIn() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","familySignInRedeemed":{"id":"r_1"}}}""").build())
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","familySignInRedeem":{"state":"Waiting"}}}""").build())
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","familySignInRedeem":{"state":"Allowed","box":"c2VhbGVk"}}}""").build())
        assertEquals("r_1", redeemFamilySignIn(server.url("/"), OkHttpClient(), "tok_9", " Pixel 9 ", FamilyPlatform.Android))
        val redeem = server.takeRequest().url
        assertEquals("/rest/redeemFamilySignIn", redeem.encodedPath)
        assertEquals("tok_9", redeem.queryParameter("token"))
        assertEquals("Pixel 9", redeem.queryParameter("deviceName"))
        assertEquals("Android", redeem.queryParameter("platform"))
        // Open: no sign-in of any kind.
        assertNull(redeem.queryParameter("u"))
        assertNull(redeem.queryParameter("t"))
        assertNull(redeem.queryParameter("p"))
        assertEquals(FamilySignInState.Waiting, familySignInRedeem(server.url("/"), OkHttpClient(), "r_1").state)
        val allowed = familySignInRedeem(server.url("/"), OkHttpClient(), "r_1")
        assertEquals(FamilySignInState.Allowed, allowed.state)
        assertEquals("c2VhbGVk", allowed.box)
        assertFalse(allowed.toString().contains("c2VhbGVk"))
        server.takeRequest()
        assertEquals("/rest/getFamilySignInRedeem", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aWrongTokenIsRefusedInPlainWords() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"That sign-in code has been used or has expired."}}}""").build())
        try {
            redeemFamilySignIn(server.url("/"), OkHttpClient(), "old", "Pixel 9", FamilyPlatform.Android)
            fail("A used token must not redeem")
        } catch (e: SubsonicException.WrongCredentials) {
            assertEquals("That sign-in code has been used or has expired.", e.message)
        }
    }

    @Test
    fun anInviteLinkIsReadInBothForms() {
        val invite = FamilyInviteLink("https://music.example.com", "tok_ABC-123")
        assertEquals(invite, parseFamilyLink("https://music.example.com/family/join#invite=tok_ABC-123"))
        assertEquals(invite, parseFamilyLink("octo://join?server=https%3A%2F%2Fmusic.example.com&invite=tok_ABC-123"))
        assertEquals("https://music.example.com/family/join#invite=tok_ABC-123", familyInviteUrl("https://music.example.com", "tok_ABC-123"))
        assertEquals(invite, parseFamilyLink(familyAppLink(invite)))
        assertFalse(invite.toString().contains("tok_"))
    }

    @Test
    fun anInvitesHomeAddressIsReadInBothFormsAndThePathIsKept() {
        val invite = FamilyInviteLink("https://example.com/octo", "tok_1", home = "http://music.lan")
        assertEquals(invite, parseFamilyLink("https://example.com/octo/family/join#invite=tok_1&home=http%3A%2F%2Fmusic.lan"))
        assertEquals(invite, parseFamilyLink("octo://join?server=https%3A%2F%2Fexample.com%2Focto&invite=tok_1&home=http%3A%2F%2Fmusic.lan"))
        assertEquals(invite, parseFamilyLink(familyInviteUrl("https://example.com/octo", "tok_1", "http://music.lan")))

        // No home address, or one that is not an address, is no home at all.
        assertNull(parseFamilyLink("https://example.com/family/join#invite=t")!!.home)
        assertNull(parseFamilyLink("https://example.com/family/join#invite=t&home=")!!.home)
        assertNull(parseFamilyLink("https://example.com/family/join#invite=t&home=%3A%2F%2F")!!.home)
    }

    @Test
    fun qualityLimitsCompareLowestFirst() {
        assertTrue(RequestQuality.Mp3.allowedUnder(RequestQuality.Flac))
        assertTrue(RequestQuality.Flac.allowedUnder(RequestQuality.Flac))
        assertFalse(RequestQuality.Best.allowedUnder(RequestQuality.Flac))
        assertFalse(RequestQuality.Flac.allowedUnder(RequestQuality.Mp3))
        assertTrue(RequestQuality.Best.allowedUnder(RequestQuality.Best))
    }

    @Test
    fun songsInTheMembersOwnLibraryAreMarked() = runTest {
        server.enqueue(
            MockResponse.Builder().body(
                """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"octo","searchResult3":{"song":[
                {"id":"tr-1","title":"Mine","octoPersonal":true},
                {"id":"tr-2","title":"Shared"},
                {"id":"tr-3","title":"Mine too","octoPersonal":"true"},
                {"id":"tr-4","title":"Odd","octoPersonal":"no"}]}}}""",
            ).build(),
        )
        val songs = client().search("x").song
        assertEquals(listOf(true, false, true, false), songs.map { it.octoPersonal })
    }
}

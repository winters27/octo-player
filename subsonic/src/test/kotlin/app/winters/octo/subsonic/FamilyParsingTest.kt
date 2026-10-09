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
        assertEquals(FamilyRole.Unmanaged, me.role)
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
        assertEquals(FamilyDeviceKind.OctoApp, phone.kind)
        assertEquals("Octo 1.6 (Android)", phone.app)
        assertEquals(FamilyPlace.Away, phone.place)
        assertEquals("Song", phone.playing?.title)
        assertTrue(phone.current)
        val other = devices[1]
        assertEquals(FamilyDeviceKind.SubsonicApp, other.kind)
        assertNull(other.playing)
        assertFalse(other.current)
    }

    @Test
    fun signingOutADeviceSendsItsId() = runTest {
        answer("ok")
        client().signOutFamilyDevice("d_2")
        val url = server.takeRequest().url
        assertEquals("/rest/signOutFamilyDevice", url.encodedPath)
        assertEquals("d_2", url.queryParameter("id"))
    }

    @Test
    fun addingADeviceGivesAPasswordOrACode() = runTest {
        answer("addFamilyDeviceSubsonic")
        val app = client().addFamilyDevice(" Symfonium ", FamilyDeviceKind.SubsonicApp)
        val url = server.takeRequest().url
        assertEquals("Symfonium", url.queryParameter("name"))
        assertEquals("SubsonicApp", url.queryParameter("kind"))
        assertEquals("ABCD-EFGH-JKMN-PQRS", app.appPassword)
        assertNull(app.pairCode)
        assertEquals("https://navidrome.winters.app", app.server)
        // The password never reaches a log.
        assertFalse(app.toString().contains("ABCD"))

        answer("addFamilyDeviceOcto")
        val octo = client().addFamilyDevice("Laptop", FamilyDeviceKind.OctoApp)
        assertEquals("OctoApp", server.takeRequest().url.queryParameter("kind"))
        assertEquals("482913", octo.pairCode)
        assertEquals("2026-10-20T18:15:00Z", octo.expires)
        assertNull(octo.appPassword)
        assertFalse(octo.toString().contains("482913"))
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
    fun pairingSendsNoSignInAndAnswersTheSecret() = runTest {
        answer("octoFamilyPair")
        val pair = pairWithFamilyCode(server.url("/"), OkHttpClient(), " alex ", "482 913", "Pixel 9", FamilyPlatform.Android)
        val url = server.takeRequest().url
        assertEquals("/rest/octoFamilyPair", url.encodedPath)
        assertEquals("alex", url.queryParameter("username"))
        assertEquals("482913", url.queryParameter("code"))
        assertEquals("Pixel 9", url.queryParameter("deviceName"))
        assertEquals("Android", url.queryParameter("platform"))
        assertEquals("json", url.queryParameter("f"))
        // Open call: no sign-in of any kind.
        assertNull(url.queryParameter("u"))
        assertNull(url.queryParameter("t"))
        assertNull(url.queryParameter("p"))
        assertNull(url.queryParameter("apiKey"))

        assertEquals("alex", pair.username)
        assertEquals("s3cr3tS3cr3tS3cr3tS3cr3tS3cr3t12", pair.secret)
        assertEquals("d_11", pair.deviceId)
        assertFalse(pair.toString().contains("s3cr3t"))
    }

    @Test
    fun aWrongCodeSaysSoInPlainWords() = runTest {
        answer("octoFamilyPairWrong")
        try {
            pairWithFamilyCode(server.url("/"), OkHttpClient(), "alex", "000000", "Pixel 9", FamilyPlatform.MacOs)
            fail("A wrong code must not pair")
        } catch (e: SubsonicException.WrongCredentials) {
            assertEquals("That code did not work. Ask for a new one.", e.message)
        }
        assertEquals("macOS", server.takeRequest().url.queryParameter("platform"))
    }

    @Test
    fun aJoinLinkIsRead() {
        val link = parseFamilyJoinLink("  octo://join?server=https%3A%2F%2Fmusic.example.com%2Fnd&username=alex&code=482913 ")
        assertEquals(FamilyJoinLink("https://music.example.com/nd", "alex", "482913"), link)
        // The code never reaches a log.
        assertFalse(link.toString().contains("482913"))
        // A link made here reads back the same.
        val made = familyJoinLink("https://music.example.com/nd", "alex smith", "482913")
        assertEquals("octo://join?server=https%3A%2F%2Fmusic.example.com%2Fnd&username=alex%20smith&code=482913", made)
        assertEquals(FamilyJoinLink("https://music.example.com/nd", "alex smith", "482913"), parseFamilyJoinLink(made))
        assertNull(parseFamilyJoinLink("https://music.example.com"))
        assertNull(parseFamilyJoinLink("octo://join?server=x&username=alex&code=12345"))
        assertNull(parseFamilyJoinLink("octo://join?server=x&code=123456"))
        assertNull(parseFamilyJoinLink("octo://join?username=alex&code=123456"))
    }

    @Test
    fun qualityLimitsCompareLowestFirst() {
        assertTrue(RequestQuality.Mp3.allowedUnder(RequestQuality.Flac))
        assertTrue(RequestQuality.Flac.allowedUnder(RequestQuality.Flac))
        assertFalse(RequestQuality.Best.allowedUnder(RequestQuality.Flac))
        assertFalse(RequestQuality.Flac.allowedUnder(RequestQuality.Mp3))
        assertTrue(RequestQuality.Best.allowedUnder(RequestQuality.Best))
    }
}

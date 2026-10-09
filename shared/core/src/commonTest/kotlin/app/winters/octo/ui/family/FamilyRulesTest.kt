package app.winters.octo.ui.family

import app.winters.octo.subsonic.DeviceQualityMode
import app.winters.octo.subsonic.StreamQuality
import app.winters.octo.subsonic.FamilyQuality
import app.winters.octo.subsonic.AddToLibrary
import app.winters.octo.subsonic.FamilyAbilities
import app.winters.octo.subsonic.FamilyDevice
import app.winters.octo.subsonic.FamilyDevicePlaying
import app.winters.octo.subsonic.FamilyDeviceKind
import app.winters.octo.subsonic.FamilyMe
import app.winters.octo.subsonic.FamilyPlace
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.FamilyRequest
import app.winters.octo.subsonic.FamilyRequestOutcome
import app.winters.octo.subsonic.FamilyRequestState
import app.winters.octo.subsonic.FamilyRole
import app.winters.octo.subsonic.RequestQuality
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Outside song actions, offline copies, the request sheet, notices, the
// abilities in plain words and joining with a code.
class FamilyRulesTest {
    private fun member(
        add: AddToLibrary = AddToLibrary.Request,
        max: RequestQuality = RequestQuality.Flac,
        limit: Int = 10,
        used: Int = 3,
        storageUsed: Long = 0,
        storageLimit: Int = 10,
        offline: Boolean = false,
        role: FamilyRole = FamilyRole.Listener,
        managed: Boolean = true,
    ) = FamilyMe(
        username = "alex",
        displayName = "Alex",
        roleName = role.name,
        managed = managed,
        abilities = FamilyAbilities(addToLibrary = add, requestQuality = max, weeklyRequestLimit = limit, storageLimitGb = storageLimit, offlineCopies = offline),
        requestsThisWeek = used,
        storageUsedBytes = storageUsed,
    )

    @Test
    fun outsideSongActionsFollowThePlan() {
        assertEquals(OutsideActions(SAVE, offersRequest = true), outsideActions(member(add = AddToLibrary.Request)))
        assertEquals(OutsideActions(SAVE, offersRequest = false), outsideActions(member(add = AddToLibrary.SaveOnly)))
        // Direct, or no family at all: the app's usual add.
        assertEquals(USUAL_OUTSIDE_ACTIONS, outsideActions(member(add = AddToLibrary.Direct)))
        assertEquals(USUAL_OUTSIDE_ACTIONS, outsideActions(null))
    }

    @Test
    fun onlyManagedMembersGetRemoveFromMyLibrary() {
        assertTrue(offersRemoveFromMyLibrary(member()))
        assertTrue(offersRemoveFromMyLibrary(member(role = FamilyRole.Kid)))
        assertFalse(offersRemoveFromMyLibrary(member(role = FamilyRole.Owner, managed = false)))
        assertFalse(offersRemoveFromMyLibrary(member(role = FamilyRole.Unmanaged, managed = false)))
        assertFalse(offersRemoveFromMyLibrary(null))
    }

    @Test
    fun removeFromMyLibraryGoesOnlyOnMarkedSongsWhenTheServerMarks() {
        // A server that marks: only the member's own songs.
        assertTrue(offersRemoveOn(personal = true, serverMarksPersonal = true))
        assertFalse(offersRemoveOn(personal = false, serverMarksPersonal = true))
        // A server that marks none: every library song, and its refusal says why.
        assertTrue(offersRemoveOn(personal = false, serverMarksPersonal = false))
    }

    @Test
    fun offlineCopiesFollowThePlan() {
        assertFalse(offlineCopiesAllowed(member(offline = false)))
        assertTrue(offlineCopiesAllowed(member(offline = true)))
        assertTrue(offlineCopiesAllowed(null))
        assertEquals("Offline copies are off for this account", OFFLINE_COPIES_OFF)
    }

    @Test
    fun theSheetHoldsBackQualitiesAboveThePlan() {
        val sheet = requestSheet(member(max = RequestQuality.Flac))
        assertEquals(listOf(RequestQuality.Best, RequestQuality.Flac, RequestQuality.Mp3), sheet.choices.map { it.quality })
        assertEquals(listOf(false, true, true), sheet.choices.map { it.enabled })
        assertEquals("Not on your plan", sheet.choices[0].detail)
        assertEquals(RequestQuality.Flac, sheet.initial)
        assertEquals("7 of 10 requests left this week", sheet.quotaLine)
        assertFalse(sheet.quotaUsed)
        assertNull(sheet.storageLine)
        assertEquals("Waits for approval", sheet.approvalLine)

        val mp3 = requestSheet(member(max = RequestQuality.Mp3))
        assertEquals(listOf(false, false, true), mp3.choices.map { it.enabled })
        assertEquals(RequestQuality.Mp3, mp3.initial)
        assertEquals(RequestQuality.Best, requestSheet(member(max = RequestQuality.Best)).initial)
    }

    @Test
    fun theSheetSaysWhenTheWeekOrTheLibraryIsFull() {
        val full = requestSheet(member(used = 10, storageUsed = 10_200_000_000, storageLimit = 10))
        assertTrue(full.quotaUsed)
        assertEquals("You have used all 10 requests this week. Songs the family already has can still be added.", full.quotaLine)
        assertEquals("Your library is full (10.2 of 10 GB)", full.storageLine)
        // No limits: no lines.
        val open = requestSheet(member(limit = 0, storageLimit = 0, storageUsed = 99_000_000_000))
        assertNull(open.quotaLine)
        assertNull(open.storageLine)
    }

    @Test
    fun sizesAndCountsReadPlainly() {
        assertEquals("5.4 of 10 GB used", storageLine(5_400_000_000, 10))
        assertEquals("5.4 GB used", storageLine(5_400_000_000, 0))
        assertEquals("40 MB of 10 GB used", storageLine(40_000_000, 10))
        assertEquals("0 of 5 GB used", storageLine(0, 5))
        assertEquals("120 GB used", storageLine(120_400_000_000, 0))
        assertEquals(0.54f, storageShare(5_400_000_000, 10)!!, 0.001f)
        assertEquals(1f, storageShare(50_000_000_000, 10)!!, 0.001f)
        assertNull(storageShare(5, 0))
        assertEquals("3 of 10 requests used this week", weeklyLine(3, 10))
        assertEquals("1 request this week", weeklyLine(1, 0))
    }

    @Test
    fun thePlanReadsInPlainWords() {
        val lines = abilityLines(member().abilities.copy(streamCap = 192, awayCap = 128, devicesAtOnce = 2, autoApprove = false)).map { it.text }
        assertEquals("Songs you add are saved. Request a copy to keep one in your library", lines[0])
        assertTrue("Copies in FLAC or MP3" in lines)
        assertTrue("Your requests wait for approval" in lines)
        assertTrue("10 requests a week" in lines)
        assertTrue("Room for 10 GB in your library" in lines)
        assertTrue("Your family plan limits listening to 192 kbps" in lines)
        assertTrue("Your family plan limits away listening to 128 kbps" in lines)
        assertTrue("Plays on 2 devices at once" in lines)
        assertTrue("Offline copies are off" in lines)
        // Nothing in the family's words uses a dash for a pause.
        for (line in lines) assertFalse(line, line.contains('\u2014') || line.contains('\u2013'))

        val owner = abilityLines(FamilyAbilities(addToLibrary = AddToLibrary.Direct, manageFamily = true, approveRequests = true)).map { it.text }
        assertEquals("Songs you add go straight into your library", owner[0])
        // No limit set: quality is not a line at all.
        assertFalse(owner.any { it.contains("kbps") || it.contains("quality") })
        assertTrue("Manages the family" in owner)
        assertFalse(owner.any { it.contains("requests a week") })
        // An away cap of 0 is the same as at home: no away line of its own.
        val sameAway = abilityLines(FamilyAbilities(streamCap = 192, awayCap = 0)).map { it.text }
        assertTrue("Listens away from home too" in sameAway)
        assertFalse(sameAway.any { it.contains("away listening") })
        // Nor one no lower.
        assertFalse(abilityLines(FamilyAbilities(streamCap = 128, awayCap = 192)).any { it.text.contains("away listening") })
        assertTrue(abilityLines(FamilyAbilities(streamCap = 0, awayCap = 128)).any { it.text == "Your family plan limits away listening to 128 kbps" })
        val homeOnly = abilityLines(FamilyAbilities(away = false))
        assertEquals(AbilityLine("Listening away from home is off", on = false), homeOnly.first { it.text.startsWith("Listening away") })
    }

    @Test
    fun audioQualityChoicesShowTheFamilysLimit() {
        val free = FamilyQuality()
        assertTrue(familyLimitLines(free).isEmpty())
        assertTrue(qualityOptions(homeLimit(free)).none { it.limited })
        val limited = FamilyQuality(home = StreamQuality.Original, away = StreamQuality.Standard, familyLimitKbps = 0, familyAwayLimitKbps = 160)
        assertEquals(listOf("Your family plan limits away listening to 160 kbps"), familyLimitLines(limited))
        assertEquals(160, awayLimit(limited))
        assertEquals(0, homeLimit(limited))
        // Original and High are held to 160 away; Standard and Data saver are not.
        assertEquals(listOf(true, true, false, false), qualityOptions(awayLimit(limited)).map { it.limited })
        assertEquals(listOf("Original", "High", "Standard", "Data saver"), qualityOptions(0).map { it.name })
        // A home limit is the away limit too, unless away is lower.
        val both = FamilyQuality(familyLimitKbps = 256, familyAwayLimitKbps = 96)
        assertEquals(listOf("Your family plan limits listening to 256 kbps", "Your family plan limits away listening to 96 kbps"), familyLimitLines(both))
        assertEquals(96, awayLimit(both))
        assertEquals(256, awayLimit(FamilyQuality(familyLimitKbps = 256)))
    }

    @Test
    fun theAppPicksQualityOnlyWithoutAFamilyOrWhenLeftToIt() {
        assertTrue(appPicksQuality(familyOn = false, mode = null))
        assertTrue(appPicksQuality(familyOn = false, mode = DeviceQualityMode.Account))
        assertTrue(appPicksQuality(familyOn = true, mode = DeviceQualityMode.App))
        assertFalse(appPicksQuality(familyOn = true, mode = DeviceQualityMode.Account))
        assertFalse(appPicksQuality(familyOn = true, mode = null))
        // In Account mode the app asks for the file as it is.
        assertEquals(mapOf("format" to "raw"), streamParams(appPicks = false, quality = StreamQuality.DataSaver))
        assertEquals(mapOf("format" to "raw"), streamParams(appPicks = true, quality = StreamQuality.Original))
        assertEquals(mapOf("format" to "opus", "maxBitRate" to "160"), streamParams(appPicks = true, quality = StreamQuality.Standard))
    }

    @Test
    fun aRoleFromANewerServerShowsItsOwnName() {
        assertEquals("Listener", roleLabel("Listener"))
        assertEquals("Co-admin", roleLabel("CoAdmin"))
        assertEquals("Guest", roleLabel("Guest"))
        assertEquals("Family Guest", roleLabel("FamilyGuest"))
        assertEquals("Zoe, Guest", planTitle(FamilyMe(username = "zoe", displayName = "Zoe", roleName = "Guest")))
    }

    @Test
    fun aLinkMakesAQrCodeThatReadsBack() {
        val link = app.winters.octo.subsonic.familyJoinUrl("https://music.example.com", "alex", "482913")
        val code = qrCode(link)
        // Drawn four squares to a module with a light border, as the apps draw it.
        val scale = 4
        val side = (code.size + 8) * scale
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in 0 until code.size) for (x in 0 until code.size) if (code.isDark(x, y)) {
            for (dy in 0 until scale) for (dx in 0 until scale) pixels[((y + 4) * scale + dy) * side + (x + 4) * scale + dx] = 0xFF000000.toInt()
        }
        assertEquals(link, readQr(pixels, side, side))
        assertEquals(app.winters.octo.subsonic.FamilyJoinLink("https://music.example.com", "alex", "482913"), familyLinkInQr(readQr(pixels, side, side)))
        // Light on dark reads too.
        val inverted = IntArray(pixels.size) { pixels[it] xor 0x00FFFFFF }
        assertEquals(link, readQr(inverted, side, side))
        // A picture with no code in it.
        assertNull(readQr(IntArray(100 * 100) { 0xFF808080.toInt() }, 100, 100))
    }

    @Test
    fun anInviteJoinsThenPairsThisDevice() = runBlocking {
        FamilyFakeServer().use { server ->
            server.raw("join") { """{"username":"alex"}""" }
            server.raw("devices") { """{"deviceId":"d_1","kind":"OctoApp","pairCode":"482913","username":"alex"}""" }
            server.answer("octoFamilyPair") { """"familyPair":{"username":"alex","secret":"abcdefghijklmnopqrstuvwxyz012345","deviceId":"d_1"}""" }
            assertEquals("Choose a password of at least 8 characters", inviteProblem("Alex", "short", "short"))
            assertEquals("The two passwords are not the same", inviteProblem("Alex", "long enough", "long enougH"))
            assertEquals("Type your name", inviteProblem(" ", "long enough", "long enough"))
            assertNull(inviteProblem("Alex", "long enough", "long enough"))
            val joined = joinWithInvite(server.url, "tok_1", "Alex", "long enough", "Pixel 9", FamilyPlatform.Android, OkHttpClient())
            assertEquals("abcdefghijklmnopqrstuvwxyz012345", (joined as JoinOutcome.Paired).pair.secret)
            assertEquals("482913", server.called("octoFamilyPair").single().url.queryParameter("code"))
            assertEquals("Pixel 9", server.called("octoFamilyPair").single().url.queryParameter("deviceName"))
        }
    }

    @Test
    fun requestsAndDevicesReadPlainly() {
        val done = FamilyRequest(title = "Song", artist = "Artist", state = FamilyRequestState.Done, outcome = FamilyRequestOutcome.Downloaded, quality = RequestQuality.Flac)
        assertEquals("Downloaded", requestStateLine(done))
        assertEquals("Added from the family library", requestStateLine(done.copy(outcome = FamilyRequestOutcome.AddedFromFamily)))
        assertEquals("Already in the family library", requestStateLine(done.copy(outcome = FamilyRequestOutcome.AlreadyShared)))
        assertEquals("Failed: No real FLAC copy found", requestStateLine(done.copy(state = FamilyRequestState.Failed, failure = "No real FLAC copy found")))
        assertEquals("Song · FLAC", requestKindLine(done))
        assertEquals("Song · Artist", requestTitle(done))
        val phone = FamilyDevice(name = "Pixel 9", kind = FamilyDeviceKind.OctoApp, app = "Octo 1.6 (Android)", place = FamilyPlace.Away, playing = FamilyDevicePlaying("tr-1", "Song", "Artist"))
        assertEquals("Octo 1.6 (Android) · Away · Playing Song by Artist", deviceLine(phone))
        assertEquals("Other music app · Home", deviceLine(FamilyDevice(kind = FamilyDeviceKind.SubsonicApp)))
    }

    @Test
    fun eachDecisionIsToldOnce() {
        val pending = FamilyRequest(id = "r1", title = "Song", artist = "Artist", state = FamilyRequestState.Pending)
        // The first check is the starting point: nothing old is told.
        val (first, afterFirst) = familyNotices(NoticeMemory(), listOf(pending, pending.copy(id = "r0", state = FamilyRequestState.Done)), waiting = null)
        assertTrue(first.isEmpty())

        val approved = pending.copy(state = FamilyRequestState.Approved)
        val (second, afterSecond) = familyNotices(afterFirst, listOf(approved), waiting = null)
        assertEquals(listOf(FamilyNotice("r1", "Approved", "Song · Artist is on its way to your library.")), second)
        // The same news is not told again.
        assertTrue(familyNotices(afterSecond, listOf(approved), waiting = null).first.isEmpty())

        val added = approved.copy(state = FamilyRequestState.Done, outcome = FamilyRequestOutcome.AddedFromFamily)
        val (third, afterThird) = familyNotices(afterSecond, listOf(added), waiting = null)
        assertEquals("Added", third.single().title)
        assertEquals("Song · Artist. Added from the family library.", third.single().text)

        val declined = FamilyRequest(id = "r2", title = "Other", state = FamilyRequestState.Declined, note = "We have the live one")
        assertEquals(FamilyNotice("r2", "Declined", "Other. \"We have the live one\""), familyNotices(afterThird, listOf(declined), null).first.single())

        // A request the sheet already showed is not told again.
        val shown = FamilyRequest(id = "r3", title = "Shown", state = FamilyRequestState.Done, outcome = FamilyRequestOutcome.AlreadyShared)
        assertTrue(familyNotices(afterThird.alreadyTold(shown), listOf(shown), null).first.isEmpty())
    }

    @Test
    fun managersAreToldHowManyWait() {
        val (first, after) = familyNotices(NoticeMemory(), emptyList(), waiting = 2)
        assertEquals(FamilyNotice(WAITING_NOTICE_KEY, "2 requests waiting", "Open Family to approve or decline."), first.single())
        // The same count, or fewer, is not told again; more is.
        assertTrue(familyNotices(after, emptyList(), waiting = 2).first.isEmpty())
        val (fewer, afterFewer) = familyNotices(after, emptyList(), waiting = 1)
        assertTrue(fewer.isEmpty())
        assertEquals("2 requests waiting", familyNotices(afterFewer, emptyList(), waiting = 2).first.single().title)
        assertEquals("1 request waiting", familyNotices(NoticeMemory(), emptyList(), waiting = 1).first.single().title)
    }

    // Pairing, against a pretend server.
    @Test
    fun joiningWithACodeAnswersTheSecret() = runBlocking {
        FamilyFakeServer().use { server ->
            server.answer("octoFamilyPair") { """"familyPair":{"username":"alex","secret":"abcdefghijklmnopqrstuvwxyz012345","deviceId":"d_11","server":"https://music.example.com"}""" }
            val joined = joinFamily(server.url, "alex", "482 913", "Pixel 9", FamilyPlatform.Android, OkHttpClient())
            assertEquals("abcdefghijklmnopqrstuvwxyz012345", (joined as JoinOutcome.Paired).pair.secret)
            val call = server.called("octoFamilyPair").single().url
            assertEquals("482913", call.queryParameter("code"))
            assertNull(call.queryParameter("u"))

            server.failWith("octoFamilyPair", 40, "That code did not work. Ask for a new one.")
            val wrong = joinFamily(server.url, "alex", "000000", "Pixel 9", FamilyPlatform.Android, OkHttpClient())
            assertEquals("That code did not work. Ask for a new one.", (wrong as JoinOutcome.Failed).message)
        }
    }

    @Test
    fun aJoinSaysWhatIsMissing() {
        val url = "https://music.example.com/".toHttpUrl()
        assertEquals("Type the server's address", joinProblem(null, "alex", "123456"))
        assertEquals("Type your username", joinProblem(url, " ", "123456"))
        assertEquals("The family code is 6 digits", joinProblem(url, "alex", "12345"))
        assertNull(joinProblem(url, "alex", "123 456"))
    }
}

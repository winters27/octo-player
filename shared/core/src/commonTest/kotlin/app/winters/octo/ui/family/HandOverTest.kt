package app.winters.octo.ui.family

import app.winters.octo.subsonic.FamilyHandOverLink
import app.winters.octo.subsonic.FamilyPlatform
import app.winters.octo.subsonic.parseFamilyLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// Signing in on another device of one's own: the sealed box, the signed-in
// device's side (renewing, asking, nothing sent before Allow) and the new
// device's side (the key never leaves it).
class HandOverTest {
    private val server = FamilyFakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val start = Instant.parse("2026-10-20T18:00:00Z").toEpochMilli()
    private val now = AtomicLong(start)
    private val signIn = HandOverSignIn("alex", "secret", "Token", "https://music.example.com", "http://192.168.1.20:4533", "Alex")

    @After
    fun stop() {
        scope.cancel()
        server.close()
    }

    private fun until(what: String, check: () -> Boolean) = runBlocking {
        withTimeoutOrNull(5_000) { while (!check()) delay(5) }
        assertTrue("$what: calls ${server.calls.map { it.url.encodedPath }}", check())
    }

    private fun settle() = runBlocking { delay(200) }

    // Each token lasts two minutes from when it was made, by the test's clock.
    private fun answerStarts() = server.raw("start") {
        val n = server.called("start").size
        """{"token":"tok_$n","expires":"${Instant.ofEpochMilli(now.get() + 120_000)}","links":{"anywhere":"https://music.example.com","home":"http://192.168.1.20:4533"},"anywhereAvailable":true}"""
    }

    private fun source() = HandOverSource({ server.client() }, scope, { "Alex" }, now::get, tickMs = 10, askEveryMs = 20)

    @Test
    fun aSealedSignInOpensWithItsKey() {
        val key = HandOverBox.newKey()
        assertEquals(43, key.length)
        val box = HandOverBox.seal(signIn, key)
        assertFalse(box.contains("secret"))
        assertEquals(signIn, HandOverBox.open(box, key))
        // A fresh nonce each time: the same sign-in never seals the same way.
        assertNotEquals(box, HandOverBox.seal(signIn, key))
        assertFalse(signIn.toString().contains("secret"))
    }

    @Test
    fun aWrongKeyFailsCleanly() {
        val box = HandOverBox.seal(signIn, HandOverBox.newKey())
        try {
            HandOverBox.open(box, HandOverBox.newKey())
            fail("A wrong key must not open the box")
        } catch (e: HandOverException) {
            assertEquals("This sign-in could not be opened here. Make a new QR code on your other device.", e.message)
        }
        // A changed box, a damaged one, or a short key fail the same clean way.
        val key = HandOverBox.newKey()
        val sealed = HandOverBox.seal(signIn, key)
        val changed = sealed.dropLast(2) + (if (sealed.endsWith("AA")) "BB" else "AA")
        for (bad in listOf(changed, "not a box", "")) {
            try {
                HandOverBox.open(bad, key)
                fail("A bad box must not open: $bad")
            } catch (e: HandOverException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
        try {
            HandOverBox.open(sealed, "c2hvcnQ")
            fail("A short key must not be used")
        } catch (e: HandOverException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun theSignedInDeviceSendsNothingBeforeAllow() {
        answerStarts()
        server.raw("pending") { "" }
        server.raw("decide") { "" }
        server.raw("box") { "" }
        val source = source()
        source.open()
        until("code") { source.sheet?.start != null }
        val sheet = source.sheet!!
        assertEquals("tok_1", sheet.start!!.token)
        val link = sheet.options(awayAllowed = true)!!.linkFor(LinkReach.Anywhere)!!
        assertTrue(link.startsWith("https://music.example.com/family/signin#t=tok_1&k="))
        assertEquals(sheet.key, (parseFamilyLink(link) as FamilyHandOverLink).key)

        // A device redeems the token; the sheet asks.
        server.raw("pending") { """{"id":"r_1","deviceName":"Pixel 9","platform":"Android"}""" }
        until("asking") { source.sheet?.asking != null }
        assertEquals("Sign in on Pixel 9?", askingTitle(source.sheet!!.asking!!))
        settle()
        assertTrue("nothing decided yet", server.called("decide").isEmpty())
        assertTrue("nothing sent yet", server.called("box").isEmpty())

        source.allow()
        until("sent") { source.sheet?.done != null }
        assertEquals("Sent to Pixel 9. It's signing in now.", source.sheet!!.done)
        val decide = server.called("decide").single()
        assertEquals("""{"allow":true}""", decide.body!!.utf8())
        val boxCall = server.called("box").single()
        assertTrue("decided before sending", server.calls.indexOf(decide) < server.calls.indexOf(boxCall))
        // The box opens with the key from the QR code, and holds this app's sign-in.
        val box = boxCall.body!!.utf8().substringAfter("\"box\":\"").substringBefore('"')
        val opened = HandOverBox.open(box, sheet.key)
        assertEquals(HandOverSignIn("alex", "secret", "Token", "https://music.example.com", "http://192.168.1.20:4533", "Alex"), opened)
        // The key never went to the server, nor the password in the clear.
        server.calls.forEach { call ->
            assertFalse(call.url.toString().contains(sheet.key))
            assertFalse(call.body?.utf8().orEmpty().contains(sheet.key))
            assertFalse(call.body?.utf8().orEmpty().contains("secret"))
        }
    }

    @Test
    fun denyingTellsTheServerAndShowsAFreshCode() {
        answerStarts()
        server.raw("pending") { """{"id":"r_1","deviceName":"Someone's laptop","platform":"Windows"}""" }
        server.raw("decide") { "" }
        val source = source()
        source.open()
        until("asking") { source.sheet?.asking != null }
        val first = source.sheet!!.key
        server.raw("pending") { "" }
        source.deny()
        until("fresh code") { source.sheet?.start?.token == "tok_2" && source.sheet?.loading == false }
        assertEquals("""{"allow":false}""", server.called("decide").single().body!!.utf8())
        assertTrue(server.called("box").isEmpty())
        assertNotEquals("a new key with the new code", first, source.sheet!!.key)
        assertNull(source.sheet!!.asking)
    }

    @Test
    fun theCodeRenewsBeforeItExpiresPausesWhenHiddenAndStopsAfterTenMinutes() {
        answerStarts()
        server.raw("pending") { "" }
        val source = source()
        source.open()
        until("code") { source.sheet?.start != null }
        val firstKey = source.sheet!!.key
        val expires = expiresAtMs(source.sheet!!.start!!.expires)!!

        source.sheetOnScreen(false)
        settle()
        now.set(expires + 10_000)
        settle()
        assertEquals("paused while hidden", 1, server.called("start").size)
        source.sheetOnScreen(true)
        until("renewed on return") { source.sheet?.renewed == 1 }
        assertNotEquals(firstKey, source.sheet!!.key)

        // Renewed on time until ten minutes are up, then it asks instead.
        while (now.get() - start < HANDOVER_FOR_MS) {
            val renewals = source.sheet!!.renewed
            now.set(expiresAtMs(source.sheet!!.start!!.expires)!! - 1_000)
            until("renewal") { source.sheet?.renewed == renewals + 1 || source.sheet?.stale == true }
            if (source.sheet!!.stale) break
        }
        now.set(expiresAtMs(source.sheet!!.start!!.expires)!! - 1_000)
        until("asks instead") { source.sheet?.stale == true }
        val made = server.called("start").size
        settle()
        assertEquals("no code past ten minutes", made, server.called("start").size)
        source.newCode()
        until("new code") { source.sheet?.stale == false && server.called("start").size == made + 1 }
    }

    @Test
    fun theNewDeviceNeverSendsTheKey() = runBlocking {
        val link = FamilyHandOverLink(server.url.toString().removeSuffix("/"), "tok_9", HandOverBox.newKey(), "https://music.example.com", "http://192.168.1.20:4533")
        val box = HandOverBox.seal(signIn, link.key)
        server.raw("redeem") { """{"id":"r_1"}""" }
        val polls = AtomicInteger(0)
        server.raw("r_1") { if (polls.incrementAndGet() < 2) """{"state":"Waiting"}""" else """{"state":"Allowed","box":"$box"}""" }
        val steps = mutableListOf<String>()
        val result = receiveHandOver(link, OkHttpClient(), "Pixel 9", FamilyPlatform.Android, { steps += it }, pollMs = 10)
        assertEquals(signIn, (result as HandOverResult.Received).signIn)
        assertEquals(listOf(HANDOVER_WAITING, HANDOVER_ALLOWED), steps)
        val redeem = server.called("redeem").single()
        assertEquals("""{"token":"tok_9","deviceName":"Pixel 9","platform":"Android"}""", redeem.body!!.utf8())
        // The part after # never reaches the server: not the key, not the link.
        server.calls.forEach { call ->
            assertFalse(call.url.toString().contains(link.key))
            assertFalse(call.url.toString().contains("#"))
            assertFalse(call.body?.utf8().orEmpty().contains(link.key))
        }
    }

    @Test
    fun aDeniedOrExpiredHandOverSaysSo() = runBlocking {
        val link = FamilyHandOverLink(server.url.toString().removeSuffix("/"), "tok_9", HandOverBox.newKey(), "https://music.example.com")
        server.raw("redeem") { """{"id":"r_1"}""" }
        server.raw("r_1") { """{"state":"Denied"}""" }
        assertEquals(HANDOVER_DENIED, (receiveHandOver(link, OkHttpClient(), "Pixel 9", FamilyPlatform.Android, pollMs = 10) as HandOverResult.Refused).message)
        server.raw("r_1") { """{"state":"Expired"}""" }
        assertEquals(HANDOVER_EXPIRED, (receiveHandOver(link, OkHttpClient(), "Pixel 9", FamilyPlatform.Android, pollMs = 10) as HandOverResult.Refused).message)
        // Nobody answers in time.
        server.raw("r_1") { """{"state":"Waiting"}""" }
        assertEquals(HANDOVER_EXPIRED, (receiveHandOver(link, OkHttpClient(), "Pixel 9", FamilyPlatform.Android, pollMs = 10, waitMs = 50) as HandOverResult.Refused).message)
        // A token already used or out of date.
        server.raw("redeem") { throw IllegalStateException() }
        assertEquals(HANDOVER_EXPIRED, (receiveHandOver(link, OkHttpClient(), "Pixel 9", FamilyPlatform.Android, pollMs = 10) as HandOverResult.Refused).message)
    }

    @Test
    fun aBoxSealedWithAnotherKeyIsRefused() = runBlocking {
        val link = FamilyHandOverLink(server.url.toString().removeSuffix("/"), "tok_9", HandOverBox.newKey(), "https://music.example.com")
        val other = HandOverBox.seal(signIn, HandOverBox.newKey())
        server.raw("redeem") { """{"id":"r_1"}""" }
        server.raw("r_1") { """{"state":"Allowed","box":"$other"}""" }
        val result = receiveHandOver(link, OkHttpClient(), "Pixel 9", FamilyPlatform.Android, pollMs = 10)
        assertEquals("This sign-in could not be opened here. Make a new QR code on your other device.", (result as HandOverResult.Refused).message)
    }
}

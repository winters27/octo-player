package app.winters.octo.desktop.discord

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// A pretend Discord, in memory: it reads Octo's frames, answers as Discord
// does, and can refuse Octo, go quiet, ping, or quit. Nothing here reaches
// the real Discord.
class FakeDiscord(
    private val accepts: Boolean = true,
    private val answers: Boolean = true,
) : IpcPipe {
    private val incoming = FrameBuffer()
    private var outgoing = ByteArray(0)
    var gone = false
    var closed = false
    val handshakes = ArrayList<JsonObject>()
    // Each status set, null for a clear.
    val statuses = ArrayList<JsonObject?>()
    val pongs = ArrayList<String>()

    fun ping() = reply(OP_PING, """{"n":1}""")

    private fun reply(op: Int, body: String) {
        outgoing += encodeFrame(op, body)
    }

    override fun write(bytes: ByteArray) {
        if (gone) throw IOException("The pipe has been ended")
        incoming.add(bytes, bytes.size)
        while (true) {
            val frame = incoming.take() ?: break
            when (frame.op) {
                OP_HANDSHAKE -> {
                    handshakes += Json.parseToJsonElement(frame.body).jsonObject
                    if (accepts) reply(OP_FRAME, """{"cmd":"DISPATCH","evt":"READY","data":{"v":1}}""") else reply(OP_CLOSE, """{"code":4000,"message":"Invalid Client ID"}""")
                }
                OP_FRAME -> {
                    val command = Json.parseToJsonElement(frame.body).jsonObject
                    val activity = command["args"]!!.jsonObject["activity"]
                    statuses += if (activity == null || activity is JsonNull) null else activity.jsonObject
                    if (answers) reply(OP_FRAME, """{"cmd":"SET_ACTIVITY","nonce":${command["nonce"]}}""")
                }
                OP_PONG -> pongs += frame.body
                OP_CLOSE -> closed = true
            }
        }
    }

    override fun readNow(into: ByteArray): Int {
        if (gone) return -1
        val count = minOf(outgoing.size, into.size)
        outgoing.copyInto(into, 0, 0, count)
        outgoing = outgoing.copyOfRange(count, outgoing.size)
        return count
    }

    override fun close() {
        closed = true
    }
}

class DiscordSyncTest {
    private var clock = 0L
    // Discord as it is now: running (a pipe to hand out) or not.
    private var discord: FakeDiscord? = null
    private var opens = 0

    private val sync = DiscordSync(
        appId = "1234567890123456789",
        opener = PipeOpener {
            opens++
            discord
        },
        pid = 7,
        connect = { DiscordConnection(it, clock = { clock }, pause = { ms -> clock += ms }) },
    )

    private fun song(title: String = "Karma Police", start: Long = clock) = DiscordActivity(title, "Radiohead", "OK Computer", start, start + 264_000)

    private fun later(ms: Long) {
        clock += ms
    }

    @Test
    fun itIntroducesOctoAndShowsTheSong() {
        discord = FakeDiscord()
        sync.step(enabled = true, wanted = song(), nowMs = clock)
        val fake = discord!!
        assertEquals("1234567890123456789", fake.handshakes.single()["client_id"]!!.jsonPrimitive.content)
        assertEquals("Karma Police", fake.statuses.single()!!["details"]!!.jsonPrimitive.content)
        assertTrue(sync.connected)
    }

    @Test
    fun theSameSongIsNotSentAgainButASeekIs() {
        discord = FakeDiscord()
        val shown = song()
        sync.step(true, shown, clock)
        later(SEND_GAP_MS)
        sync.step(true, shown.copy(startMs = shown.startMs + 500), clock)
        assertEquals("a moment of drift sends nothing", 1, discord!!.statuses.size)
        sync.step(true, shown.copy(startMs = shown.startMs - 60_000, endMs = shown.endMs!! - 60_000), clock)
        assertEquals(2, discord!!.statuses.size)
    }

    @Test
    fun pausingClearsTheStatus() {
        discord = FakeDiscord()
        sync.step(true, song(), clock)
        later(SEND_GAP_MS)
        sync.step(true, null, clock)
        assertNull(discord!!.statuses.last())
        later(SEND_GAP_MS)
        sync.step(true, null, clock)
        assertEquals("cleared once", 2, discord!!.statuses.size)
    }

    @Test
    fun nothingToShowNeverReachesDiscord() {
        discord = FakeDiscord()
        repeat(5) {
            sync.step(true, null, clock)
            later(10_000)
        }
        assertEquals(0, opens)
        assertTrue(discord!!.handshakes.isEmpty())
    }

    @Test
    fun turningItOffClearsAndLetsGo() {
        discord = FakeDiscord()
        sync.step(true, song(), clock)
        sync.step(false, song(), clock)
        assertNull("cleared at once, however soon", discord!!.statuses.last())
        assertTrue(discord!!.closed)
        assertFalse(sync.connected)
        val before = opens
        sync.step(false, song(), clock + 100_000)
        assertEquals("off, it never looks for Discord", before, opens)
    }

    @Test
    fun changesComeNoFasterThanDiscordTakesThem() {
        discord = FakeDiscord()
        sync.step(true, song("One"), clock)
        later(1_000)
        sync.step(true, song("Two"), clock)
        assertEquals("too soon", 1, discord!!.statuses.size)
        later(SEND_GAP_MS)
        sync.step(true, song("Two"), clock)
        assertEquals("Two", discord!!.statuses.last()!!["details"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutDiscordItTriesAgainLessOftenAndFindsItWhenItOpens() {
        discord = null
        // One song playing on all along: its times do not move.
        val playing = song(start = 0)
        val tries = ArrayList<Long>()
        // Five minutes, a step a second, with Discord closed.
        repeat(300) {
            val before = opens
            sync.step(true, playing, clock)
            if (opens > before) tries += clock
            later(1_000)
        }
        val gaps = tries.zipWithNext { a, b -> b - a }
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), gaps.take(7))
        // Discord opens: found within the minute.
        discord = FakeDiscord()
        var found = -1L
        repeat(61) {
            sync.step(true, playing, clock)
            if (found < 0 && sync.connected) found = clock
            later(1_000)
        }
        assertTrue(found >= 0)
        assertEquals(1, discord!!.statuses.size)
    }

    @Test
    fun whenDiscordQuitsItReconnectsQuietlyAndShowsTheSongAgain() {
        discord = FakeDiscord()
        sync.step(true, song(), clock)
        discord!!.gone = true
        later(SEND_GAP_MS)
        // A seek, so there is something to send; the send finds Discord gone.
        sync.step(true, song(start = clock - 90_000), clock)
        assertFalse(sync.connected)
        // Discord comes back; the next try (2 s later) finds it.
        discord = FakeDiscord()
        later(2_000)
        sync.step(true, song(start = clock - 92_000), clock)
        assertTrue(sync.connected)
        assertEquals(1, discord!!.statuses.size)
    }

    @Test
    fun anApplicationDiscordRefusesIsTriedAgainLater() {
        discord = FakeDiscord(accepts = false)
        sync.step(true, song(), clock)
        assertFalse(sync.connected)
        assertTrue("the refused pipe is closed", discord!!.closed)
        assertTrue(discord!!.statuses.isEmpty())
        val before = opens
        later(1_000)
        sync.step(true, song(), clock)
        assertEquals("waits before trying again", before, opens)
    }

    @Test
    fun aQuietDiscordDoesNotHoldOcto() {
        // Discord takes the status but never answers: a moment's wait, no more.
        discord = FakeDiscord(answers = false)
        val start = clock
        sync.step(true, song(), clock)
        assertEquals(1, discord!!.statuses.size)
        assertTrue(clock - start <= ANSWER_WAIT_MS + 100)
        assertTrue(sync.connected)
    }

    @Test
    fun aPingIsAnswered() {
        val fake = FakeDiscord()
        val connection = DiscordConnection(fake, clock = { clock }, pause = { clock += it })
        assertTrue(connection.handshake("1234567890123456789"))
        fake.ping()
        assertNull(connection.receive(100))
        assertEquals(listOf("""{"n":1}"""), fake.pongs)
    }

    // The worker thread: shows what it is handed, tells when it connects,
    // and clears the status on the way out.
    @Test
    fun theWorkerShowsTheSongAndClearsItOnQuit() {
        val fake = FakeDiscord()
        val presence = DiscordPresence(DiscordSync("1234567890123456789", { fake }, pid = 7))
        val connected = java.util.concurrent.CountDownLatch(1)
        presence.onConnected = { if (it) connected.countDown() }
        presence.start()
        presence.want(true, song())
        assertTrue(connected.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val end = System.currentTimeMillis() + 5_000
        while (synchronized(fake) { fake.statuses.isEmpty() } && System.currentTimeMillis() < end) Thread.sleep(10)
        presence.close()
        assertEquals("Karma Police", fake.statuses.first()!!["details"]!!.jsonPrimitive.content)
        assertNull("cleared on quit", fake.statuses.last())
        assertTrue(fake.closed)
    }

    @Test
    fun framesAreCutWhereverTheBytesBreak() {
        val bytes = encodeFrame(OP_FRAME, """{"a":"é"}""") + encodeFrame(OP_CLOSE, "{}")
        val buffer = FrameBuffer()
        val frames = ArrayList<IpcFrame>()
        bytes.forEach { byte ->
            buffer.add(byteArrayOf(byte), 1)
            buffer.take()?.let(frames::add)
        }
        assertEquals(listOf(IpcFrame(OP_FRAME, """{"a":"é"}"""), IpcFrame(OP_CLOSE, "{}")), frames)
    }
}

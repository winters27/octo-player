package app.winters.octo.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.winters.octo.output.OutputSwitch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A station's refill and Autoplay on the phone's player through the one
// mixer: each add is the app's own upkeep, so one landing while a blend is
// set up or under way waits for it, runs once when it is over with its
// guards checked again, and the blend is kept to its end.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpkeepEndToEndTest {
    private val crossfade = AutomixSettings(maxOverlapMs = 1_500, smart = false)
    private val scope = MainScope()

    @After
    fun close() {
        scope.cancel()
        PlayerRig.closeAll()
    }

    private fun songs() = listOf(PcmSong("a", 5_000), PcmSong("b", 2_500, sign = -1), PcmSong("c", 2_000, flip = true))

    // The blend from a to b ran to its end, nothing cut or canceled it.
    private fun blendKept(rig: PlayerRig) {
        assertTrue(rig.mixedCount() > 0)
        assertFalse(rig.told(), rig.events.any { it.generation == 1L && (it.kind == BlendEventKind.CANCELLED || it.kind == BlendEventKind.INCOMING_NOT_READY) })
        val end = rig.events.first { it.kind == BlendEventKind.BLEND_END && it.generation == 1L }
        assertTrue("ended ${end.ms} ms in", end.ms > 1_000)
    }

    @Test
    fun aStationsRefillWhoseAnswerComesOnceTheSpareIsLoadedWaitsForTheBlendAndAddsOnce() {
        val rig = PlayerRig(songs())
        val station = QueueSource.Station("st", "St")
        val answer = CompletableDeferred<Unit>()
        var asked = 0
        val refill = StationRefill({ true }, { _, _ -> asked++; answer.await(); listOf("c") }, { ids -> ids.map { rig.song(it).item() } })
        refill.attach(OutputSwitch(rig.player) { null }, scope)
        rig.player.automix = crossfade
        rig.player.setMediaItems(listOf(rig.song("a").item().withSource(station), rig.song("b").item().withSource(station)))
        rig.player.prepare()
        rig.player.play()
        rig.runFor(20)
        // Asked before the spare is loaded; the server answers after.
        refill.check()
        rig.runFor(5)
        assertEquals(1, asked)
        assertTrue(rig.runUntil(4_000) { rig.decks[1].playbackState != Player.STATE_IDLE })
        answer.complete(Unit)
        rig.runFor(20)
        assertEquals("the add waits", 2, rig.player.mediaItemCount)
        assertTrue(rig.runUntil(9_000) { rig.transitions.lastOrNull() == "q:b" })
        // Asked again in the blend: once for each last song.
        refill.check()
        rig.runFor(20)
        assertEquals(1, asked)
        assertEquals("still waiting through the blend", 2, rig.player.mediaItemCount)
        assertTrue(rig.runUntil(6_000) { !rig.player.isBlending })
        rig.runFor(20)
        assertEquals(listOf("a", "b", "c"), List(rig.player.mediaItemCount) { rig.player.getMediaItemAt(it).mediaId })
        // The song added too late for its own fixed crossfade is refused
        // once, then reached by the song's own advance: never armed again
        // and again.
        rig.runFor(300)
        val armed = commitGenerationOf(rig.mix.mixer.commitWord.get())
        assertTrue("armed $armed times", armed <= 3)
        assertTrue(rig.runUntil(6_000) { rig.player.playbackState == Player.STATE_ENDED })
        blendKept(rig)
        assertEquals(listOf("q:a", "q:b", "q:c"), rig.transitions)
    }

    @Test
    fun aSongPutNextWhileAStationsRefillWaitsIsNotAddedAgainByIt() {
        val rig = PlayerRig(songs())
        val station = QueueSource.Station("st", "St")
        val answer = CompletableDeferred<Unit>()
        val refill = StationRefill({ true }, { _, _ -> answer.await(); listOf("c") }, { ids -> ids.map { rig.song(it).item() } })
        refill.attach(OutputSwitch(rig.player) { null }, scope)
        rig.player.automix = crossfade
        rig.player.setMediaItems(listOf(rig.song("a").item().withSource(station), rig.song("b").item().withSource(station)))
        rig.player.prepare()
        rig.player.play()
        rig.runFor(20)
        refill.check()
        rig.runFor(5)
        assertTrue(rig.runUntil(4_000) { rig.decks[1].playbackState != Player.STATE_IDLE })
        // The server's answer, "c", waits for the blend.
        answer.complete(Unit)
        rig.runFor(20)
        assertEquals("the add waits", 2, rig.player.mediaItemCount)
        // The person puts "c" next meanwhile; the queue still ends with "b".
        rig.player.addMediaItem(rig.player.currentMediaItemIndex + 1, rig.song("c").item())
        assertTrue(rig.runUntil(15_000) { rig.player.playbackState == Player.STATE_ENDED })
        assertEquals(listOf("a", "c", "b"), List(rig.player.mediaItemCount) { rig.player.getMediaItemAt(it).mediaId })
    }

    @Test
    fun anAutoplayAddForAShortLastSongLandsInItsBlendWaitsAndAddsOnceAfter() {
        val rig = PlayerRig(songs())
        val asked = ArrayList<String>()
        val refill = StationRefill({ false }, { _, _ -> emptyList() }, { emptyList() })
        val autoplay = Autoplay(flowOf(true), { seed, _, _, _ -> asked += seed; listOf("c") }, { ids -> ids.map { rig.song(it).item() } }, refill, {})
        autoplay.attach(OutputSwitch(rig.player) { null }, scope)
        rig.play("a", "b", settings = crossfade)
        // b is the last song and short: its add is due as soon as it is on,
        // inside the blend.
        assertTrue(rig.runUntil(9_000) { rig.transitions.lastOrNull() == "q:b" })
        autoplay.check()
        rig.runFor(50)
        assertEquals(listOf("b"), asked)
        assertEquals("waits through the blend", 2, rig.player.mediaItemCount)
        assertTrue(rig.runUntil(6_000) { !rig.player.isBlending })
        rig.runFor(20)
        assertEquals(listOf("a", "b", "c"), List(rig.player.mediaItemCount) { rig.player.getMediaItemAt(it).mediaId })
        assertTrue(rig.player.getMediaItemAt(2).isAutoplay)
        autoplay.check()
        rig.runFor(20)
        assertEquals("tried once for its song", listOf("b"), asked)
        blendKept(rig)
        autoplay.detach()
    }
}

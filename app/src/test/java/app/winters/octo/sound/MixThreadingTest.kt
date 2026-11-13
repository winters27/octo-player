package app.winters.octo.sound

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ListenerSet
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// Real outputs built by Media3's own provider (made with no context) on
// the builder thread, then used only from the mix thread: every call the
// mixer makes runs there with no exception, a dead object included, and no
// Media3 listener is added or removed; a release from the mix thread
// posts its onReleased on a looper; a deck output's events reach its own
// playback looper.
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MixThreadingTest {
    private val threads = ArrayList<HandlerThread>()
    private val crashes = CopyOnWriteArrayList<Throwable>()

    private fun thread(name: String): HandlerThread =
        HandlerThread(name).also {
            it.start()
            it.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
            threads += it
        }

    @After
    fun stop() {
        for (t in threads) t.quitSafely()
    }

    // Runs `block` on `t`'s looper and waits for it, passing on what it threw.
    private fun <T> on(t: HandlerThread, block: () -> T): T {
        val done = CountDownLatch(1)
        val out = AtomicReference<Any?>()
        Handler(t.looper).post {
            out.set(try { block() } catch (e: Throwable) { e })
            done.countDown()
        }
        assertTrue("ran on ${t.name}", done.await(5, TimeUnit.SECONDS))
        val v = out.get()
        if (v is Throwable) throw AssertionError("on ${t.name}", v)
        @Suppress("UNCHECKED_CAST")
        return v as T
    }

    private fun listeners(o: AudioTrackAudioOutput): Int {
        val f = AudioTrackAudioOutput::class.java.getDeclaredField("listeners")
        f.isAccessible = true
        return (f.get(o) as ListenerSet<*>).size()
    }

    // The Media3 output an AudioTrackRealOutput wraps.
    private val AudioTrackRealOutput.output: AudioTrackAudioOutput
        get() {
            val f = AudioTrackRealOutput::class.java.getDeclaredField("out")
            f.isAccessible = true
            return f.get(this) as AudioTrackAudioOutput
        }

    @Test
    fun everyRealOutputCallRunsOnTheMixThreadAfterABuildOnTheBuilderThread() {
        val build = thread("build")
        val mix = thread("mix")
        val builder = on(build) { AudioTrackOutputBuilder(48_000, AudioAttributes.DEFAULT, C.AUDIO_SESSION_ID_UNSET) }
        // The first output, a rebuild and a restart's, all from the builder thread.
        repeat(3) {
            val out = on(build) { builder.build(24_000) } as AudioTrackRealOutput
            val samples = FloatArray(4_800 * 2) { 0.1f }
            on(mix) {
                out.setPreferredDevice(null)
                out.listenForRoutes(Handler(Looper.myLooper()!!)) {}
                out.setVolume(0.5f)
                out.setStartThreshold(2_400)
                // The buffer as built, whatever size API 29 and 30 put in use.
                val built = out.bufferFrames
                out.setBufferSize(960)
                assertEquals(built, out.bufferFrames)
                out.setBufferSize(built)
                out.routedDevice()
                out.play()
                out.write(samples, 0, 4_800)
                out.head()
                out.timestamp(RealTimestamp())
                out.underruns()
                out.isStalled()
                out.setAuxEffectSendLevel(0f)
                out.pause()
            }
            // The mixer adds no Media3 listener of its own (read on the
            // thread the output's listener set belongs to).
            assertEquals(0, on(build) { listeners(out.output) })
            on(mix) { out.release() }
        }
        on(build) { builder.release() }
        assertTrue("$crashes", crashes.isEmpty())
    }

    // An AudioTrack whose writes find its object dead.
    private class DeadTrack : AudioTrack(
        android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build(),
        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
        48_000 * 8 / 2,
        MODE_STREAM,
        AudioManager.AUDIO_SESSION_ID_GENERATE,
    ) {
        override fun write(audioData: ByteBuffer, sizeInBytes: Int, writeMode: Int): Int = ERROR_DEAD_OBJECT
    }

    // An AudioTrack whose size in use follows setBufferSizeInFrames, as a
    // device's does.
    private class SizedTrack : AudioTrack(
        android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build(),
        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
        48_000 * 8 / 2,
        MODE_STREAM,
        AudioManager.AUDIO_SESSION_ID_GENERATE,
    ) {
        private var size = 24_000

        override fun setBufferSizeInFrames(bufferSizeInFrames: Int): Int {
            size = minOf(bufferSizeInFrames, 24_000)
            return size
        }

        override fun getBufferSizeInFrames(): Int = size
    }

    @Test
    fun aRealOutputsBufferIsTheOneItWasBuiltWithWhateverSizeIsInUse() {
        val build = thread("build")
        val mix = thread("mix")
        val out = on(build) {
            AudioTrackRealOutput(AudioTrackAudioOutput(SizedTrack(), config(), object : AudioTrackAudioOutput.CapabilityChangeListener {
                override fun onRoutedDeviceChanged(device: AudioDeviceInfo) {}

                override fun onRecoverableWriteError() {}
            }, Clock.DEFAULT))
        }
        on(mix) {
            assertEquals(24_000, out.bufferFrames)
            // API 29 and 30's start size, then the whole buffer put back.
            out.setBufferSize(2_400)
            assertEquals(24_000, out.bufferFrames)
            out.setBufferSize(out.bufferFrames)
            out.release()
        }
        assertTrue("$crashes", crashes.isEmpty())
    }

    private fun config(): OutputConfig =
        OutputConfig.Builder()
            .setEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setBufferSize(48_000 * 8 / 2)
            .setAudioAttributes(AudioAttributes.DEFAULT)
            .setAudioSessionId(C.AUDIO_SESSION_ID_UNSET)
            .build()

    @Test
    fun aDeadObjectWriteOnTheMixThreadIsReadAsDeadWithNothingThrownByMedia3() {
        val build = thread("build")
        val mix = thread("mix")
        val routed = AtomicInteger()
        val errors = AtomicInteger()
        val out = on(build) {
            val o = AudioTrackAudioOutput(DeadTrack(), config(), object : AudioTrackAudioOutput.CapabilityChangeListener {
                override fun onRoutedDeviceChanged(device: AudioDeviceInfo) {
                    routed.incrementAndGet()
                }

                override fun onRecoverableWriteError() {
                    errors.incrementAndGet()
                }
            }, Clock.DEFAULT)
            AudioTrackRealOutput(o)
        }
        val dead = on(mix) {
            out.play()
            try {
                out.write(FloatArray(960 * 2), 0, 960)
                false
            } catch (e: RealOutputDead) {
                true
            }
        }
        assertTrue("read as dead", dead)
        on(mix) { out.release() }
        assertTrue("$crashes", crashes.isEmpty())
    }

    @Test
    fun aReleaseFromTheMixThreadPostsBackToItsOwnLooperWithNothingThrown() {
        val build = thread("build")
        val mix = thread("mix")
        val builder = on(build) { AudioTrackOutputBuilder(48_000, AudioAttributes.DEFAULT, C.AUDIO_SESSION_ID_UNSET) }
        val out = on(build) { builder.build(24_000) as AudioTrackRealOutput }
        // Media3 makes a handler for the releasing thread's looper, releases
        // the track 20 ms later and posts back to that looper; the mix
        // thread is a looper thread, so this neither throws nor stalls.
        on(mix) { out.release() }
        assertTrue(on(build) { builder.hasPendingReleases() })
        val deadline = System.currentTimeMillis() + 3_000
        while (on(build) { builder.hasPendingReleases() } && System.currentTimeMillis() < deadline) {
            shadowOf(mix.looper).idle()
            Thread.sleep(5)
        }
        assertTrue("the release finished", !on(build) { builder.hasPendingReleases() })
        shadowOf(mix.looper).idle()
        assertTrue("$crashes", crashes.isEmpty())
    }

    @Test
    fun aDeckOutputsEventsAndItsPauseRunOnItsOwnPlaybackLooper() {
        val deckThread = thread("deck")
        val rig = MixRig()
        val output = on(deckThread) {
            val labels = DeckTimeQueue()
            val provider = rig.mixer.provider(0, labels)
            val config = OutputConfig.Builder()
                .setEncoding(C.ENCODING_PCM_FLOAT)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setBufferSize(24_000 * 8)
                .setAudioAttributes(AudioAttributes.DEFAULT)
                .setAudioSessionId(7)
                .build()
            provider.getAudioOutput(config) as MixAudioOutput
        }
        val events = CopyOnWriteArrayList<String>()
        on(deckThread) {
            output.addListener(object : AudioOutput.Listener {
                override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
                    events += Thread.currentThread().name
                }
                override fun onOffloadDataRequest() {}
                override fun onOffloadPresentationEnded() {}
                override fun onUnderrun() {}
                override fun onReleased() {}
            })
        }
        // The mixer reports a deck's first presented frame from its own thread.
        val mix = thread("mix")
        on(mix) { output.firePositionAdvancing() }
        on(deckThread) {}
        assertEquals(listOf("deck"), events.toList())
        // Its pause is acted on from its own looper once the call's message ends.
        on(deckThread) {
            output.play()
            output.pause()
            assertTrue("still playing within the call", output.playing)
        }
        on(deckThread) {}
        assertTrue("paused once the message ended", !output.playing)
        assertTrue("$crashes", crashes.isEmpty())
    }

    // The whole mixer on its own threads.

    // A virtual nanosecond clock that runs with Robolectric's system clock.
    private val virtual = object : Clock by Clock.DEFAULT {
        override fun nanoTime(): Long = SystemClock.uptimeMillis() * 1_000_000L
    }

    private class Checked(val inner: RealOutput, val mix: Thread, val wrong: MutableList<String>, val playing: AtomicInteger) : RealOutput {
        private fun on(name: String) {
            if (Thread.currentThread() !== mix) wrong += "$name on ${Thread.currentThread().name}"
        }

        override val bufferFrames: Int get() = inner.bufferFrames.also { on("bufferFrames") }

        override fun write(samples: FloatArray, offset: Int, frames: Int): Int = inner.write(samples, offset, frames).also { on("write") }

        override fun play() {
            on("play")
            playing.incrementAndGet()
            inner.play()
        }

        override fun pause() {
            on("pause")
            playing.decrementAndGet()
            inner.pause()
        }

        override fun release() {
            on("release")
            inner.release()
        }

        override fun head(): Long = inner.head().also { on("head") }

        override fun timestamp(into: RealTimestamp): Boolean = inner.timestamp(into).also { on("timestamp") }

        override fun underruns(): Int = inner.underruns().also { on("underruns") }

        override fun setVolume(volume: Float) {
            on("setVolume")
            inner.setVolume(volume)
        }

        override fun setStartThreshold(frames: Int): Int = inner.setStartThreshold(frames).also { on("setStartThreshold") }

        override fun setBufferSize(frames: Int): Int = inner.setBufferSize(frames).also { on("setBufferSize") }

        override fun isStalled(): Boolean = inner.isStalled().also { on("isStalled") }

        override fun routedDevice(): String? = inner.routedDevice().also { on("routedDevice") }

        override fun listenForRoutes(handler: Handler, onRouted: () -> Unit) {
            on("listenForRoutes")
            inner.listenForRoutes(handler, onRouted)
        }

        override fun setPreferredDevice(device: AudioDeviceInfo?) {
            on("setPreferredDevice")
            inner.setPreferredDevice(device)
        }

        override fun attachAuxEffect(effectId: Int) {
            on("attachAuxEffect")
            inner.attachAuxEffect(effectId)
        }

        override fun setAuxEffectSendLevel(level: Float) {
            on("setAuxEffectSendLevel")
            inner.setAuxEffectSendLevel(level)
        }
    }

    @Test
    fun theMixerOnItsOwnThreadsCallsItsRealOutputsOnlyFromTheMixThread() {
        val mix = thread("mix")
        val build = thread("build")
        val gate = thread("gate")
        val deckThread = thread("deck")
        val wrong = CopyOnWriteArrayList<String>()
        val builtOn = CopyOnWriteArrayList<String>()
        val playing = AtomicInteger()
        val probeWrong = CopyOnWriteArrayList<String>()
        val real = on(build) { AudioTrackOutputBuilder(48_000, AudioAttributes.DEFAULT, C.AUDIO_SESSION_ID_UNSET) }
        val builder = object : RealOutputBuilder {
            override fun build(bufferFrames: Int): RealOutput {
                builtOn += Thread.currentThread().name
                return Checked(real.build(bufferFrames), mix, wrong, playing)
            }

            override fun hasPendingReleases(): Boolean = real.hasPendingReleases()

            override fun minimumFrames(): Int = real.minimumFrames()

            override fun release() = real.release()
        }
        val main = Handler(Looper.getMainLooper())
        val mixer = DeckMixer(
            mixRate = 48_000,
            sessionId = C.AUDIO_SESSION_ID_UNSET,
            loop = HandlerMixLoop(virtual, mix.looper),
            builderLoop = HandlerMixLoop(virtual, build.looper),
            gateLoop = HandlerMixLoop(virtual, gate.looper),
            builder = builder,
            playbackList = PlaybackList { playing.get().coerceAtLeast(0) },
            toMain = { main.post(it) },
            listener = object : MixListener {},
            tuning = MixTuning(watchdogMs = 60_000),
        )
        mixer.probe = object : MixProbe {
            override fun written(output: Int, frame: Long, samples: FloatArray, offset: Int, frames: Int) {
                if (Thread.currentThread() !== mix) probeWrong += "written on ${Thread.currentThread().name}"
            }

            override fun head(output: Int, nanos: Long, head: Long, written: Long) {
                if (Thread.currentThread() !== mix) probeWrong += "head on ${Thread.currentThread().name}"
            }

            override fun note(what: String, frame: Long) {
                if (Thread.currentThread() !== mix) probeWrong += "note on ${Thread.currentThread().name}"
            }
        }
        val loopers = listOf(mix.looper, build.looper, gate.looper, deckThread.looper)
        fun advance(ms: Int) {
            repeat(ms / 2) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2))
                for (l in loopers) shadowOf(l).idle()
            }
        }
        val labels = DeckTimeQueue()
        val output = on(deckThread) {
            val config = OutputConfig.Builder()
                .setEncoding(C.ENCODING_PCM_FLOAT)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setBufferSize(mixer.deckTargetFrames(48_000) * 8)
                .setAudioAttributes(AudioAttributes.DEFAULT)
                .setAudioSessionId(C.AUDIO_SESSION_ID_UNSET)
                .build()
            mixer.provider(0, labels).getAudioOutput(config) as MixAudioOutput
        }
        var songMs = 0.0
        fun feed() = on(deckThread) {
            val frames = 4_800
            val buffer = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
            repeat(frames * 2) { buffer.putFloat(0.1f) }
            buffer.flip()
            labels.push(frames, songMs, 1_000.0 / 48_000, "e0", songMs == 0.0, Double.NaN, false)
            output.write(buffer, 1, 0)
            val took = frames - buffer.remaining() / 8
            if (took < frames) labels.clear()
            songMs += took * 1_000.0 / 48_000
        }
        on(deckThread) { output.play() }
        repeat(30) {
            feed()
            advance(20)
        }
        // A pause and a resume: a fresh output built on the builder thread.
        mixer.pause(hold = false)
        on(deckThread) { output.pause() }
        advance(400)
        mixer.wantPlaying(true)
        on(deckThread) { output.play() }
        repeat(20) {
            feed()
            advance(20)
        }
        assertTrue("built: $builtOn", builtOn.size >= 2)
        assertTrue("every build on the builder thread: $builtOn", builtOn.all { it == "build" })
        assertTrue("every real-output call on the mix thread: ${wrong.take(5)}", wrong.isEmpty())
        assertTrue("every mixer step on the mix thread: ${probeWrong.take(5)}", probeWrong.isEmpty())
        assertTrue(mixer.release())
        advance(10)
        assertTrue("$crashes", crashes.isEmpty())
    }
}

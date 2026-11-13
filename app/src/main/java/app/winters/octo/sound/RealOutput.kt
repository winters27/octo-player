package app.winters.octo.sound

import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.Clock
import androidx.media3.common.util.HandlerWrapper

// The one output the mixer writes to: on a phone an AudioTrack through
// Media3's AudioTrackAudioOutput, in tests a fake that models the system
// mixer. Built on the builder thread, then used only from the mix thread.
// Frames are float stereo at the mix rate.
interface RealOutput {
    // The buffer in frames, as the system made it.
    val bufferFrames: Int

    // Takes up to `frames` frames from `samples` (from frame `offset`)
    // without blocking and returns how many it took. Throws RealOutputDead
    // when the output can play nothing more.
    fun write(samples: FloatArray, offset: Int, frames: Int): Int

    fun play()

    fun pause()

    fun release()

    // The playback head: frames the system has taken, as the 32-bit
    // counter the platform keeps.
    fun head(): Long

    // The latest presented frame and the time it was presented, or false
    // when the system has none yet.
    fun timestamp(into: RealTimestamp): Boolean

    fun underruns(): Int

    // The track's own volume (the player level, and the fallback's silent
    // span), applied by the system at its next cycle.
    fun setVolume(volume: Float)

    // API 31 and up: the frames the track must hold before it plays, as set.
    fun setStartThreshold(frames: Int): Int

    // API 29 and 30, which have no start threshold: the buffer size in use.
    fun setBufferSize(frames: Int): Int

    // Android 10's head stuck at 0 after a failed Bluetooth connect.
    fun isStalled(): Boolean

    // The device the system routes the track to, as a key for its
    // measures, or null.
    fun routedDevice(): String?

    // Reports re-routes between devices already connected on `handler`.
    fun listenForRoutes(handler: Handler, onRouted: () -> Unit)

    fun setPreferredDevice(device: AudioDeviceInfo?)

    fun attachAuxEffect(effectId: Int)

    fun setAuxEffectSendLevel(level: Float)
}

class RealTimestamp {
    var frame = 0L
    var nanos = 0L
}

// The real output can play nothing more (a dead object, a write error).
class RealOutputDead(val code: Int) : Exception("real output failed with $code")

// Builds real outputs on the builder thread. Media3 binds an output's
// listeners and its error path to the thread that built it, so every build
// runs on one thread for the mixer's whole life.
interface RealOutputBuilder {
    // Builds one at the given buffer size in frames, or throws.
    fun build(bufferFrames: Int): RealOutput

    // Whether an earlier output's release has not finished yet.
    fun hasPendingReleases(): Boolean

    // The smallest buffer the system allows, in frames.
    fun minimumFrames(): Int

    fun release()
}

// The mix thread's loop: messages, wakes at a time, and the clock the
// mixer reads. On a phone a HandlerThread; in tests a step-driven loop on
// a virtual clock.
interface MixLoop {
    fun nanoTime(): Long

    fun post(task: Runnable)

    // Runs `task` once the clock reaches `nanos`; a task already waiting is
    // moved, so one task has at most one pending run.
    fun postAt(nanos: Long, task: Runnable)

    fun remove(task: Runnable)

    fun isCurrent(): Boolean

    // A handler on the loop's thread, for platform listeners.
    fun handler(): Handler

    fun quit()
}

// The phone's mix loop on a looper, its timing from a Media3 clock.
class HandlerMixLoop(private val clock: Clock, private val looper: Looper) : MixLoop {
    private val wrapper: HandlerWrapper = clock.createHandler(looper, null)
    private val plain = Handler(looper)

    override fun nanoTime(): Long = clock.nanoTime()

    override fun post(task: Runnable) {
        wrapper.post(task)
    }

    override fun postAt(nanos: Long, task: Runnable) {
        wrapper.removeCallbacks(task)
        val delayMs = ((nanos - clock.nanoTime()) / 1_000_000L).coerceAtLeast(0)
        wrapper.postDelayed(task, delayMs)
    }

    override fun remove(task: Runnable) {
        wrapper.removeCallbacks(task)
    }

    override fun isCurrent(): Boolean = Looper.myLooper() === looper

    override fun handler(): Handler = plain

    override fun quit() {
        looper.quitSafely()
    }
}

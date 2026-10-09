package app.winters.octo.playback

import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import kotlin.math.max
import kotlin.math.roundToLong

// A deck's buffering, as Media3 does it, except how much of a stream is
// ready before playback starts: `startAfterMs`, from the setting, and after
// running dry at least as much as Media3 waits then. Files on the phone
// start as Media3 starts them. One for each deck.
@OptIn(UnstableApi::class)
class OctoLoadControl : DefaultLoadControl() {
    @Volatile var startAfterMs: Int = StartAfter.Short.ms

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (isLocal(parameters)) return super.shouldStartPlayback(parameters)
        return hasEnoughToStart(parameters.bufferedDurationUs, parameters.playbackSpeed, parameters.rebuffering, startAfterMs)
    }

    // Whether the song is a file on the phone, by its address.
    private fun isLocal(parameters: LoadControl.Parameters): Boolean {
        val timeline = parameters.timeline
        if (timeline.isEmpty) return false
        val period = timeline.getPeriodByUid(parameters.mediaPeriodId.periodUid, Timeline.Period())
        val scheme = timeline.getWindow(period.windowIndex, Timeline.Window()).mediaItem.localConfiguration?.uri?.scheme
        return isLocalScheme(scheme)
    }
}

// Whether an address with this scheme is a file on the phone.
@OptIn(UnstableApi::class)
internal fun isLocalScheme(scheme: String?): Boolean = scheme != null && scheme.lowercase() in DefaultLoadControl.LOCAL_PLAYBACK_SCHEMES

// Whether enough is buffered to start: `startAfterMs` of playing time at
// this speed, or after running dry at least what Media3 waits then.
@OptIn(UnstableApi::class)
internal fun hasEnoughToStart(bufferedUs: Long, speed: Float, rebuffering: Boolean, startAfterMs: Int): Boolean {
    val playout = if (speed == 1f || speed <= 0f) bufferedUs else (bufferedUs / speed.toDouble()).roundToLong()
    val need = if (rebuffering) max(startAfterMs, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS) else startAfterMs
    return playout >= need * 1_000L
}

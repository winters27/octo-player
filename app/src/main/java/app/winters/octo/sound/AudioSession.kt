package app.winters.octo.sound

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// The one audio session both decks play in, so an equalizer app on the phone
// that attaches to Octo keeps working through crossfades. Such apps hear
// about the session through the phone's standard announcements.
@OptIn(UnstableApi::class)
@Singleton
class AudioSession @Inject constructor(@ApplicationContext private val context: Context) {
    // The session's id, or 0 when the phone would not make one.
    val id: Int by lazy { Util.generateAudioSessionId(context) }

    private var announced = false

    // Tells equalizer apps music has started in this session.
    fun open() {
        if (announced || id == C.AUDIO_SESSION_ID_UNSET) return
        announced = true
        context.sendBroadcast(effectIntent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION))
    }

    // Tells them it has ended.
    fun close() {
        if (!announced) return
        announced = false
        context.sendBroadcast(effectIntent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION))
    }

    // Opens the phone's own equalizer for Octo's sound, for a screen to
    // start. Not every phone has one, so check it resolves first.
    fun systemEqualizer(): Intent = effectIntent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)

    private fun effectIntent(action: String) = Intent(action)
        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id)
        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
}

package app.winters.octo.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

// The phone's media volume, from 0 to 1. It follows the volume buttons and
// any other app that changes it, for as long as someone is watching.
@Singleton
class DeviceVolume @Inject constructor(@ApplicationContext private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
    private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    val level: StateFlow<Float> = callbackFlow {
        trySend(current())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(current())
            }
        }
        // Sent by the system whenever a volume changes.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { context.unregisterReceiver(receiver) }
    }.stateIn(MainScope(), SharingStarted.WhileSubscribed(1_000), current())

    fun set(fraction: Float) {
        val index = min + Math.round(fraction.coerceIn(0f, 1f) * (max - min))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }

    private fun current(): Float =
        if (max > min) (audio.getStreamVolume(AudioManager.STREAM_MUSIC) - min).toFloat() / (max - min) else 0f
}

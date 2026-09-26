package app.winters.octo.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.DefaultMediaNotificationProvider

// The id of the brief notification shown when a widget button started the
// service but ended up playing nothing.
private const val STAND_DOWN_ID = 20_939

// Carries out widget buttons inside the playback service. A button starts
// the service in the foreground, which promises the system a notification
// within seconds: playing brings the usual media notification, and anything
// else keeps the promise with a brief one and takes it away again.
@OptIn(UnstableApi::class)
class WidgetRemote(
    private val service: Service,
    private val player: Player,
    private val picks: QuickPicks,
    // Turns song ids into playable songs, skipping any that are gone.
    private val songs: suspend (List<String>) -> List<MediaItem>,
) {
    suspend fun run(command: WidgetCommand) {
        when (command) {
            WidgetCommand.Play -> resume()
            WidgetCommand.Pause -> player.pause()
            WidgetCommand.Next -> player.seekToNext()
            WidgetCommand.Previous -> player.seekToPrevious()
            is WidgetCommand.Quick -> if (command.pick == QuickPick.Resume) {
                resume()
            } else {
                val (ids, shuffle) = picks.songsFor(command.pick)
                play(songs(ids), shuffle)
            }
        }
        val willPlay = player.playWhenReady && player.mediaItemCount > 0
        if (service.foregroundServiceType == 0 && !willPlay) standDown()
    }

    // Carries on with the queue, or with none, shuffles everything, as the
    // app's own play button does.
    private suspend fun resume() {
        if (player.mediaItemCount == 0) {
            play(songs(picks.everything()), shuffle = true)
        } else {
            Util.handlePlayButtonAction(player)
        }
    }

    private fun play(items: List<MediaItem>, shuffle: Boolean) {
        if (items.isEmpty()) return
        player.shuffleModeEnabled = shuffle
        player.setMediaItems(items, if (shuffle) items.indices.random() else 0, 0)
        player.prepare()
        player.play()
    }

    // Shows a quiet notification on the media channel and removes it at
    // once, which is all the system needs.
    private fun standDown() {
        val manager = service.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID) == null) {
            val name = service.getString(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_NAME_RESOURCE_ID)
            manager.createNotificationChannel(
                NotificationChannel(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(service, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setSilent(true)
            .build()
        try {
            ServiceCompat.startForeground(service, STAND_DOWN_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: IllegalStateException) {
            // Refused by the system: then nothing was promised either.
            Log.w("WidgetRemote", "Could not stand down", e)
        }
    }
}

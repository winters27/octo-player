package app.winters.octo.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.winters.octo.MainActivity
import app.winters.octo.playback.OctoPlaybackService

private const val ACTION_COMMAND = "app.winters.octo.widget.COMMAND"
private const val EXTRA_COMMAND = "app.winters.octo.widget.command"

// Set on the intent that opens the app from a widget, asking for the full
// player rather than the library.
const val EXTRA_OPEN_PLAYER = "open_player"

private const val OPEN_APP = 100
private const val OPEN_PLAYER = 101

// A button that goes straight to the playback service without opening the
// app. It starts the service in the foreground, which a tap on a widget is
// allowed to do even while the app is closed; the service then either plays
// or stands down (see `WidgetRemote`).
fun commandIntent(context: Context, command: WidgetCommand): PendingIntent {
    val intent = Intent(context, OctoPlaybackService::class.java)
        .setAction(ACTION_COMMAND)
        .putExtra(EXTRA_COMMAND, encodeCommand(command))
    return PendingIntent.getForegroundService(
        context,
        requestCode(command),
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

// The command a widget sent, or null for any other start of the service.
fun widgetCommand(intent: Intent?): WidgetCommand? =
    if (intent?.action == ACTION_COMMAND) decodeCommand(intent.getStringExtra(EXTRA_COMMAND)) else null

// Opens the app, with the full player up when asked.
fun openAppIntent(context: Context, player: Boolean): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (player) intent.putExtra(EXTRA_OPEN_PLAYER, true)
    return PendingIntent.getActivity(
        context,
        if (player) OPEN_PLAYER else OPEN_APP,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

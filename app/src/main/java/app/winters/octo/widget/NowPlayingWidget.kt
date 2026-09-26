package app.winters.octo.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import app.winters.octo.R
import app.winters.octo.design.OctoIcons

// The now playing widget on the home screen. It shows whatever the playback
// service last told it; with the service not running, that is "Nothing
// playing" and a play button that picks up the saved queue.
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { draw(context, manager, it) }
    }

    // Before Android 12 the widget picks its shape from its size itself.
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        draw(context, manager, appWidgetId)
    }

    companion object {
        // The last word from the playback service, kept for the life of the
        // app's process. Null while nothing is loaded.
        @Volatile private var playback: WidgetPlayback? = null
        @Volatile private var art: Bitmap? = null

        // Called by the playback service when the song changes or it starts
        // or stops playing.
        fun show(context: Context, playback: WidgetPlayback?, art: Bitmap?) {
            this.playback = playback
            this.art = art
            val manager = AppWidgetManager.getInstance(context)
            ids(context, manager).forEach { draw(context, manager, it) }
        }

        // Whether any are on a home screen, so no work is done for none.
        fun placed(context: Context): Boolean = ids(context, AppWidgetManager.getInstance(context)).isNotEmpty()

        private fun ids(context: Context, manager: AppWidgetManager): IntArray =
            manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))

        private fun draw(context: Context, manager: AppWidgetManager, id: Int) {
            val playback = playback
            val art = art
            // Android 12 and later pick the largest shape that fits, as the
            // widget is resized, with no update needed.
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RemoteViews(
                    mapOf(
                        SizeF(SMALL_MIN_WIDTH_DP, SMALL_MIN_HEIGHT_DP) to views(context, WidgetSize.Small, playback, art),
                        SizeF(MEDIUM_MIN_WIDTH_DP, SMALL_MIN_HEIGHT_DP) to views(context, WidgetSize.Medium, playback, art),
                        SizeF(MEDIUM_MIN_WIDTH_DP, LARGE_MIN_HEIGHT_DP) to views(context, WidgetSize.Large, playback, art),
                    ),
                )
            } else {
                val options = manager.getAppWidgetOptions(id)
                val size = widgetSize(
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat(),
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat(),
                )
                views(context, size, playback, art)
            }
            manager.updateAppWidget(id, views)
        }

        private fun views(context: Context, size: WidgetSize, playback: WidgetPlayback?, art: Bitmap?): RemoteViews {
            val layout = when (size) {
                WidgetSize.Small -> R.layout.widget_now_playing_small
                WidgetSize.Medium -> R.layout.widget_now_playing_medium
                WidgetSize.Large -> R.layout.widget_now_playing_large
            }
            val face = nowPlayingFace(playback, size)
            return RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.widget_title, face.title)
                if (face.artist != null) {
                    setTextViewText(R.id.widget_artist, face.artist)
                    setViewVisibility(R.id.widget_artist, View.VISIBLE)
                } else {
                    setViewVisibility(R.id.widget_artist, View.GONE)
                }
                if (art != null && !face.empty) {
                    setImageViewBitmap(R.id.widget_art, art)
                } else {
                    setImageViewResource(R.id.widget_art, R.drawable.widget_art_empty)
                }
                // The picture and the words open the full player.
                val open = openAppIntent(context, player = !face.empty)
                setOnClickPendingIntent(R.id.widget_art, open)
                setOnClickPendingIntent(R.id.widget_text, open)

                setImageViewResource(R.id.widget_play, if (face.playing) OctoIcons.Pause else OctoIcons.Play)
                setContentDescription(R.id.widget_play, context.getString(if (face.playing) R.string.widget_pause else R.string.widget_play))
                setOnClickPendingIntent(R.id.widget_play, commandIntent(context, if (face.playing) WidgetCommand.Pause else WidgetCommand.Play))

                val skips = if (face.showSkips) View.VISIBLE else View.GONE
                setViewVisibility(R.id.widget_previous, skips)
                setViewVisibility(R.id.widget_next, skips)
                if (face.showSkips) {
                    setOnClickPendingIntent(R.id.widget_previous, commandIntent(context, WidgetCommand.Previous))
                    setOnClickPendingIntent(R.id.widget_next, commandIntent(context, WidgetCommand.Next))
                }
            }
        }
    }
}

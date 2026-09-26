package app.winters.octo.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import app.winters.octo.R

// Four shortcuts on the home screen that start music without opening the
// app. Nothing on it changes, so it is drawn once when placed.
class QuickPlayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_play).apply {
            tiles.forEach { (id, pick) -> setOnClickPendingIntent(id, commandIntent(context, WidgetCommand.Quick(pick))) }
        }
        manager.updateAppWidget(appWidgetIds, views)
    }

    private val tiles = listOf(
        R.id.widget_quick_liked to QuickPick.Liked,
        R.id.widget_quick_recent to QuickPick.RecentlyAdded,
        R.id.widget_quick_mix to QuickPick.AlbumMix,
        R.id.widget_quick_resume to QuickPick.Resume,
    )
}

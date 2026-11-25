package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.DriveCastPreferences
import com.example.ui.formatDuration

/**
 * Home-screen quick-resume widget showing the latest DriveCast video,
 * playback progress, and active equalizer profile.
 */
class DriveCastResumeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { widgetId ->
            updateSingleWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {
        const val EXTRA_AUTO_RESUME_LAST = "com.example.widget.EXTRA_AUTO_RESUME_LAST"

        fun refreshAllWidgets(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val component = ComponentName(appContext, DriveCastResumeWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            ids.forEach { id -> updateSingleWidget(appContext, manager, id) }
        }

        private fun updateSingleWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            val snapshot = DriveCastPreferences(context).readWidgetSnapshot()
            val views = RemoteViews(context.packageName, R.layout.widget_drivecast_resume)

            views.setTextViewText(R.id.widget_video_title, snapshot.title)
            val subtitle = if (snapshot.durationMs > 0L) {
                "${formatDuration(snapshot.positionMs)} / ${formatDuration(snapshot.durationMs)} • ${snapshot.activeEqName}"
            } else {
                "MG Windsor Pro EV • ${snapshot.activeEqName}"
            }
            views.setTextViewText(R.id.widget_video_subtitle, subtitle)

            val progress = if (snapshot.durationMs > 0L) {
                ((snapshot.positionMs * 100L) / snapshot.durationMs).toInt().coerceIn(0, 100)
            } else {
                0
            }
            views.setProgressBar(R.id.widget_progress_bar, 100, progress, false)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_AUTO_RESUME_LAST, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                widgetId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_resume_button, pendingIntent)

            manager.updateAppWidget(widgetId, views)
        }
    }
}

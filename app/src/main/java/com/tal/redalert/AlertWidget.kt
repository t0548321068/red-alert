package com.tal.redalert

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

/** ווידג'ט למסך הבית: שעה, מצב (מוגן/כבוי) והתראה אחרונה */
class AlertWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(c)) }
    }

    companion object {
        /** לקרוא אחרי כל התראה או שינוי מצב */
        fun updateAll(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            val ids = mgr.getAppWidgetIds(ComponentName(c, AlertWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { mgr.updateAppWidget(it, build(c)) }
        }

        private fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_alert)
            val on = Prefs.enabled(c)
            v.setTextViewText(R.id.widget_status, if (on) "● מוגן" else "○ כבוי")
            v.setTextColor(R.id.widget_status, Color.parseColor(if (on) "#30D158" else "#8E8E93"))

            val last = Prefs.history(c).firstOrNull()
            if (last == null) {
                v.setTextViewText(R.id.widget_last, "אין התראות עדיין")
                v.setTextColor(R.id.widget_last, Color.parseColor("#8E8E93"))
            } else {
                val color = when (last.level) {
                    AlertService.LEVEL_PRE -> "#FF9F0A"
                    AlertService.LEVEL_END -> "#30D158"
                    else -> "#FF3B30"
                }
                val t = if (last.ts > 0) TimeFormat.time(c, last.ts) else last.time
                v.setTextViewText(R.id.widget_last, "$t · ${last.title}\n${last.body}")
                v.setTextColor(R.id.widget_last, Color.parseColor(color))
            }

            val open = PendingIntent.getActivity(c, 10, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.widget_root, open)
            return v
        }
    }
}

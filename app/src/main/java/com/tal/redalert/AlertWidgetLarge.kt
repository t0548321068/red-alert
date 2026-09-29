package com.tal.redalert

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/** ווידג'ט גדול: שעה עם שניות, תאריך, מצב, ספירה לאחור בזמן התראה ו-3 התראות אחרונות */
class AlertWidgetLarge : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(c)) }
    }

    companion object {
        fun updateAll(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            val ids = mgr.getAppWidgetIds(ComponentName(c, AlertWidgetLarge::class.java))
            if (ids.isNotEmpty()) ids.forEach { mgr.updateAppWidget(it, build(c)) }
        }

        private fun color(level: Int) = Color.parseColor(when (level) {
            AlertService.LEVEL_PRE -> "#FF9F0A"
            AlertService.LEVEL_END -> "#30D158"
            else -> "#FF3B30"
        })

        private fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_large)
            val on = Prefs.enabled(c)
            v.setTextViewText(R.id.wl_status, if (on) "● מוגן" else "○ כבוי")
            v.setTextColor(R.id.wl_status, Color.parseColor(if (on) "#30D158" else "#8E8E93"))

            // ספירה לאחור - רק בזמן שנשאר זמן להגעה למרחב המוגן
            val left = Prefs.countdownUntil(c) - System.currentTimeMillis()
            if (left > 0) {
                v.setViewVisibility(R.id.wl_cd_box, View.VISIBLE)
                v.setTextViewText(R.id.wl_cd_title, Prefs.countdownTitle(c))
                v.setChronometer(R.id.wl_cd, SystemClock.elapsedRealtime() + left, null, true)
                v.setChronometerCountDown(R.id.wl_cd, true)
            } else {
                v.setChronometer(R.id.wl_cd, SystemClock.elapsedRealtime(), null, false)
                v.setViewVisibility(R.id.wl_cd_box, View.GONE)
            }

            val hist = Prefs.history(c).take(3)
            val rows = listOf(R.id.wl_h1, R.id.wl_h2, R.id.wl_h3)
            if (hist.isEmpty()) {
                v.setTextViewText(R.id.wl_h1, "אין התראות עדיין")
                v.setTextColor(R.id.wl_h1, Color.parseColor("#8E8E93"))
            }
            rows.forEachIndexed { i, id ->
                val e = hist.getOrNull(i)
                if (e == null) { if (i > 0) v.setViewVisibility(id, View.GONE); return@forEachIndexed }
                val t = if (e.ts > 0) TimeFormat.time(c, e.ts) else e.time
                v.setViewVisibility(id, View.VISIBLE)
                v.setTextViewText(id, "$t · ${e.title}" + if (e.body.isNotEmpty()) " – ${e.body}" else "")
                v.setTextColor(id, color(e.level))
            }

            val open = PendingIntent.getActivity(c, 11, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.wl_root, open)
            return v
        }
    }
}

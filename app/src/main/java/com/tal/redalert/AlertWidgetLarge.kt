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

/** ווידג'ט גדול: שעה עם שניות, תאריך, מצב, וכרטיס ההתרעה הפעילה (בלי רשימת התראות אחרונות) */
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

        private fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_large)
            val on = Prefs.enabled(c)
            v.setTextViewText(R.id.wl_status, if (on) "● מוגן" else "○ כבוי")
            v.setTextColor(R.id.wl_status, Color.parseColor(if (on) "#30D158" else "#8E8E93"))

            // התרעה פעילה: כרטיס כמו בכתום - אייקון, כותרת, שורה מתחתיה, מיקום.
            // באדום: ירי - ספירה לכניסה ואחריה "נשארים במרחב המוגן עד הודעה". סוג בלי זמן הגעה - ההנחיה שלו (הכל באדום)
            val act = AlertWidget.activeAny(c)
            AlertWidget.setAlertBox(v, act, R.id.wl_pre_box, R.id.wl_pre_icon, R.id.wl_pre_title, R.id.wl_pre_note, R.id.wl_pre_area)
            val left = Prefs.countdownUntil(c) - System.currentTimeMillis()
            val actTitle = act?.optString("title") ?: ""
            if (act != null && act.optInt("level") == AlertService.LEVEL_ALERT && !AlertUi.noShelter(actTitle) && left > 0) {
                v.setTextViewText(R.id.wl_pre_note, AlertUi.instruction(actTitle))
                v.setViewVisibility(R.id.wl_pre_cd, View.VISIBLE)
                v.setChronometer(R.id.wl_pre_cd, SystemClock.elapsedRealtime() + left, null, true)
                v.setChronometerCountDown(R.id.wl_pre_cd, true)
            } else {
                if (act != null && act.optInt("level") == AlertService.LEVEL_ALERT)
                    v.setTextViewText(R.id.wl_pre_note, if (AlertUi.noShelter(actTitle)) AlertUi.instruction(actTitle) else AlertUi.STAY_TEXT)
                v.setChronometer(R.id.wl_pre_cd, SystemClock.elapsedRealtime(), null, false)
                v.setViewVisibility(R.id.wl_pre_cd, View.GONE)
            }

            val open = PendingIntent.getActivity(c, 11, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.wl_root, open)
            return v
        }
    }
}

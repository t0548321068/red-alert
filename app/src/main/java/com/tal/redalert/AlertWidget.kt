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
            AlertWidgetLarge.updateAll(c)
        }

        /** ההתרעה האחרונה מכל סוג - נשארת עד שמגיעה התרעה אחרת (בלי זמן תפוגה) */
        fun activeAny(c: Context): org.json.JSONObject? = Prefs.activeAlert(c)

        /** כרטיס צבעוני לפי סוג ההתרעה: אייקון · כותרת · שורה מתחתיה · מיקום */
        fun setAlertBox(v: RemoteViews, j: org.json.JSONObject?, box: Int, icon: Int, title: Int, note: Int, area: Int) {
            if (j == null) { v.setViewVisibility(box, android.view.View.GONE); return }
            val level = j.optInt("level")
            val (bg, ic) = when (level) {
                AlertService.LEVEL_PRE -> R.drawable.widget_pre_bg to R.drawable.ic_list_pre
                AlertService.LEVEL_END -> R.drawable.widget_end_bg to R.drawable.ic_list_end
                else -> R.drawable.widget_alert_bg to R.drawable.ic_list_siren
            }
            val (t1, t2) = AlertUi.head(j.optString("title"), level)
            v.setViewVisibility(box, android.view.View.VISIBLE)
            v.setInt(box, "setBackgroundResource", bg)
            v.setImageViewResource(icon, ic)
            v.setTextViewText(title, t1)
            v.setTextViewText(note, t2)
            val body = j.optString("body")
            v.setTextViewText(area, body)
            v.setViewVisibility(area, if (body.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
        }

        private fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_alert)
            val on = Prefs.enabled(c)
            v.setTextViewText(R.id.widget_status, if (on) "● מוגן" else "○ כבוי")
            v.setTextColor(R.id.widget_status, Color.parseColor(if (on) "#30D158" else "#8E8E93"))

            // התרעה פעילה (אדום / כתום / ירוק): כרטיס צבעוני במקום שורת ההתראה האחרונה
            val act = activeAny(c)
            setAlertBox(v, act, R.id.widget_pre_box, R.id.widget_pre_icon, R.id.widget_pre_title, R.id.widget_pre_note, R.id.widget_pre_area)
            v.setViewVisibility(R.id.widget_last, if (act != null) android.view.View.GONE else android.view.View.VISIBLE)

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

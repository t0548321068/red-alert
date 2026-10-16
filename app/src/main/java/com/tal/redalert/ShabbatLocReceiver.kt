package com.tal.redalert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * מצב שבת אוטומטי: שעה לפני כל כניסת שבת/חג האפליקציה מתעוררת (גם כשהטלפון ישן),
 * לוקחת מיקום עדכני - והכניסה והיציאה מחושבות לפיו. אחר כך מתזמנת את השבת הבאה.
 */
class ShabbatLocReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (Prefs.shabbatMode(c) != Shabbat.AUTO) return
        val pending = goAsync()
        val app = c.applicationContext
        var finished = false
        fun finish() { if (!finished) { finished = true; schedule(app); try { pending.finish() } catch (_: Exception) { } } }
        try {
            // קודם מהרשת (מהיר, בלי סוללה); בלי - GPS
            Weather.networkLocation(app) { net ->
                if (net != null) finish()
                else try { Weather.freshLocation(app) { finish() } } catch (_: Exception) { finish() }
            }
        } catch (_: Exception) { finish() }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 50_000)
    }

    companion object {
        private const val BEFORE_MS = 60 * 60_000L   // שעה לפני הכניסה

        /** מתזמן את ההתעוררות לפני השבת/החג הקרובים (רק במצב אוטומטי) */
        fun schedule(c: Context) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(c, 31, Intent(c, ShabbatLocReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            am.cancel(pi)
            if (Prefs.shabbatMode(c) != Shabbat.AUTO) return
            val now = System.currentTimeMillis()
            var w = try { Shabbat.nextWindow(c, now) } catch (_: Exception) { null } ?: return
            // השבת הנוכחית כבר התחילה או שפחות משעה לכניסה - מתזמנים לבאה
            if (w.first - BEFORE_MS <= now) w = try { Shabbat.nextWindow(c, w.second + 60_000) } catch (_: Exception) { null } ?: return
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, w.first - BEFORE_MS, pi) } catch (_: Exception) { }
        }
    }
}

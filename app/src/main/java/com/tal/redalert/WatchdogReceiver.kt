package com.tal.redalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** שומר: מתעורר כל ~5 דקות (גם כשהטלפון ישן) ובודק שההאזנה וחיבור צופר חיים */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val s = AlertService.instance
        when {
            // מחוץ ל-main thread: הבדיקה מחכה למנעול של טיפול בהתרעה, ואסור לחסום את הממשק
            s != null -> { val done = goAsync(); Thread { try { s.onWatchdog() } catch (_: Exception) { } finally { done.finish() } }.start() }
            Prefs.enabled(c) -> try { AlertService.start(c) } catch (_: Exception) { AlertService.scheduleWatchdog(c) }
        }
    }
}

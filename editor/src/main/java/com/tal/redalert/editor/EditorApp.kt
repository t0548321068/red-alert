package com.tal.redalert.editor

import android.app.Activity
import android.os.Bundle
import com.tal.redalert.App
import com.tal.redalert.MainActivity
import com.tal.redalert.Prefs

/**
 * צופר חירום 2 (עריכת צופר): צופר חירום עצמה (App - אותה הפעלה בדיוק), עם העיצוב מחדש.
 * בנוסף: העדכון העצמי של צופר חירום 2, והגרסה שלה בתוך המסכים
 */
class EditorApp : App() {
    override fun onCreate() {
        super.onCreate()
        appVersion()
        Prefs.setAutoUpdate(this, false)   // העדכון של צופר חירום לא שייך לכאן - צופר חירום 2 מתעדכנת לבד
        // עדכון: מחובר כל הזמן ומתקין ברגע שגרסה מתפרסמת - בלי לפתוח (והבדיקה התקופתית - גיבוי)
        UpdateListener.start(this)
        UpdateJob.schedule(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { if (a is MainActivity) { Update.maybeCheck(a); askBattery(a) } }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** פעם אחת: לבקש שהטלפון לא יעצור את צופר חירום 2 ברקע - אחרת העדכון האוטומטי לא יגיע */
    private fun askBattery(a: Activity) {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val sp = getSharedPreferences("editor_update", MODE_PRIVATE)
        if (pm.isIgnoringBatteryOptimizations(packageName) || sp.getBoolean("batteryAsked", false)) return
        sp.edit().putBoolean("batteryAsked", true).apply()
        try { a.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) { }
    }

    /** בתוך המסכים (שורת החיוויים, הגדרות): הגרסה של צופר חירום 2 */
    private fun appVersion() = try {
        com.tal.redalert.Updater::class.java.getDeclaredField("curVer").apply { isAccessible = true }
            .set(com.tal.redalert.Updater, getString(com.tal.redalert.R.string.app_version))
    } catch (_: Exception) { }
}

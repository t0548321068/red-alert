package com.tal.redalert.editor

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.tal.redalert.DataFiles
import com.tal.redalert.MainActivity
import com.tal.redalert.Prefs

/**
 * עריכת צופר: האפליקציה עצמה (אותו קוד), ומעליה שכבת העריכה.
 * כאן: נתוני דוגמה בפתיחה הראשונה, בלי חלונות פתיחה (הבהרה / מה חדש / מסך נעילה), ושמירת קריסה לשליחה
 */
class EditorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { Store.saveCrash(this, android.util.Log.getStackTraceString(e)) } catch (_: Exception) { }
            prev?.uncaughtException(t, e)
        }
        DataFiles.init(this)
        seed()
        MainActivity.lockAsked = true
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { Editor.scanSoon(); if (a is MainActivity) Update.maybeCheck(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        Editor.start(this)
    }

    /** בכל פתיחה: בלי הבהרה ובלי "מה חדש". בפעם הראשונה: הגדרות כמו בטלפון של טל */
    private fun seed() {
        val pi = packageManager.getPackageInfo(packageName, 0)
        Prefs.setDisclaimerFor(this, pi.firstInstallTime)
        Prefs.setSeenVersion(this, pi.versionName ?: "")
        Prefs.setAutoUpdate(this, false)   // עדכון של צופר חירום לא שייך לכאן
        val sp = getSharedPreferences("editor_seed", MODE_PRIVATE)
        if (sp.getBoolean("done", false)) return
        Prefs.setEnabled(this, true)
        Prefs.setNearMe(this, true)
        Prefs.setNearbyAreas(this, listOf("ירושלים - צפון"))
        Prefs.setGreetingName(this, "טל")
        Prefs.setShowGreeting(this, true)
        sp.edit().putBoolean("done", true).apply()
    }
}

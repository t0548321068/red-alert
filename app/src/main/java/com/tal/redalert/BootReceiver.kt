package com.tal.redalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // אחרי הדלקת הטלפון, ואחרי כל עדכון של האפליקציה (אנדרואיד עוצר אותה בזמן העדכון)
        val restart = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (restart && Prefs.enabled(context)) {
            AlertService.start(context)
        } else if (restart) AlertService.showOff(context)   // כבוי - תזכורת שלא מוגן
    }
}

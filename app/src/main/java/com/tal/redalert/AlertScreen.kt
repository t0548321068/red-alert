package com.tal.redalert

import com.tal.redalert.AlertService.Companion.CH_ALERT
import com.tal.redalert.AlertService.Companion.CH_COUNT
import com.tal.redalert.AlertService.Companion.CH_INFO
import com.tal.redalert.AlertService.Companion.CH_NO_SOUND
import com.tal.redalert.AlertService.Companion.CH_NO_VIBE
import com.tal.redalert.AlertService.Companion.CH_QUIET
import com.tal.redalert.AlertService.Companion.CH_SERVICE
import com.tal.redalert.AlertService.Companion.CH_SILENT
import com.tal.redalert.AlertService.Companion.CH_STAY
import com.tal.redalert.AlertService.Companion.CH_UPDATE
import com.tal.redalert.AlertService.Companion.CH_WATCH
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// שירות ההתרעות: הקפצת מסך ההתרעה, הערת המסך וערוצי ההתראות

/** הרשאת "הצגה מעל אפליקציות אחרות" (canDrawOverlays לפעמים טועה - בודקים גם ב-AppOps) */
internal fun AlertService.overlayOk(): Boolean {
    if (android.provider.Settings.canDrawOverlays(this)) return true
    return try {
        val ops = getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }
}

/**
 * פתיחת מסך ההתרעה מעל אפליקציה אחרת.
 * מאנדרואיד 15 מותר לפתוח מסך מהרקע רק כשיש לאפליקציה חלון צף גלוי -
 * אז מוסיפים חלון שקוף בגודל פיקסל, פותחים את המסך, ומסירים אותו אחרי כמה שניות.
 */
internal fun AlertService.launchOver(i: Intent) = main.post {
    val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
    var v: android.view.View? = null
    if (overlayOk()) try {
        v = android.view.View(this)
        wm.addView(v, android.view.WindowManager.LayoutParams(1, 1,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START })
    } catch (_: Exception) { v = null }
    try { startActivity(i) } catch (_: Exception) { }
    main.postDelayed({ try { v?.let { wm.removeView(it) } } catch (_: Exception) { } }, 4000)
}

internal fun AlertService.wakeScreen() {
    val pm = getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    if (pm.isInteractive) return
    try {
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "redalert:screen").acquire(20_000)
    } catch (_: Exception) { }
}

internal fun AlertService.createChannels() {
    val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
        NotificationChannel(CH_COUNT, "ספירה לאחור למרחב המוגן", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    nm.createNotificationChannel(
        NotificationChannel(CH_SERVICE, "שירות רקע", NotificationManager.IMPORTANCE_MIN))
    nm.createNotificationChannel(
        NotificationChannel(CH_ALERT, "התראות צופר חירום", NotificationManager.IMPORTANCE_HIGH).apply {
            // צליל ורטט - ברירת המחדל של הטלפון
            enableVibration(true)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    nm.createNotificationChannel(
        NotificationChannel(CH_UPDATE, "עדכוני גרסה", NotificationManager.IMPORTANCE_HIGH).apply {
            // בינתיים: צליל ההתראות הרגיל של הטלפון (לא המנגינה של האפליקציה)
        })
    nm.deleteNotificationChannel("updates_v2")
    nm.deleteNotificationChannel("updates_v3")
    nm.deleteNotificationChannel("watchdog_v2")
    nm.deleteNotificationChannel("stay")
    nm.deleteNotificationChannel("alerts_v2")
    nm.deleteNotificationChannel("info")
    nm.deleteNotificationChannel("updates_v4")
    nm.deleteNotificationChannel("watchdog_v3")
    nm.deleteNotificationChannel("stay_v2")
    nm.deleteNotificationChannel("stay_v3")
    nm.deleteNotificationChannel("watchdog")
    nm.deleteNotificationChannel("updates")
    nm.deleteNotificationChannel("alerts")
    nm.createNotificationChannel(
        NotificationChannel(CH_QUIET, "שעות שקט", NotificationManager.IMPORTANCE_LOW))
    nm.createNotificationChannel(
        NotificationChannel(CH_WATCH, "האפליקציה לא מוגנת", NotificationManager.IMPORTANCE_HIGH).apply {
            // בינתיים: צליל ההתראות הרגיל של הטלפון (לא המנגינה של האפליקציה)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    nm.createNotificationChannel(
        NotificationChannel(CH_STAY, "נשארים במרחב המוגן", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    nm.createNotificationChannel(
        NotificationChannel(CH_NO_SOUND, "התראות – רטט בלי צליל", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(true)
            setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    nm.createNotificationChannel(
        NotificationChannel(CH_NO_VIBE, "התראות – צליל בלי רטט", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(false); vibrationPattern = longArrayOf(0)
            setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    nm.createNotificationChannel(
        NotificationChannel(CH_SILENT, "התראות – בלי צליל ורטט", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); vibrationPattern = longArrayOf(0)
            setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    nm.createNotificationChannel(
        NotificationChannel(CH_INFO, "סיום אירוע", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
}

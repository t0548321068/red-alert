package com.tal.redalert

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tal.redalert.MainActivity.C
import com.tal.redalert.MainActivity.Row
import com.tal.redalert.MainActivity.Perm
import com.tal.redalert.MainActivity.SameHeight

// הרשאות והגדרות טלפון: בקשות, עמוד ההרשאות, מסך נעילה, מיקום ויישובים בקרבתי
// (חלק מהמסך הראשי - MainActivity; המצב עצמו נשמר שם)

/** הרשאות: שורה אחת שפותחת מסך עם כל ההרשאות והמצב של כל אחת */
internal fun MainActivity.phoneRows(): List<Row> = listOf(
    Row("🛡", "הרשאות", {
        val bad = permissionItems().count { !it.ok }
        if (bad == 0) "הכל תקין ✓" else "$bad דורש טיפול"
    }) { showPermissions() }
)

internal fun MainActivity.permissionItems(): List<Perm> {
    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    return listOf(
        Perm("📱", "מסך מלא בהתראה", when {
                !overlayAllowed() -> "חסר: הצגה מעל אפליקציות אחרות"
                !fullScreenIntentAllowed() -> "חסר: התראות במסך מלא"
                else -> "התרעה נפתחת על כל המסך גם כשהטלפון בשימוש"
            }, fullScreenOk()) { openFullScreenSettings() },
        Perm("🔋", "חיסכון בסוללה", "האפליקציה לא נעצרת ברקע",
            pm.isIgnoringBatteryOptimizations(packageName)) { openBatterySettings() },
        Perm("🔔", "התראות בטלפון", "התראות האפליקציה מופעלות",
            nm.areNotificationsEnabled()) { openNotificationSettings() },
        Perm("🔕", "נא לא להפריע", "מאפשר לעקוף נא לא להפריע בזמן התרעה",
            dndAccess()) { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
    )
}

/** מסך הרשאות: כל הרשאה עם מצב, לחיצה פותחת את ההגדרה בטלפון */
internal fun MainActivity.showPermissions() = ouiPage("הרשאות") { body, _ ->
    val card = ouiCard()
    permissionItems().forEachIndexed { i, p ->
        if (i > 0) ouiDivider(card)
        ouiRow(card, p.title, sub = p.note, subColor = C.MUTED,
            end = if (p.ok) "תקין" else "דורש טיפול", endColor = if (p.ok) C.GREEN else C.RED) { p.open() }
    }
    ouiAdd(body, card)
    ouiNote(body, "לחיצה על הרשאה פותחת את ההגדרה בטלפון")
}

// ---- חיווים ----

@SuppressLint("BatteryLife")
internal fun MainActivity.askPermissions() {
    if (Build.VERSION.SDK_INT >= 33) {
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(packageName)) openBatterySettings()
}

@SuppressLint("BatteryLife")
/**
 * סוללה: אם עוד לא מוחרגת - בקשת המערכת. אם כבר מוחרגת (או שהבקשה לא נפתחת, כמו בחלק ממכשירי סמסונג)
 * - פרטי האפליקציה, שם "סוללה" ← "ללא הגבלה". כך לחיצה תמיד פותחת משהו
 */
internal fun MainActivity.openBatterySettings() {
    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")))
            return
        } catch (_: Exception) { }
    }
    openAppDetails()
}

internal fun MainActivity.openAppDetails() {
    try {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
    } catch (_: Exception) { }
}

internal fun MainActivity.openNotificationSettings() {
    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}

/** אנדרואיד 14+: בלי ההרשאה הזו ההתראה לא נדלקת על מסך נעילה */
internal fun MainActivity.askLockScreen() {
    if (lockAsked || Build.VERSION.SDK_INT < 34 || !Prefs.enabled(this)) return
    // עם "הצגה מעל אפליקציות" המסך נדלק ומוצג גם בלי ההרשאה הזו - לא מציקים
    if (Settings.canDrawOverlays(this)) return
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.canUseFullScreenIntent()) return
    lockAsked = true
    AppSheet.show(this, "התראה על מסך נעילה",
        "כדי שההתראה תידלק מיד כשהמסך כבוי – צריך לאשר \"התראות במסך מלא\".", "אישור", {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.parse("package:$packageName")))
        }, cancel = "לא עכשיו")
}

/**
 * "הצגה מעל אפליקציות אחרות". canDrawOverlays לפעמים מחזיר "לא" גם כשמופעל (במיוחד מיד אחרי
 * שחוזרים מההגדרות) - לכן בודקים גם ישירות בהרשאות המערכת (AppOps)
 */
internal fun MainActivity.overlayAllowed(): Boolean {
    if (Settings.canDrawOverlays(this)) return true
    return try {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }
}

/** הרשאת התראה במסך מלא (אנדרואיד 14 ומעלה) */
internal fun MainActivity.fullScreenIntentAllowed(): Boolean =
    Build.VERSION.SDK_INT < 34 || (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()

/** מסך מלא תקין רק כששתי ההרשאות מופעלות */
internal fun MainActivity.fullScreenOk() = overlayAllowed() && fullScreenIntentAllowed()

internal fun MainActivity.openFullScreenSettings() {
    // קודם מה שחסר: הצגה מעל אפליקציות, ואז התראה במסך מלא.
    // אם הכל מופעל - חוזרים למסך "הצגה מעל אפליקציות" (ולא להגדרות ההתראות)
    if (!overlayAllowed() || fullScreenIntentAllowed()) {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(Uri.parse("package:$packageName")))
        return
    }
    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
        .setData(Uri.parse("package:$packageName")))
}

/** הקראה: הפעלה/כיבוי ודוגמה */
internal fun MainActivity.dndAccess() =
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted

/** התרעות לפי מיקום: צריך מיקום מדויק. מיקום ברקע נקרא דרך השירות */
internal fun MainActivity.toggleNearMe() {
    val on = !Prefs.nearMe(this)
    if (on && !hasPrecise()) {
        requestPermissions(arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION), 3)
        return
    }
    Prefs.setNearMe(this, on)
    if (on) {
        // זיהוי ראשון מיד, בלי לחכות לשירות
        Weather.freshLocation(this) { loc ->
            if (loc != null) Thread {
                val list = AreaData.nearby(this, loc.latitude, loc.longitude)
                Prefs.setNearbyAreas(this, list)
                runOnUiThread { refresh() }
            }.start()
        }
        // השירות צריך לעלות מחדש כדי לקבל גישה למיקום ברקע
        if (Prefs.enabled(this)) { stopService(Intent(this, AlertService::class.java)); AlertService.start(this, manual = true) }
    }
    refresh()
}

/** בכל פתיחה - מיקום עדכני ל"התרעות לפי מיקום" (בנוסף לעדכון של השירות כל 2 דקות) */
internal fun MainActivity.refreshNearby() {
    if (!Prefs.nearMe(this) || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED) return
    fun use(loc: android.location.Location) = Thread {
        try {
            Prefs.addVisits(this, AreaData.areasAt(this, loc.latitude, loc.longitude, 0.0), System.currentTimeMillis())
            val list = AreaData.nearby(this, loc.latitude, loc.longitude)
            if (list.isNotEmpty()) Prefs.setNearbyAreas(this, list)
        } catch (_: Exception) { }
    }.start()
    // קודם מיקום מהרשת (אנטנות / Wi-Fi - שנייה-שתיים): אם זזת למקום אחר, האזור מתעדכן מיד.
    // אחר כך המיקום המדויק (GPS, עד חצי דקה) מדייק אותו
    var gpsDone = false
    Weather.networkLocation(this) { net ->
        if (!gpsDone && net != null && net.accuracy < 2000f) use(Weather.chooseLocation(net))
    }
    Weather.freshLocation(this) { got ->
        gpsDone = true
        got?.let { keepAccuracy(it) }
        val loc = got?.let { Weather.chooseLocation(it) }   // מיקום גס לא דורס מיקום מדויק
        if (loc != null) use(loc)
    }
}

package com.tal.redalert
import com.tal.redalert.MainActivity.C
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

// המסך הראשי: מזג אוויר ודיוק המיקום שמוצג

/** מתעדכן כל 15 דקות, או מיד כשמכריחים (לחיצה על השורה) */
internal fun MainActivity.updateWeather(force: Boolean = false, manual: Boolean = false) {
    if (!Prefs.showWeather(this)) { weatherLine.visibility = View.GONE; return }
    weatherLine.visibility = View.VISIBLE
    if (!hasLocationPerm()) {
        weatherLine.text = "📍 הצג מזג אוויר לפי מיקום"
        weatherLine.setTextColor(C.BLUE)
        return
    }
    if (!force && System.currentTimeMillis() - weatherAt < Prefs.weatherMinutes(this) * 60 * 1000L) return
    weatherAt = System.currentTimeMillis()
    // כשכבר מוצג מזג אוויר - נשאר עד שמגיע החדש (בלי להבהב "מאתר מיקום")
    // בלחיצה על חיווי המיקום - רואים שהוא מתעדכן
    if (!weatherShown || manual) {
        weatherLine.text = "מאתר מיקום מדויק…"
        weatherLine.setTextColor(C.MUTED)
    }
    // קודם מיקום מהרשת (שנייה-שתיים) - מזג האוויר מופיע מיד; אחר כך ה-GPS המדויק מעדכן את שם המקום
    var shown: android.location.Location? = null
    var gpsDone = false
    Weather.networkLocation(this) { net ->
        if (!gpsDone && net != null && net.accuracy < 2000f) { shown = net; loadWeather(net) }
    }
    Weather.freshLocation(this) { loc ->
        gpsDone = true
        if (loc == null) {
            if (shown == null) {
                weatherRetrySoon()
                if (!weatherShown || manual) {   // מזג אוויר קודם נשאר על המסך (חוץ מלחיצה ידנית)
                    weatherLine.text = "📍 לא נמצא מיקום – לחץ כדי לנסות שוב"
                    weatherLine.setTextColor(C.MUTED)
                }
            }
            return@freshLocation
        }
        // המיקום מהרשת גס (מאות מטרים) - שם השכונה לפיו יכול להיות של השכונה הסמוכה.
        // כשמגיע המיקום המדויק - טוענים שוב (גם השם), אלא אם המיקום הראשון כבר היה מדויק וקרוב
        val first = shown
        keepAccuracy(loc, manual)
        if (manual || first == null || first.accuracy > 50f || loc.distanceTo(first) > 300f) loadWeather(loc)
    }
}

/**
 * חיווי הדיוק יציב: מציג את הדיוק הטוב ביותר שנמדד לאחרונה (נשמר גם אחרי סגירה ופתיחה).
 * מתעדכן רק כשה-GPS מוצא דיוק טוב יותר, כשזזת (מעל 200 מטר), או כשהמדידה האחרונה בת יותר מחצי שעה
 */
internal fun MainActivity.keepAccuracy(loc: android.location.Location, manual: Boolean = false) {
    val m = Math.round(loc.accuracy)
    val (prev, at, pos) = Prefs.lastAcc(this)
    val moved = FloatArray(1).also { android.location.Location.distanceBetween(pos.first, pos.second, loc.latitude, loc.longitude, it) }[0] > 200f
    val stale = System.currentTimeMillis() - at > 30 * 60_000L
    if (manual || prev <= 0 || m <= prev || moved || stale) {   // בלחיצה ידנית - המדידה הנוכחית
        Prefs.setLastAcc(this, m, loc.latitude, loc.longitude)
        weatherAccuracy = m
    }
}

internal fun MainActivity.shownAccuracy(): Int {
    if (weatherAccuracy > 0) return weatherAccuracy
    val (m, _, _) = Prefs.lastAcc(this)
    return if (m > 0) m else initialAcc
}

/** בכל פתיחה: מספר ראשוני מיד, ומדידה מדויקת ברקע שמעדכנת אותו (אם לא נמדד בדקה האחרונה) */
internal fun MainActivity.refreshAccuracy() {
    if (!hasLocationPerm() || !locationOn()) return
    if (initialAcc <= 0) initialAcc = try { Weather.lastLocation(this)?.accuracy?.let { Math.round(it) } } catch (_: Exception) { null } ?: -1
    if (System.currentTimeMillis() - Prefs.lastAcc(this).second < 60_000L) return
    Weather.freshLocation(this) { loc -> if (loc != null) keepAccuracy(loc) }
}

/** נכשל - ניסיון חוזר בעוד דקה (לא בכל שנייה) */
internal fun MainActivity.weatherRetrySoon() { weatherAt = System.currentTimeMillis() - Prefs.weatherMinutes(this) * 60_000L + 60_000L }

internal fun MainActivity.loadWeather(loc: android.location.Location) = Thread {
    val w = try { Weather.fetch(this, loc) } catch (_: Exception) { null }
    runOnUiThread {
        if (w == null) { weatherRetrySoon(); return@runOnUiThread }
        val (icon, _) = Weather.describe(w.code, w.isDay)
        val hint = if (hasPrecise()) "" else " (לחץ למיקום מדויק)"
        weatherLine.text = listOf("$icon ${w.temp}°", w.place)
            .filter { it.isNotBlank() }.joinToString(" · ") + hint
        weatherLine.setTextColor(C.SOFT)
        weatherShown = true

    }
}.start()

internal fun MainActivity.onWeatherClick() {
    if (hasPrecise()) updateWeather(force = true)
    else requestPermissions(arrayOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION), 2)
}

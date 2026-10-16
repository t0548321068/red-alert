package com.tal.redalert

import android.content.Context
import android.icu.util.HebrewCalendar
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

/**
 * מצב שבת: כבוי / דלוק / אוטומטי (שבת וחג לפי המיקום).
 * בזמן מצב שבת כל התראה מוצגת דקה אחת ונסגרת לבד, עם הקראה קולית.
 */
object Shabbat {
    const val OFF = 0
    const val ON = 1
    const val AUTO = 2

    // זמנים לפי שיטת אתר ישיבה (yeshiva.org.il/calendar/timeprinciples):
    // כניסה - מספר דקות לפני השקיעה לפי מנהג המקום; יציאה - צאת הכוכבים (השמש 8.5° מתחת לאופק). מעוגל לדקה
    private class Custom(val lat: Double, val lon: Double, val km: Float, val min: Long)
    private val CUSTOMS = listOf(
        Custom(31.778, 35.235, 12f, 40),   // ירושלים
        Custom(32.087, 34.887, 5f, 40),    // פתח תקווה
        Custom(32.180, 34.871, 4f, 20),    // רעננה
        Custom(32.070, 34.790, 12f, 21),   // תל אביב וגוש דן
        Custom(32.965, 35.496, 6f, 30),    // צפת
        Custom(32.790, 35.530, 6f, 30),    // טבריה
        Custom(32.800, 35.000, 12f, 30),   // חיפה
        Custom(31.250, 34.790, 10f, 22),   // באר שבע
        Custom(31.800, 34.650, 7f, 22),    // אשדוד
    )
    private const val CANDLE_DEFAULT = 30L   // מקום בלי מנהג ידוע - 30 דקות (משנה ברורה)

    private fun candleMin(lat: Double, lon: Double): Long {
        val r = FloatArray(1)
        for (c in CUSTOMS) {
            android.location.Location.distanceBetween(lat, lon, c.lat, c.lon, r)
            if (r[0] < c.km * 1000) return c.min
        }
        return CANDLE_DEFAULT
    }
    private fun roundMin(ms: Long) = (ms + 30_000) / 60_000 * 60_000
    private fun candle(prev: Calendar, lat: Double, lon: Double) =
        roundMin(sunset(prev, lat, lon, 90.833) - candleMin(lat, lon) * 60_000)

    fun label(mode: Int) = when (mode) { ON -> "דלוק"; AUTO -> "אוטומטי"; else -> "כבוי" }

    fun active(c: Context, now: Long = System.currentTimeMillis()): Boolean = when (Prefs.shabbatMode(c)) {
        ON -> true
        AUTO -> try { holyNow(c, now) } catch (_: Exception) { false }
        else -> false
    }

    /** מתי מצב השבת האוטומטי נגמר / מתחיל (להצגה) */
    fun endsAt(c: Context, now: Long = System.currentTimeMillis()): Long? {
        val (lat, lon) = where(c)
        val day = cal(now)
        return if (isHoly(day)) tzeit(day, lat, lon) else null
    }

    /** השבת/החג הנוכחי או הקרוב: (כניסה, יציאה) לפי המיקום - כמו שמצב השבת האוטומטי משתמש */
    fun nextWindow(c: Context, now: Long = System.currentTimeMillis()): Pair<Long, Long>? {
        val (lat, lon) = where(c)
        val base = cal(now)
        for (i in -1..60) {
            val day = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            val prev = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
            if (!isHoly(day) || isHoly(prev)) continue          // תחילת רצף של ימים קדושים
            val start = candle(prev, lat, lon)
            var last = day
            while (true) {
                val next = (last.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
                if (isHoly(next)) last = next else break        // חג שצמוד לשבת - יציאה אחת בסוף
            }
            val end = tzeit(last, lat, lon)
            if (end > now) return start to end
        }
        return null
    }

    /** האם יש מיקום שמור (אחרת הזמנים לפי מרכז הארץ) */
    fun hasLocation(c: Context): Boolean = try { Weather.lastLocation(c) != null } catch (_: Exception) { false }

    private fun where(c: Context): Pair<Double, Double> {
        val l = try { Weather.lastLocation(c) } catch (_: Exception) { null }
        return if (l != null) l.latitude to l.longitude else 31.78 to 35.0   // ברירת מחדל: מרכז הארץ
    }

    private fun holyNow(c: Context, now: Long): Boolean {
        val (lat, lon) = where(c)
        val today = cal(now)
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        // היום קדוש ועוד לא יצאו הכוכבים
        if (isHoly(today) && now < tzeit(today, lat, lon)) return true
        // מחר קדוש ועברה כניסת השבת/החג היום
        if (isHoly(tomorrow) && now >= candle(today, lat, lon)) return true
        return false
    }

    private fun cal(ms: Long) = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem")).apply { timeInMillis = ms }

    /** שבת, או יום טוב בארץ (ראש השנה, יום כיפור, סוכות, שמיני עצרת, פסח, שביעי של פסח, שבועות) */
    private fun isHoly(day: Calendar): Boolean {
        if (day.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) return true
        val h = HebrewCalendar(android.icu.util.TimeZone.getTimeZone("Asia/Jerusalem"), android.icu.util.ULocale.getDefault())
        h.timeInMillis = (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 12) }.timeInMillis
        val m = h.get(android.icu.util.Calendar.MONTH); val d = h.get(android.icu.util.Calendar.DAY_OF_MONTH)
        return when (m) {
            HebrewCalendar.TISHRI -> d in setOf(1, 2, 10, 15, 22)
            HebrewCalendar.NISAN -> d == 15 || d == 21
            HebrewCalendar.SIVAN -> d == 6
            else -> false
        }
    }

    private fun tzeit(day: Calendar, lat: Double, lon: Double) =
        roundMin(sunset(day, lat, lon, 98.5))   // צאת הכוכבים: השמש 8.5° מתחת לאופק

    /** שקיעה (או זנית אחר) ביום נתון - אלגוריתם NOAA, מחזיר זמן UTC במילישניות */
    private fun sunset(day: Calendar, lat: Double, lon: Double, zenith: Double): Long {
        val n = day.get(Calendar.DAY_OF_YEAR)
        val lngHour = lon / 15.0
        val t = n + ((18 - lngHour) / 24)
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
        l = (l + 360) % 360
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
        ra = (ra + 360) % 360
        ra += (floor(l / 90) * 90 - floor(ra / 90) * 90)
        ra /= 15
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(Math.toRadians(zenith)) - sinDec * sin(Math.toRadians(lat))) / (cosDec * cos(Math.toRadians(lat)))
        val h = Math.toDegrees(acos(cosH.coerceIn(-1.0, 1.0))) / 15
        val localT = h + ra - 0.06571 * t - 6.622
        val ut = ((localT - lngHour) % 24 + 24) % 24
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
        }
        return utc.timeInMillis + (ut * 3600_000).toLong()
    }
}

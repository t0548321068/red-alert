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

    // זמנים כמו באתר "עתים לבינה" (itimlabina.co.il): לכל יישוב באתר - ההפרש של הדלקת הנרות מהשקיעה
    // בגובה פני הים (כמו שהאפליקציה מחשבת), ושל צאת השבת מצאת הכוכבים 8.5°. ההפרשים כוללים את מנהג המקום,
    // הגובה וההסתרה של ההרים, ונלקחו מהאתר. המיקום - לפי היישוב הקרוב ביותר. מעוגל לדקה הקרובה
    private class C(val lat: Double, val lon: Double, val start: Float, val end: Float)
    private val CITIES = listOf(
        C(32.5023, 34.9219, -31.1f, -1.1f),   // אור עקיבא
        C(32.0292, 34.8484, -30.9f, -0.8f),   // אור יהודה
        C(31.7885, 35.461, -30.8f, -0.5f),   // אלמוג
        C(29.5372, 34.9368, -21.2f, -1.2f),   // אילת
        C(33.0427, 35.8377, -30.9f, -1.1f),   // אלוני הבשן
        C(31.3084, 34.6328, -30.6f, -1.1f),   // אופקים
        C(32.2324, 35.3361, -26.8f, -0.7f),   // אלון מורה
        C(32.0511, 34.9564, -28.5f, -1.3f),   // אלעד
        C(31.6658, 35.1591, -36.1f, -0.8f),   // אפרת
        C(31.7378, 35.188, -35.9f, -0.6f),   // ירושלים
        C(32.0574, 34.7986, -23.1f, -0.9f),   // תל אביב
        C(32.8021, 34.9626, -29.6f, -0.7f),   // חיפה
        C(31.2415, 34.7762, -18.1f, -0.6f),   // באר שבע
        C(31.7966, 34.6616, -21.9f, -0.7f),   // אשדוד
        C(31.6626, 34.5684, -19.5f, -1.1f),   // אשקלון
        C(32.3004, 34.8728, -22.5f, -0.5f),   // נתניה
        C(32.0943, 34.878, -40.8f, -0.6f),   // פתח תקוה
        C(31.9727, 34.8062, -23.2f, -1.0f),   // ראשון לציון
        C(31.8921, 34.8017, -23.3f, -1.0f),   // רחובות
        C(32.0147, 34.7865, -21.2f, -1.0f),   // חולון
        C(32.0887, 34.8342, -22.9f, -0.8f),   // בני ברק
        C(32.0493, 34.8425, -22.9f, -0.8f),   // רמת גן
        C(32.1781, 34.8106, -22.9f, -0.8f),   // הרצליה
        C(32.1799, 34.9227, -20.5f, -1.4f),   // כפר סבא
        C(32.1905, 34.863, -22.7f, -0.6f),   // רעננה
        C(32.1495, 34.8854, -30.7f, -0.5f),   // הוד השרון
        C(31.899, 35.007, -38.4f, -1.2f),   // מודיעין
        C(31.7275, 34.9904, -38.7f, -0.4f),   // בית שמש
        C(31.7014, 35.1162, -36.2f, -0.9f),   // ביתר עילית
        C(31.932, 35.043, -28.3f, -1.1f),   // קרית ספר
        C(32.1035, 35.188, -26.5f, -1.3f),   // אריאל
        C(31.7864, 35.3123, -41.3f, -1.1f),   // מעלה אדומים
        C(31.5341, 35.1236, -36.4f, -1.0f),   // קרית ארבע
        C(32.9691, 35.5006, -26.3f, -0.5f),   // צפת
        C(32.7842, 35.5204, -31.4f, -0.5f),   // טבריה
        C(33.0152, 35.1026, -20.8f, -1.0f),   // נהריה
        C(32.9307, 35.0835, -31.0f, -1.2f),   // עכו
        C(32.9105, 35.285, -28.2f, -0.4f),   // כרמיאל
        C(33.2093, 35.5725, -30.7f, -1.0f),   // קרית שמונה
        C(32.6154, 35.297, -30.5f, -0.5f),   // עפולה
        C(32.7101, 35.3228, -27.3f, -0.4f),   // נוף הגליל
        C(32.4994, 35.5006, -30.8f, -0.8f),   // בית שאן
        C(32.4251, 34.9498, -31.1f, 6.9f),   // חדרה
    )

    private fun nearest(lat: Double, lon: Double): C {
        var best = CITIES[0]; var bd = Double.MAX_VALUE
        val k = cos(Math.toRadians(lat))
        for (c in CITIES) {
            val dy = c.lat - lat; val dx = (c.lon - lon) * k
            val d = dy * dy + dx * dx
            if (d < bd) { bd = d; best = c }
        }
        return best
    }
    private fun roundMin(ms: Long) = (ms + 30_000) / 60_000 * 60_000
    private fun candle(prev: Calendar, lat: Double, lon: Double) =
        roundMin(sunset(prev, lat, lon, 90.833) + (nearest(lat, lon).start * 60_000).toLong())

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
        roundMin(sunset(day, lat, lon, 98.5) + (nearest(lat, lon).end * 60_000).toLong())   // צאת השבת כמו בעתים לבינה

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

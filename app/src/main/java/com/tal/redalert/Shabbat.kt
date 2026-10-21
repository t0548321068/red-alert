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

    // זמנים כמו באתר ישיבה (yeshiva.org.il): לכל אחד מ-165 היישובים באתר - כמה דקות לפני השקיעה
    // (שקיעה בגובה פני הים, כמו שהאפליקציה מחשבת) מדליקים נרות שם. הנתונים נלקחו מלוח השבתות של האתר
    // לשנה שלמה, וההפרש קבוע לכל יישוב (מנהג המקום + הגובה). המיקום - לפי היישוב הקרוב ביותר.
    // יציאה - צאת הכוכבים (השמש 8.5° מתחת לאופק), מעוגל כלפי מעלה לדקה, כמו באתר
    private class C(val lat: Double, val lon: Double, val off: Float)
    private val CITIES = listOf(
        C(32.5023, 34.9219, -20.8f),   // אור עקיבא
        C(32.0292, 34.8484, -20.6f),   // אור יהודה
        C(31.7885, 35.461, -33.3f),   // אלמוג
        C(29.5372, 34.9368, -30.3f),   // אילת
        C(33.0427, 35.8377, -24.8f),   // אלוני הבשן
        C(31.3084, 34.6328, -28.2f),   // אופקים
        C(32.2324, 35.3361, -25.6f),   // אלון מורה
        C(32.0511, 34.9564, -30.3f),   // אלעד
        C(31.6658, 35.1591, -16.3f),   // אפרת
        C(31.7966, 34.6616, -22.2f),   // אשדוד
        C(32.1035, 35.188, -30.2f),   // אריאל
        C(31.2415, 34.7762, -19.6f),   // באר שבע
        C(31.6626, 34.5684, -30.3f),   // אשקלון
        C(31.9443, 34.8448, -30.3f),   // באר יעקב
        C(31.9405, 35.2219, -35.3f),   // בית אל
        C(31.7926, 34.812, -29f),   // בית חלקיה
        C(31.8224, 35.5042, -33.9f),   // בית חגלה
        C(32.4994, 35.5006, -32.5f),   // בית שאן
        C(31.7275, 34.9904, -30.4f),   // בית שמש
        C(31.7014, 35.1162, -25.7f),   // ביתר עילית
        C(32.0887, 34.8342, -20.2f),   // בני ברק
        C(31.1453, 34.3179, -28.8f),   // בני נצרים
        C(32.5213, 34.9453, -30.4f),   // בנימינה
        C(31.9096, 35.2494, -35.5f),   // גבעת אסף
        C(31.8506, 35.1658, -25.7f),   // גבעת זאב
        C(32.0701, 34.81, -21.3f),   // גבעתיים
        C(32.0147, 34.7494, -21.3f),   // בת ים
        C(32.0775, 34.8523, -20.3f),   // גבעת שמואל
        C(31.8111, 34.7771, -28.9f),   // גדרה
        C(31.0692, 35.0378, -30.4f),   // דימונה
        C(32.1495, 34.8854, -20.4f),   // הוד השרון
        C(32.1861, 35.2645, -25.6f),   // הר ברכה
        C(32.1781, 34.8106, -30.5f),   // הרצליה
        C(32.5688, 34.9548, -28.4f),   // זכרון יעקב
        C(32.4251, 34.9498, -30.2f),   // חדרה
        C(32.0147, 34.7865, -21.2f),   // חולון
        C(32.3101, 35.1931, -22.3f),   // חומש
        C(32.8456, 35.7928, -30.3f),   // חיספין
        C(32.4597, 35.0492, -28.7f),   // חריש
        C(32.8021, 34.9626, -27.7f),   // חיפה
        C(31.789, 34.8014, -29f),   // חפץ חיים
        C(32.7842, 35.5204, -27.7f),   // טבריה
        C(31.9384, 35.1385, -26.6f),   // טלמון
        C(31.8781, 34.7375, -20.5f),   // יבנה
        C(31.1883, 34.3183, -28.9f),   // יבול
        C(31.7962, 34.8218, -29.1f),   // יד בנימין
        C(32.0322, 34.8879, -20.4f),   // יהוד
        C(33.0574, 35.6055, -28.4f),   // יסוד המעלה
        C(30.9954, 34.9238, -30.3f),   // ירוחם
        C(32.1648, 35.2448, -25.6f),   // יצהר
        C(31.7378, 35.188, -35.6f),   // ירושלים
        C(31.9568, 35.3459, -26.2f),   // כוכב השחר
        C(31.8222, 35.3409, -27.2f),   // כפר אדומים
        C(32.7478, 35.089, -28.4f),   // כפר חסידים
        C(31.4324, 34.5386, -30.6f),   // כפר מימון
        C(32.1799, 34.9227, -20.3f),   // כפר סבא
        C(32.9105, 35.285, -30.4f),   // כרמיאל
        C(32.8351, 35.4986, -32.2f),   // מגדל
        C(31.7974, 35.1404, -25.7f),   // מבשרת ציון
        C(31.9525, 34.8909, -30.4f),   // לוד
        C(32.6732, 35.244, -30.4f),   // מגדל העמק
        C(32.7904, 35.4418, -27.1f),   // לביא
        C(31.899, 35.007, -19.4f),   // מודיעין
        C(31.8439, 34.8454, -19.1f),   // מזכרת בתיה
        C(32.3642, 35.5147, -30.3f),   // מחולה
        C(32.9875, 35.4416, -35.2f),   // מירון
        C(31.7864, 35.3123, -30.4f),   // מעלה אדומים
        C(31.3281, 34.9387, -27f),   // מיתר
        C(31.3922, 34.5943, -28.8f),   // מעגלים
        C(32.0549, 35.2405, -25.7f),   // מעלה לבונה
        C(32.0705, 35.4052, -27.3f),   // מעלה אפרים
        C(33.0143, 35.2798, -26.3f),   // מעלות
        C(31.3118, 35.3636, -29f),   // מצדה
        C(30.6154, 34.7977, -25.3f),   // מצפה רמון
        C(32.6891, 35.1937, -30.6f),   // נהלל
        C(31.8186, 35.3942, -28.3f),   // מצפה יריחו
        C(32.0076, 35.1266, -26.3f),   // נווה צוף (חלמיש)
        C(33.0152, 35.1026, -30.3f),   // נהריה
        C(32.7101, 35.3228, -17.4f),   // נוף הגליל
        C(31.8224, 35.3195, -27f),   // נופי פרת
        C(31.9738, 35.1403, -26.2f),   // נחליאל
        C(32.7002, 34.9914, -27.7f),   // ניר עציון
        C(31.9241, 34.7969, -20.3f),   // נס ציונה
        C(31.963, 35.0645, -27f),   // נעלה
        C(31.9533, 35.1274, -18.3f),   // נריה
        C(32.7679, 35.0421, -30.5f),   // נשר
        C(31.4271, 34.584, -28.5f),   // נתיבות
        C(32.3004, 34.8728, -21.2f),   // נתניה
        C(31.3913, 35.1132, -25.7f),   // סוסיא
        C(32.0005, 35.176, -25.8f),   // עטרת
        C(31.1991, 35.3636, -30.4f),   // עין בוקק
        C(31.4591, 35.3948, -33.4f),   // עין גדי
        C(32.0752, 35.2754, -16.9f),   // עלי
        C(32.9307, 35.0835, -30.4f),   // עכו
        C(30.6543, 35.2336, -21.4f),   // עין יהב
        C(32.6154, 35.297, -29.2f),   // עפולה
        C(31.9532, 35.2621, -16.5f),   // עפרה
        C(32.1662, 35.1497, -18f),   // עמנואל
        C(31.4402, 35.0298, -27f),   // עתניאל
        C(31.2576, 35.2131, -26.2f),   // ערד
        C(32.9747, 35.3334, -30.4f),   // פקיעין
        C(32.0618, 35.0519, -18.4f),   // פדואל
        C(32.0943, 34.878, -40.4f),   // פתח תקוה
        C(32.4763, 34.9773, -30.5f),   // פרדס חנה-כרכור
        C(31.2033, 34.534, -19.3f),   // צאלים
        C(31.6633, 34.7295, -29.1f),   // קוממיות
        C(32.9691, 35.5006, -25.2f),   // צפת
        C(32.221, 35.1627, -26.6f),   // קדומים
        C(32.7253, 35.6222, -33.1f),   // קיבוץ האון
        C(32.4526, 35.4217, -26.8f),   // קיבוץ מירב
        C(32.4844, 35.1157, -27.1f),   // קציר
        C(32.0497, 35.3398, -25.6f),   // קידה
        C(31.5341, 35.1236, -16f),   // קרית ארבע
        C(32.052, 34.8607, -20.1f),   // קרית אונו
        C(32.9914, 35.6893, -18.4f),   // קצרין
        C(32.805, 35.0913, -29.5f),   // קרית אתא
        C(32.8357, 35.0954, -29.6f),   // קרית ביאליק
        C(31.6197, 34.7659, -28.4f),   // קרית גת
        C(32.8477, 35.0704, -30.4f),   // קרית ים
        C(33.2093, 35.5725, -30.2f),   // קרית שמונה
        C(32.7132, 35.1226, -29.2f),   // קרית טבעון
        C(32.8417, 35.0822, -29.6f),   // קרית מוצקין
        C(31.732, 34.7457, -30.4f),   // קרית מלאכי
        C(32.1167, 35.1129, -26.8f),   // קרית נטפים
        C(32.1668, 35.0839, -18.4f),   // קרני שומרון
        C(32.9654, 35.5419, -26.8f),   // ראש פינה
        C(31.8921, 34.8017, -19f),   // רחובות
        C(32.0938, 34.962, -28.7f),   // ראש העין
        C(31.9727, 34.8062, -21.4f),   // ראשון לציון
        C(32.7512, 35.1075, -20.3f),   // רכסים
        C(31.9267, 34.8646, -30.3f),   // רמלה
        C(32.0493, 34.8425, -20.2f),   // רמת גן
        C(32.1395, 34.8337, -28.9f),   // רמת השרון
        C(32.8471, 35.8083, -26.8f),   // רמת מגשימים
        C(32.9799, 35.0837, -30.4f),   // שבי ציון
        C(32.1905, 34.863, -19f),   // רעננה
        C(31.5313, 34.5985, -19.9f),   // שדרות
        C(31.4313, 34.8835, -25.8f),   // שומריה
        C(32.0505, 35.2982, -25.8f),   // שילה
        C(32.7037, 35.2302, -27.7f),   // תמרת
        C(32.0574, 34.7986, -20.8f),   // תל אביב
        C(31.326, 34.6845, -28.1f),   // תפרח
        C(31.5247, 35.1074, -16.1f),   // חברון
        C(32.7611, 34.9716, -29.3f),   // טירת הכרמל
        C(32.6495, 35.0963, -28.6f),   // יוקנעם
        C(31.9971, 34.9469, -20.8f),   // שהם
        C(31.4324, 34.5386, -30.6f),   // תושיה
        C(31.8702, 34.9857, -28.1f),   // שעלבים
        C(31.3644, 35.1118, -26f),   // לב יתיר
        C(32.72, 35.56, -27.4f),   // כינר
        C(31.99, 34.85, -30.2f),   // כפר חבד
        C(33.31, 35.78, -22.5f),   // חרמון מפלס עליון
        C(33.29, 35.76, -23.6f),   // חרמון מפלס תחתון
        C(31.86, 35.46, -21.3f),   // יריחו
        C(32.22, 35.26, -26f),   // שכם
        C(31.5, 34.47, -29.2f),   // עזה
        C(31.8, 35.1, -26f),   // טלזסטון
        C(31.51, 35.12, -25.3f),   // בית חגי
        C(32.39, 35.31, -28f),   // גנים
        C(31.33, 35.05, -20.6f),   // יתיר
        C(32.45, 35.24, -28f),   // כדים
        C(31.64, 34.73, -30.9f),   // נעה
        C(29.86, 35.07, -19.8f),   // סיירים
        C(32.36, 35.24, -24.4f),   // שא נור
        C(31.932, 35.043, -30.4f),   // קרית ספר (מודיעין עילית)
    )

    private fun candleOffset(lat: Double, lon: Double): Float {
        var best = CITIES[0]; var bd = Double.MAX_VALUE
        val k = cos(Math.toRadians(lat))
        for (c in CITIES) {
            val dy = c.lat - lat; val dx = (c.lon - lon) * k
            val d = dy * dy + dx * dx
            if (d < bd) { bd = d; best = c }
        }
        return best.off
    }
    private fun roundMin(ms: Long) = (ms + 30_000) / 60_000 * 60_000
    private fun ceilMin(ms: Long) = (ms + 59_999) / 60_000 * 60_000
    private fun candle(prev: Calendar, lat: Double, lon: Double) =
        roundMin(sunset(prev, lat, lon, 90.833) + (candleOffset(lat, lon) * 60_000).toLong())

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
        ceilMin(sunset(day, lat, lon, 98.5))   // צאת הכוכבים: השמש 8.5° מתחת לאופק, מעוגל למעלה (כמו באתר ישיבה)

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

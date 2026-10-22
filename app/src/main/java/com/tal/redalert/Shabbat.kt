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

    // זמנים כמו באתר "עתים לבינה" (itimlabina.co.il): לכל אחד מ-162 יישובים - ההפרש של הדלקת הנרות מהשקיעה
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
        C(31.9443, 34.8448, -23.0f, -0.8f),   // באר יעקב
        C(31.9405, 35.2219, -36.5f, -1.3f),   // בית אל
        C(31.7926, 34.812, -39.3f, -1.1f),   // בית חלקיה
        C(31.8224, 35.5042, -30.5f, -1.3f),   // בית חגלה
        C(31.1453, 34.3179, -24.0f, -0.5f),   // בני נצרים
        C(32.5213, 34.9453, -31.0f, -1.0f),   // בנימינה
        C(31.9096, 35.2494, -36.5f, -1.2f),   // גבעת אסף
        C(31.8506, 35.1658, -36.9f, -0.6f),   // גבעת זאב
        C(32.0701, 34.81, -23.0f, -0.9f),   // גבעתיים
        C(32.0147, 34.7494, -21.4f, -1.2f),   // בת ים
        C(32.0775, 34.8523, -40.9f, -0.7f),   // גבעת שמואל
        C(31.8111, 34.7771, -19.5f, -1.2f),   // גדרה
        C(31.0692, 35.0378, -20.2f, -0.7f),   // דימונה
        C(32.1861, 35.2645, -36.1f, -1.0f),   // הר ברכה
        C(32.5688, 34.9548, -28.9f, -0.9f),   // זכרון יעקב
        C(32.3101, 35.1931, -37.3f, -1.2f),   // חומש
        C(32.8456, 35.7928, -31.3f, -0.4f),   // חיספין
        C(32.4597, 35.0492, -28.7f, -0.6f),   // חריש
        C(31.789, 34.8014, -31.4f, -1.1f),   // חפץ חיים
        C(31.9384, 35.1385, -36.9f, -0.7f),   // טלמון
        C(31.8781, 34.7375, -30.5f, -1.3f),   // יבנה
        C(31.1883, 34.3183, -24.0f, -0.5f),   // יבול
        C(31.7962, 34.8218, -31.3f, -1.0f),   // יד בנימין
        C(32.0322, 34.8879, -30.8f, -0.6f),   // יהוד
        C(33.0574, 35.6055, -30.8f, -1.0f),   // יסוד המעלה
        C(30.9954, 34.9238, -30.8f, -1.2f),   // ירוחם
        C(32.1648, 35.2448, -26.2f, -1.1f),   // יצהר
        C(31.9568, 35.3459, -37.0f, -0.8f),   // כוכב השחר
        C(31.8222, 35.3409, -31.2f, -0.9f),   // כפר אדומים
        C(32.7478, 35.089, -31.2f, -1.3f),   // כפר חסידים
        C(31.4324, 34.5386, -30.8f, -0.4f),   // כפר מימון
        C(32.8351, 35.4986, -30.5f, -0.6f),   // מגדל
        C(31.7974, 35.1404, -37.0f, -0.8f),   // מבשרת ציון
        C(31.9525, 34.8909, -30.9f, -0.6f),   // לוד
        C(32.6732, 35.244, -20.7f, -0.7f),   // מגדל העמק
        C(32.7904, 35.4418, -30.7f, -0.8f),   // לביא
        C(31.8439, 34.8454, -31.2f, -0.9f),   // מזכרת בתיה
        C(32.3642, 35.5147, -40.9f, -0.9f),   // מחולה
        C(32.9875, 35.4416, -36.5f, -0.7f),   // מירון
        C(31.3281, 34.9387, -37.3f, -0.9f),   // מיתר
        C(31.3922, 34.5943, -30.6f, -1.2f),   // מעגלים
        C(32.0549, 35.2405, -36.3f, -1.2f),   // מעלה לבונה
        C(32.0705, 35.4052, -30.7f, -0.5f),   // מעלה אפרים
        C(33.0143, 35.2798, -27.1f, 5.7f),   // מעלות
        C(31.3118, 35.3636, -30.7f, -1.2f),   // מצדה
        C(30.6154, 34.7977, -18.7f, -1.0f),   // מצפה רמון
        C(32.6891, 35.1937, -30.8f, -0.9f),   // נהלל
        C(31.8186, 35.3942, -36.0f, -0.7f),   // מצפה יריחו
        C(32.0076, 35.1266, -36.9f, -0.7f),   // נווה צוף (חלמיש)
        C(31.8224, 35.3195, -31.3f, -1.0f),   // נופי פרת
        C(31.9738, 35.1403, -26.8f, -0.6f),   // נחליאל
        C(32.7002, 34.9914, -28.6f, -0.7f),   // ניר עציון
        C(31.9241, 34.7969, -31.3f, -1.0f),   // נס ציונה
        C(31.963, 35.0645, -38.1f, -0.9f),   // נעלה
        C(31.9533, 35.1274, -36.9f, -0.7f),   // נריה
        C(32.7679, 35.0421, -29.4f, -0.5f),   // נשר
        C(31.3913, 35.1132, -36.6f, -1.2f),   // סוסיא
        C(32.0005, 35.176, -36.7f, -0.5f),   // עטרת
        C(31.1991, 35.3636, -30.8f, -1.3f),   // עין בוקק
        C(31.4591, 35.3948, -31.4f, -1.0f),   // עין גדי
        C(32.0752, 35.2754, -36.2f, -1.0f),   // עלי
        C(30.6543, 35.2336, -30.9f, -1.2f),   // עין יהב
        C(31.9532, 35.2621, -36.4f, -1.2f),   // עפרה
        C(32.1662, 35.1497, -37.6f, -0.5f),   // עמנואל
        C(31.4402, 35.0298, -36.9f, -0.5f),   // עתניאל
        C(31.2576, 35.2131, -19.3f, -0.9f),   // ערד
        C(32.9747, 35.3334, -31.0f, -1.1f),   // פקיעין
        C(32.0618, 35.0519, -38.1f, -0.9f),   // פדואל
        C(32.4763, 34.9773, -30.9f, -0.9f),   // פרדס חנה-כרכור
        C(31.2033, 34.534, -31.1f, -0.6f),   // צאלים
        C(31.6633, 34.7295, -30.8f, -0.5f),   // קוממיות
        C(32.221, 35.1627, -37.5f, -0.4f),   // קדומים
        C(32.7253, 35.6222, -31.1f, -1.2f),   // קיבוץ האון
        C(32.4526, 35.4217, -37.2f, -1.2f),   // קיבוץ מירב
        C(32.4844, 35.1157, -31.4f, -1.4f),   // קציר
        C(32.0497, 35.3398, -26.0f, -0.8f),   // קידה
        C(32.052, 34.8607, -30.9f, -0.7f),   // קרית אונו
        C(32.9914, 35.6893, -31.5f, -0.7f),   // קצרין
        C(32.805, 35.0913, -31.1f, -1.2f),   // קרית אתא
        C(32.8357, 35.0954, -31.1f, -1.2f),   // קרית ביאליק
        C(31.6197, 34.7659, -22.7f, -0.4f),   // קרית גת
        C(32.8477, 35.0704, -31.2f, -1.3f),   // קרית ים
        C(32.7132, 35.1226, -31.1f, -1.2f),   // קרית טבעון
        C(32.8417, 35.0822, -31.1f, -1.2f),   // קרית מוצקין
        C(31.732, 34.7457, -21.7f, -0.4f),   // קרית מלאכי
        C(32.1167, 35.1129, -37.8f, -0.6f),   // קרית נטפים
        C(32.1668, 35.0839, -37.8f, -0.7f),   // קרני שומרון
        C(32.9654, 35.5419, -31.1f, -1.3f),   // ראש פינה
        C(32.0938, 34.962, -21.4f, -1.3f),   // ראש העין
        C(32.7512, 35.1075, -31.1f, -1.2f),   // רכסים
        C(31.9267, 34.8646, -21.0f, -0.8f),   // רמלה
        C(32.1395, 34.8337, -22.9f, -0.7f),   // רמת השרון
        C(32.8471, 35.8083, -37.2f, -1.3f),   // רמת מגשימים
        C(32.9799, 35.0837, -31.0f, -1.1f),   // שבי ציון
        C(31.5313, 34.5985, -20.5f, -1.1f),   // שדרות
        C(31.4313, 34.8835, -37.4f, -1.0f),   // שומריה
        C(32.0505, 35.2982, -26.1f, -0.9f),   // שילה
        C(32.7037, 35.2302, -30.7f, -0.8f),   // תמרת
        C(31.326, 34.6845, -20.4f, -0.9f),   // תפרח
        C(31.5247, 35.1074, -36.5f, -1.1f),   // חברון
        C(32.7611, 34.9716, -19.6f, -0.7f),   // טירת הכרמל
        C(32.6495, 35.0963, -31.3f, -1.3f),   // יוקנעם
        C(31.9971, 34.9469, -30.6f, -0.4f),   // שהם
        C(31.4324, 34.5386, -30.8f, -0.4f),   // תושיה
        C(31.8702, 34.9857, -28.6f, -1.3f),   // שעלבים
        C(31.3644, 35.1118, -36.6f, -1.2f),   // לב יתיר
        C(32.72, 35.56, -31.3f, -0.4f),   // כינר
        C(31.99, 34.85, -31.0f, -0.8f),   // כפר חבד
        C(33.31, 35.78, -30.8f, -1.1f),   // חרמון מפלס עליון
        C(33.29, 35.76, -30.9f, -1.2f),   // חרמון מפלס תחתון
        C(31.8, 35.1, -37.2f, -0.9f),   // טלזסטון
        C(31.51, 35.12, -36.4f, -1.0f),   // בית חגי
        C(32.39, 35.31, -37.7f, -0.8f),   // גנים
        C(31.33, 35.05, -36.9f, -0.5f),   // יתיר
        C(32.45, 35.24, -37.9f, -0.8f),   // כדים
        C(31.64, 34.73, -30.8f, -0.5f),   // נעה
        C(29.86, 35.07, -31.4f, -0.4f),   // סיירים
        C(32.36, 35.24, -37.0f, -0.8f),   // שא נור
        C(31.4271, 34.584, -29.6f, -1.2f),   // נתיבות
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

package com.tal.tzofarrelay

import android.content.Context
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * הגדרות התצוגה של הממסר - אותן אפשרויות ואותם ברירות מחדל כמו בעמוד "תצוגה" של צופר חירום:
 * ערכת צבעים, שורת חיוויים, שעון ותאריך
 */
object Display {
    private fun sp(c: Context) = c.getSharedPreferences("display", Context.MODE_PRIVATE)
    // ערכת צבעים: 0 חשוך, 1 בהיר, 2 לפי המערכת, 3 לפי זמן
    fun themeMode(c: Context) = sp(c).getInt("themeMode", 0)
    fun setThemeMode(c: Context, v: Int) = sp(c).edit().putInt("themeMode", v).apply()
    // לפי זמן: 0 משקיעה עד זריחה, 1 לוח זמנים מותאם
    fun themeTimeMode(c: Context) = sp(c).getInt("themeTimeMode", 0)
    fun setThemeTimeMode(c: Context, v: Int) = sp(c).edit().putInt("themeTimeMode", v).apply()
    fun darkFrom(c: Context) = sp(c).getInt("darkFrom", 19 * 60)
    fun setDarkFrom(c: Context, v: Int) = sp(c).edit().putInt("darkFrom", v).apply()
    fun darkTo(c: Context) = sp(c).getInt("darkTo", 7 * 60)
    fun setDarkTo(c: Context, v: Int) = sp(c).edit().putInt("darkTo", v).apply()
    // שורת חיוויים
    const val STATUS_ALWAYS = 0; const val STATUS_HIDDEN = 1; const val STATUS_TIMED = 2
    fun statusMode(c: Context) = sp(c).getInt("statusMode", STATUS_ALWAYS)
    fun setStatusMode(c: Context, v: Int) = sp(c).edit().putInt("statusMode", v).apply()
    fun statusSeconds(c: Context) = sp(c).getInt("statusSec", 10)
    fun setStatusSeconds(c: Context, v: Int) = sp(c).edit().putInt("statusSec", v).apply()
    // שעון
    fun showSeconds(c: Context) = sp(c).getBoolean("seconds", true)
    fun setShowSeconds(c: Context, v: Boolean) = sp(c).edit().putBoolean("seconds", v).apply()
    fun blinkColon(c: Context) = sp(c).getBoolean("blink_colon", false)
    fun setBlinkColon(c: Context, v: Boolean) = sp(c).edit().putBoolean("blink_colon", v).apply()
    // תאריך
    const val DAY_FULL = 0; const val DAY_NAME = 1; const val DAY_LETTER = 2; const val DAY_SHORT = 3
    fun showDate(c: Context) = sp(c).getBoolean("date", true)
    fun setShowDate(c: Context, v: Boolean) = sp(c).edit().putBoolean("date", v).apply()
    fun dateKind(c: Context) = sp(c).getInt("dateKind", 0)
    fun setDateKind(c: Context, v: Int) = sp(c).edit().putInt("dateKind", v).apply()
    fun dayStyle(c: Context) = sp(c).getInt("dayStyle", DAY_FULL)
    fun setDayStyle(c: Context, v: Int) = sp(c).edit().putInt("dayStyle", v).apply()

    /** "לפי זמן": האם עכשיו חשוך. משקיעה עד זריחה - לפי ירושלים (הממסר נשאר בבית) */
    fun darkByTime(c: Context, now: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        if (themeTimeMode(c) == 1) {
            val min = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val from = darkFrom(c); val to = darkTo(c)
            return if (from == to) false else if (from < to) min in from until to else min >= from || min < to
        }
        val rise = sunEvent(cal, 31.78, 35.22, rising = true)
        val set = sunEvent(cal, 31.78, 35.22, rising = false)
        return now < rise || now >= set
    }
    fun dark(c: Context) = when (themeMode(c)) {
        1 -> false
        3 -> darkByTime(c)
        2 -> (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        else -> true
    }

    /** זריחה / שקיעה רגילה ביום נתון (לערכת הצבעים "לפי זמן") - זמן במילישניות */
    fun sunEvent(day: Calendar, lat: Double, lon: Double, rising: Boolean): Long {
        if (!rising) return sunset(day, lat, lon, 90.833)
        val n = day.get(Calendar.DAY_OF_YEAR)
        val lngHour = lon / 15.0
        val t = n + ((6 - lngHour) / 24)
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
        l = (l + 360) % 360
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
        ra = (ra + 360) % 360
        ra += (floor(l / 90) * 90 - floor(ra / 90) * 90)
        ra /= 15
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(Math.toRadians(90.833)) - sinDec * sin(Math.toRadians(lat))) / (cosDec * cos(Math.toRadians(lat)))
        val h = (360 - Math.toDegrees(acos(cosH.coerceIn(-1.0, 1.0)))) / 15
        val localT = h + ra - 0.06571 * t - 6.622
        val ut = ((localT - lngHour) % 24 + 24) % 24
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
        }
        return utc.timeInMillis + (ut * 3600_000).toLong()
    }

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

object TimeFormat {
    /** שמות הימים (ראשון...שבת) - גם לבחירת ימים בהגדרות */
    val NAMES = arrayOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
    private val LETTERS = arrayOf("א'", "ב'", "ג'", "ד'", "ה'", "ו'", "ש'")

    /** דוגמאות לתפריט ההגדרות */
    /** אפשרויות הסגנון - לפי היום של היום (שלישי -> "יום שלישי", "שלישי", "יום ג'", "ג'") */
    fun dayOptions(): Array<String> {
        val i = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
        return arrayOf("יום ${NAMES[i]}", NAMES[i], "יום ${LETTERS[i]}", LETTERS[i])
    }

    private fun cal(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }

    fun time(c: Context, ms: Long): String {
        val k = cal(ms)
        val hm = "%02d:%02d".format(k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE))
        return if (Display.showSeconds(c)) "$hm:%02d".format(k.get(Calendar.SECOND)) else hm
    }

    /**
     * שעה לשעון הגדול: הנקודתיים מורמות לאמצע גובה הספרות,
     * ואם נבחר - מהבהבות (חצי שנייה מוצגות, חצי שנייה שקופות, בלי שהשעה זזה)
     */
    fun clock(c: Context, ms: Long): CharSequence {
        val t = time(c, ms)
        val hide = Display.blinkColon(c) && ms % 1000 >= 500   // חצי שנייה כן, חצי שנייה לא
        val sp = android.text.SpannableString(t)
        t.forEachIndexed { i, ch ->
            if (ch != ':') return@forEachIndexed
            sp.setSpan(RaiseSpan(), i, i + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (hide) sp.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.TRANSPARENT),
                i, i + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    /** מרים את התו מעט למעלה (הנקודתיים יושבות נמוך ביחס לספרות) */
    private class RaiseSpan : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(tp: android.text.TextPaint) { tp.baselineShift -= (tp.textSize * 0.09f).toInt() }
        override fun updateMeasureState(tp: android.text.TextPaint) { tp.baselineShift -= (tp.textSize * 0.09f).toInt() }
    }

    fun day(c: Context, ms: Long): String {
        val i = cal(ms).get(Calendar.DAY_OF_WEEK) - 1
        return when (Display.dayStyle(c)) {
            Display.DAY_NAME -> NAMES[i]
            Display.DAY_LETTER -> "יום ${LETTERS[i]}"
            Display.DAY_SHORT -> LETTERS[i]
            else -> "יום ${NAMES[i]}"
        }
    }

    fun date(ms: Long): String {
        val k = cal(ms)
        return "%02d/%02d/%d".format(k.get(Calendar.DAY_OF_MONTH), k.get(Calendar.MONTH) + 1, k.get(Calendar.YEAR))
    }

    /** שורת יום + תאריך (בלי תאריך אם כובה) - לועזי / עברי / שניהם לפי ההגדרה */
    fun dayLine(c: Context, ms: Long): String {
        if (!Display.showDate(c)) return day(c, ms)
        val d = when (Display.dateKind(c)) {
            1 -> hebrewDate(ms)
            2 -> "${date(ms)} · ${hebrewDate(ms)}"
            else -> date(ms)
        }
        return "${day(c, ms)} · $d"
    }

    /** תאריך עברי, למשל: ו' בחשוון תשפ"ז */
    fun hebrewDate(ms: Long): String {
        val h = android.icu.util.HebrewCalendar(android.icu.util.TimeZone.getTimeZone("Asia/Jerusalem"))
        h.timeInMillis = ms
        val day = h.get(android.icu.util.Calendar.DAY_OF_MONTH)
        val m = h.get(android.icu.util.Calendar.MONTH)
        val year = h.get(android.icu.util.Calendar.YEAR)
        val leap = ((7 * year + 1) % 19) < 7
        val month = when (m) {
            android.icu.util.HebrewCalendar.TISHRI -> "תשרי"
            android.icu.util.HebrewCalendar.HESHVAN -> "חשוון"
            android.icu.util.HebrewCalendar.KISLEV -> "כסלו"
            android.icu.util.HebrewCalendar.TEVET -> "טבת"
            android.icu.util.HebrewCalendar.SHEVAT -> "שבט"
            android.icu.util.HebrewCalendar.ADAR_1 -> if (leap) "אדר א'" else "אדר"
            android.icu.util.HebrewCalendar.ADAR -> if (leap) "אדר ב'" else "אדר"
            android.icu.util.HebrewCalendar.NISAN -> "ניסן"
            android.icu.util.HebrewCalendar.IYAR -> "אייר"
            android.icu.util.HebrewCalendar.SIVAN -> "סיוון"
            android.icu.util.HebrewCalendar.TAMUZ -> "תמוז"
            android.icu.util.HebrewCalendar.AV -> "אב"
            else -> "אלול"
        }
        return "${gematria(day)} ב$month ${gematria(year % 1000)}"
    }

    /** מספר באותיות עבריות עם גרש / גרשיים (15 = ט"ו, 16 = ט"ז) */
    private fun gematria(n0: Int): String {
        var n = n0
        val sb = StringBuilder()
        val hundreds = listOf(400 to "ת", 300 to "ש", 200 to "ר", 100 to "ק")
        for ((v, l) in hundreds) while (n >= v) { sb.append(l); n -= v }
        when (n) {
            15 -> { sb.append("טו"); n = 0 }
            16 -> { sb.append("טז"); n = 0 }
        }
        val tens = listOf(90 to "צ", 80 to "פ", 70 to "ע", 60 to "ס", 50 to "נ", 40 to "מ", 30 to "ל", 20 to "כ", 10 to "י")
        for ((v, l) in tens) if (n >= v) { sb.append(l); n -= v; break }
        if (n > 0) sb.append("אבגדהוזחט"[n - 1])
        val s = sb.toString()
        return if (s.length == 1) "$s'" else s.dropLast(1) + "\"" + s.last()
    }
}

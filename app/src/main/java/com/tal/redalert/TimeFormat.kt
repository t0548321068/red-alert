package com.tal.redalert

import android.content.Context
import java.util.Calendar

/** עיצוב שעה / יום / תאריך לפי ההגדרות */
object TimeFormat {
    private val NAMES = arrayOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
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
        return if (Prefs.showSeconds(c)) "$hm:%02d".format(k.get(Calendar.SECOND)) else hm
    }

    fun day(c: Context, ms: Long): String {
        val i = cal(ms).get(Calendar.DAY_OF_WEEK) - 1
        return when (Prefs.dayStyle(c)) {
            Prefs.DAY_NAME -> NAMES[i]
            Prefs.DAY_LETTER -> "יום ${LETTERS[i]}"
            Prefs.DAY_SHORT -> LETTERS[i]
            else -> "יום ${NAMES[i]}"
        }
    }

    /** ברכה לפי השעה */
    fun greeting(ms: Long): String {
        val h = cal(ms).get(Calendar.HOUR_OF_DAY)
        // שעון קיץ / חורף - לפי השעון של הטלפון
        val summer = java.util.TimeZone.getDefault().inDaylightTime(java.util.Date(ms))
        return if (summer) when (h) {
            in 5..11 -> "בוקר טוב"
            in 12..15 -> "צהריים טובים"
            in 16..18 -> "אחר צהריים טובים"
            in 19..21 -> "ערב טוב"
            else -> "לילה טוב"
        } else when (h) {
            in 5..11 -> "בוקר טוב"
            in 12..14 -> "צהריים טובים"
            in 15..17 -> "אחר צהריים טובים"
            in 18..20 -> "ערב טוב"
            else -> "לילה טוב"
        }
    }

    fun date(ms: Long): String {
        val k = cal(ms)
        return "%02d/%02d/%d".format(k.get(Calendar.DAY_OF_MONTH), k.get(Calendar.MONTH) + 1, k.get(Calendar.YEAR))
    }

    /** שורת יום + תאריך (בלי תאריך אם כובה) */
    fun dayLine(c: Context, ms: Long): String =
        if (Prefs.showDate(c)) "${day(c, ms)} · ${date(ms)}" else day(c, ms)

    /** לשורה ברשימת ההתראות */
    fun stamp(c: Context, ms: Long): String {
        val t = time(c, ms)
        if (!Prefs.showDate(c)) return t
        val k = cal(ms)
        return "%02d/%02d %s".format(k.get(Calendar.DAY_OF_MONTH), k.get(Calendar.MONTH) + 1, t)
    }
}

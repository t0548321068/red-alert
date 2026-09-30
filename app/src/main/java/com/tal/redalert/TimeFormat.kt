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

    /**
     * ברכה לפי השעה ולפי השקיעה במקום שלך:
     * ערב טוב מהשקיעה, לילה טוב שעתיים וחצי אחריה (בין 20:00 ל-22:30),
     * בוקר טוב מכשעה לפני הזריחה. בחורף הערב והלילה מתחילים מוקדם יותר.
     */
    fun greeting(c: Context, ms: Long): String {
        val loc = try { Weather.lastLocation(c) } catch (_: Exception) { null }
        val (rise, set) = Sun.times(loc?.latitude ?: 31.77, loc?.longitude ?: 35.21, ms) ?: (360 to 1140)
        val k = cal(ms)
        val now = k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE)
        val night = (set + 150).coerceIn(20 * 60, 22 * 60 + 30)
        val morning = (rise - 60).coerceIn(4 * 60, 6 * 60)
        return when {
            now < morning || now >= night -> "לילה טוב"
            now >= set -> "ערב טוב"
            now < 12 * 60 -> "בוקר טוב"
            now < 15 * 60 -> "צהריים טובים"
            else -> "אחר צהריים טובים"
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

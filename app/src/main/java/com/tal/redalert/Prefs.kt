package com.tal.redalert

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val FILE = "prefs"
    private const val MAX_HISTORY = 300   // מספיק לשבוע אחורה במפה

    // הגדרות נשמרות מיד לדיסק (commit) - שלא ילכו לאיבוד אם האפליקציה נסגרת לעדכון
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** ערים לסינון. ריק = כל הארץ */
    fun cities(c: Context): List<String> =
        (sp(c).getString("cities", "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun setCities(c: Context, list: List<String>) =
        sp(c).edit().putString("cities", list.joinToString(",")).commit()

    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).commit()

    // ---- תצוגת שעה ותאריך ----
    const val DAY_FULL = 0      // יום שני
    const val DAY_NAME = 1      // שני
    const val DAY_LETTER = 2    // יום ב'
    const val DAY_SHORT = 3     // ב'

    fun dayStyle(c: Context) = sp(c).getInt("dayStyle", DAY_FULL)
    fun setDayStyle(c: Context, v: Int) = sp(c).edit().putInt("dayStyle", v).commit()
    fun showSeconds(c: Context) = sp(c).getBoolean("seconds", true)
    fun setShowSeconds(c: Context, v: Boolean) = sp(c).edit().putBoolean("seconds", v).commit()
    fun showDate(c: Context) = sp(c).getBoolean("date", true)
    fun setShowDate(c: Context, v: Boolean) = sp(c).edit().putBoolean("date", v).commit()
    fun showWeather(c: Context) = sp(c).getBoolean("weather", true)
    /** כל כמה דקות לרענן את מזג האוויר והמיקום שלו */
    fun weatherMinutes(c: Context) = sp(c).getInt("weatherMin", 2)
    fun setWeatherMinutes(c: Context, v: Int) = sp(c).edit().putInt("weatherMin", v).commit()
    fun setShowWeather(c: Context, v: Boolean) = sp(c).edit().putBoolean("weather", v).commit()

    /** הגרסה האחרונה שהמשתמש ראה עליה "מה חדש" */
    fun seenVersion(c: Context) = sp(c).getString("seenVer", "") ?: ""
    fun setSeenVersion(c: Context, v: String) = sp(c).edit().putString("seenVer", v).commit()

    // ---- פיד ארצי: כל ההתראות בארץ, בלי קשר לאזורים שלי ----
    private const val MAX_FEED = 150

    fun feed(c: Context): List<Entry> {
        val arr = try { JSONArray(sp(c).getString("feed", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry("", o.optString("title"), o.optString("body"), o.optInt("level"), o.optLong("ts"))
        }.let { collapse(it) }
    }

    /** כפילויות שנשמרו בגרסאות קודמות: אותו סוג בהפרש של פחות מדקה וחצי = שורה אחת */
    private fun collapse(list: List<Entry>): List<Entry> {
        val out = ArrayList<Entry>()
        for (e in list) {
            val top = out.lastOrNull()
            if (top != null && top.level == e.level && e.ts > 0 && top.ts - e.ts in 0 until 90_000L) {
                // הישנה (המקור הראשון) קובעת את הזמן והכותרת
                out[out.size - 1] = e.copy(body = mergeBody(e.body, top.body.split(", ")))
            } else out.add(e)
        }
        return out
    }

    /** mergeMs > 0: אם הרשומה האחרונה מאותו סוג ובתוך הזמן הזה - מצרפים אליה במקום שורה חדשה */
    @Synchronized
    fun addFeed(c: Context, e: Entry, mergeMs: Long = 0) {
        val old = feed(c)
        val first = old.firstOrNull()
        val list = if (mergeMs > 0 && first != null && first.level == e.level && e.ts - first.ts < mergeMs)
            listOf(first.copy(body = mergeBody(first.body, e.body.split(", ")))) + old.drop(1)
        else listOf(e) + old.take(MAX_FEED - 1)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("title", it.title).put("body", it.body)
                .put("level", it.level).put("ts", it.ts))
        }
        sp(c).edit().putString("feed", arr.toString()).apply()
    }

    // ---- דיוק מיקום: נלמד לכל מכשיר ----
    /** הדיוק הטוב ביותר (במטרים) שהמכשיר הזה הגיע אליו אי פעם. 0 = עוד לא נמדד */
    fun bestAccuracy(c: Context) = sp(c).getFloat("bestAcc", 0f)
    fun recordAccuracy(c: Context, acc: Float) {
        if (acc <= 0f) return
        val cur = bestAccuracy(c)
        if (cur == 0f || acc < cur) sp(c).edit().putFloat("bestAcc", acc).commit()
    }
    fun dualFrequency(c: Context) = sp(c).getBoolean("dualFreq", false)
    fun setDualFrequency(c: Context, v: Boolean) {
        if (dualFrequency(c) != v) sp(c).edit().putBoolean("dualFreq", v).commit()
    }

    // ---- קרוב אליי: התראה גם לפי המיקום הנוכחי ----
    fun nearMe(c: Context) = sp(c).getBoolean("nearMe", false)
    fun setNearMe(c: Context, v: Boolean) = sp(c).edit().putBoolean("nearMe", v).commit()
    /** האזורים שזוהו לאחרונה סביב המיקום (לתצוגה ולסינון) */
    fun nearbyAreas(c: Context): List<String> =
        (sp(c).getString("nearby", "") ?: "").split("|").filter { it.isNotEmpty() }
    fun setNearbyAreas(c: Context, v: List<String>) =
        sp(c).edit().putString("nearby", v.joinToString("|")).commit()

    // ---- צליל לכל סוג התראה (ריק = ברירת מחדל, "silent" = בלי צליל) ----
    fun sound(c: Context, level: Int) = sp(c).getString("sound$level", "") ?: ""
    fun setSound(c: Context, level: Int, uri: String) = sp(c).edit().putString("sound$level", uri).commit()

    // ---- הקראה בקול ----
    fun speakAlerts(c: Context) = sp(c).getBoolean("speak", true)
    fun setSpeakAlerts(c: Context, v: Boolean) = sp(c).edit().putBoolean("speak", v).commit()
    /** קול ההקראה: גבר (ברירת מחדל, כמו בצופר) או אישה */
    fun maleVoice(c: Context) = sp(c).getBoolean("maleVoice", true)
    fun setMaleVoice(c: Context, v: Boolean) = sp(c).edit().putBoolean("maleVoice", v).commit()

    // ---- סוג רטט לכל סוג התראה ----
    fun vibe(c: Context, level: Int) = sp(c).getInt("vibe$level", Vibes.default(level))
    fun setVibe(c: Context, level: Int, v: Int) = sp(c).edit().putInt("vibe$level", v).commit()

    // ---- שעות שקט: משתיק התראה מקדימה וסיום אירוע (ירי תמיד נשמע) ----
    fun quietOn(c: Context) = sp(c).getBoolean("quietOn", false)
    fun setQuietOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("quietOn", v).commit()
    /** דקות מתחילת היום */
    fun quietFrom(c: Context) = sp(c).getInt("quietFrom", 23 * 60)
    fun quietTo(c: Context) = sp(c).getInt("quietTo", 7 * 60)
    fun setQuiet(c: Context, from: Int, to: Int) =
        sp(c).edit().putInt("quietFrom", from).putInt("quietTo", to).commit()

    fun isQuietNow(c: Context): Boolean {
        if (!quietOn(c)) return false
        val k = java.util.Calendar.getInstance()
        val now = k.get(java.util.Calendar.HOUR_OF_DAY) * 60 + k.get(java.util.Calendar.MINUTE)
        val f = quietFrom(c); val t = quietTo(c)
        return if (f <= t) now in f until t else now >= f || now < t   // גם טווח שחוצה חצות
    }

    // ---- ספירה לאחור פעילה (לווידג'ט) ----
    fun countdownUntil(c: Context) = sp(c).getLong("cdUntil", 0L)
    fun countdownTitle(c: Context) = sp(c).getString("cdTitle", "") ?: ""
    fun setCountdown(c: Context, until: Long, title: String) =
        sp(c).edit().putLong("cdUntil", until).putString("cdTitle", title).commit()

    // ---- טיימר שהייה במרחב המוגן (10 דקות אחרי ירי) ----
    const val STAY_MS = 10 * 60 * 1000L
    fun stayUntil(c: Context) = sp(c).getLong("stayUntil", 0L)
    fun setStayUntil(c: Context, v: Long) = sp(c).edit().putLong("stayUntil", v).commit()

    // ---- עקיפת "נא לא להפריע" בזמן התראה ----
    fun dndOverride(c: Context) = sp(c).getBoolean("dndOverride", true)
    fun setDndOverride(c: Context, v: Boolean) = sp(c).edit().putBoolean("dndOverride", v).commit()

    /** ערכת נושא: true = כהה (ברירת מחדל), false = בהירה */
    fun darkTheme(c: Context) = sp(c).getBoolean("dark", true)
    fun setDarkTheme(c: Context, v: Boolean) = sp(c).edit().putBoolean("dark", v).commit()
    fun dialogTheme(c: Context) =
        if (darkTheme(c)) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    /** גרסה שנבחר עבורה "מאוחר יותר" - לא מקפיצים עליה שוב */
    fun skippedVersion(c: Context) = sp(c).getString("skipVer", "") ?: ""
    fun setSkippedVersion(c: Context, v: String) = sp(c).edit().putString("skipVer", v).commit()
    /** גרסה שכבר נשלחה עליה התראה ברקע */
    fun notifiedVersion(c: Context) = sp(c).getString("notifVer", "") ?: ""
    fun setNotifiedVersion(c: Context, v: String) = sp(c).edit().putString("notifVer", v).commit()

    data class Entry(val time: String, val title: String, val body: String, val level: Int, val ts: Long = 0)

    /** היסטוריית התראות, החדשה ראשונה */
    fun history(c: Context): List<Entry> {
        val arr = try { JSONArray(sp(c).getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.optString("time"), o.optString("title"), o.optString("body"),
                o.optInt("level"), o.optLong("ts"))
        }.filter { it.body != "התראת בדיקה" }   // בדיקות מגרסאות קודמות לא מוצגות
            .let { collapse(it) }
    }

    /** מוסיף יישובים לשורת ההתראה האחרונה (בלי כפילויות) */
    private fun mergeBody(body: String, add: List<String>): String {
        fun k(a: String) = a.split(" - ")[0].filter { it.isLetterOrDigit() }
        val cur = body.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val keys = cur.map { k(it) }.toMutableSet()
        val extra = add.filter { keys.add(k(it)) }
        return (cur + extra).joinToString(", ")
    }

    @Synchronized
    fun mergeFirstHistory(c: Context, add: List<String>) {
        val arr = try { JSONArray(sp(c).getString("history", "[]")) } catch (_: Exception) { return }
        if (arr.length() == 0) return
        val o = arr.getJSONObject(0)
        o.put("body", mergeBody(o.optString("body"), add))
        sp(c).edit().putString("history", arr.toString()).apply()
    }

    @Synchronized
    fun addHistory(c: Context, e: Entry) {
        val list = listOf(e) + history(c).take(MAX_HISTORY - 1)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("time", it.time).put("title", it.title)
                .put("body", it.body).put("level", it.level).put("ts", it.ts))
        }
        sp(c).edit().putString("history", arr.toString()).apply()
    }
}

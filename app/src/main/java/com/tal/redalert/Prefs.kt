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

    /** מפתח ליישוב - זהה בכל המקורות: "קריית שמונה" = "קרית שמונה", "תל אביב - מרכז" = "תל-אביב" */
    fun areaKey(a: String) = a.split(" - ")[0].filter { it.isLetterOrDigit() && it != 'י' && it != 'ו' }

    /** חלון זמן שבו אותה התראה מכמה מקורות נחשבת כפילות */
    const val DUP_MS = 5 * 60 * 1000L

    /**
     * איחוד כפילויות לתצוגה: אותו סוג, עם יישוב משותף, בהפרש של עד 5 דקות = שורה אחת.
     * גם התראות מאותו סוג בהפרש של פחות מדקה וחצי = אותו אירוע.
     * נשארים הזמן, הכותרת והמקור של מי שהגיע ראשון.
     */
    private fun collapse(list: List<Entry>): List<Entry> {
        val out = ArrayList<Entry>()
        val keys = ArrayList<MutableSet<String>>()
        for (e in list) {           // מהחדשה לישנה
            val ek = e.body.split(",").map { areaKey(it.trim()) }.filter { it.isNotEmpty() }.toSet()
            val i = out.indices.firstOrNull { j ->
                val k = out[j]
                k.level == e.level && e.ts > 0 && k.ts - e.ts in 0 until DUP_MS &&
                    (k.ts - e.ts < 90_000L || keys[j].any { it in ek })
            }
            if (i == null) { out.add(e); keys.add(ek.toMutableSet()); continue }
            out[i] = e.copy(body = mergeBody(e.body, out[i].body.split(", ")))
            keys[i].addAll(ek)
        }
        return out
    }

    /** מוסיף יישובים לשורת ההתראה האחרונה (בלי כפילויות) */
    private fun mergeBody(body: String, add: List<String>): String {
        fun k(a: String) = areaKey(a)
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
                .put("body", it.body).put("level", it.level).put("ts", it.ts).put("src", it.src))
        }
        sp(c).edit().putString("history", arr.toString()).apply()
    }
}

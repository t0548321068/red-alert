package com.tal.redalert

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * נתוני אזורי ההתרעה של פיקוד העורף:
 * זמן כניסה למרחב מוגן, מרכז וגבולות לכל אזור.
 * (נתונים מ-amitfin/oref_alert, רישיון MIT)
 */
object AreaData {

    @Volatile private var migunCache: Map<String, Int>? = null
    @Volatile private var areasCache: JSONObject? = null

    private fun migun(c: Context): Map<String, Int> = migunCache ?: synchronized(this) {
        migunCache ?: c.assets.open("migun.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { l ->
                val p = l.split('\t')
                if (p.size == 2) p[1].toIntOrNull()?.let { p[0] to it } else null
            }.toMap()
        }.also { migunCache = it }
    }

    fun areas(c: Context): JSONObject = areasCache ?: synchronized(this) {
        areasCache ?: JSONObject(
            c.assets.open("areas.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        ).also { areasCache = it }
    }

    /**
     * שמות ממקורות שונים לא תמיד זהים לשמות הרשמיים:
     * קודם התאמה מדויקת, אחרת כל האזורים שמתחילים בשם (למשל "תל אביב" -> "תל אביב - מרכז העיר")
     */
    fun match(c: Context, raw: String): List<String> {
        val all = areas(c)
        if (all.has(raw)) return listOf(raw)
        val out = mutableListOf<String>()
        val keys = all.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith("$raw -") || k.startsWith("$raw,")) out += k
        }
        return out
    }

    /** שניות להגעה למרחב מוגן (0 = מיידי), או null אם לא ידוע */
    fun shelterSeconds(c: Context, raw: String): Int? {
        val m = migun(c)
        m[raw]?.let { return it }
        return match(c, raw).mapNotNull { m[it] }.minOrNull()
    }

    /** הזמן הקצר ביותר מבין כמה אזורים */
    fun shelterSeconds(c: Context, areas: List<String>): Int? =
        areas.mapNotNull { shelterSeconds(c, it) }.minOrNull()

    fun shelterText(sec: Int): String = when {
        sec <= 0 -> "מיידי"
        sec < 60 -> "$sec שניות"
        sec == 60 -> "דקה"
        sec == 90 -> "דקה וחצי"
        else -> "${sec / 60} דקות"
    }

    /**
     * האזורים שבהם נמצא מיקום מסוים: האזור שהנקודה בתוכו,
     * ועוד אזורים שהמרכז שלהם קרוב (ברירת מחדל 3 ק"מ) - כדי לא לפספס בגבולות.
     */
    fun areasAt(c: Context, lat: Double, lon: Double, radiusKm: Double = 3.0): List<String> {
        val all = areas(c)
        val inside = mutableListOf<String>()
        val near = mutableListOf<Pair<String, Double>>()
        val keys = all.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val o = all.getJSONObject(name)
            o.optJSONArray("p")?.let { if (contains(it, lat, lon)) inside += name }
            val cc = o.getJSONArray("c")
            val d = distKm(lat, lon, cc.getDouble(0), cc.getDouble(1))
            if (d <= radiusKm) near += name to d
        }
        return (inside + near.sortedBy { it.second }.map { it.first }).distinct()
    }

    /** בדיקת נקודה בתוך פוליגון (ray casting) */
    private fun contains(poly: JSONArray, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = poly.length() - 1
        for (i in 0 until poly.length()) {
            val pi = poly.getJSONArray(i)
            val pj = poly.getJSONArray(j)
            val yi = pi.getDouble(0); val xi = pi.getDouble(1)
            val yj = pj.getDouble(0); val xj = pj.getDouble(1)
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111.0
        val dx = (lon2 - lon1) * 111.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dx * dx + dy * dy)
    }
}

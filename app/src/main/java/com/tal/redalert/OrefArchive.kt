package com.tal.redalert

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** כל ההתראות שהיו בארץ - מהארכיון של פיקוד העורף (החודש האחרון) */
object OrefArchive {
    /** הארכיון החדש של פיקוד העורף (נובמבר 2026; הכתובת הישנה alerts-history מפנה לדף האתר). period: lastDay / lastWeek / lastMonth */
    const val BASE = "https://www.oref.org.il/api/v1/AlertHistory/heb?period="
    private const val URL_MONTH = "${BASE}lastMonth"
    private const val DAY = 24 * 3600 * 1000L
    /** השרת שלנו שומר את כל ההיסטוריה (שנה אחורה) - אותה רשימה לכל הטלפונים. אם לא עונה - הארכיון של פיקוד העורף */
    private const val SERVER = "http://151.145.91.93:8080/archive"

    /** התרעות מהשרת שאחרי since (0 = הכול), או null אם השרת לא ענה */
    private fun fetchServer(since: Long): List<Prefs.Entry>? = try {
        val conn = URL("$SERVER?since=$since").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000; conn.readTimeout = 20000; conn.useCaches = false
        try {
            if (conn.responseCode != 200) null else {
                val arr = JSONArray(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i); val t = o.getString("t"); val ts = o.getLong("ts")
                    // s: לכל יישוב - כמה שניות אחרי תחילת השורה הוא הגיע (באותו סדר כמו b)
                    val areas = o.optString("b").split(",").map { it.trim() }
                    val s = o.optJSONArray("s")
                    val times = if (s == null || s.length() != areas.size) emptyMap()
                        else areas.indices.associate { areas[it] to ts + s.optLong(it) * 1000 }
                    Prefs.Entry("", AlertService.displayTitle(t), o.optString("b"), AlertService.levelOf(t), ts, times = times)
                }
            }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }

    private fun merge(c: android.content.Context, fresh: List<Prefs.Entry>): List<Prefs.Entry> {
        val yearAgo = System.currentTimeMillis() - 366 * DAY
        val merged = LinkedHashMap<String, Prefs.Entry>()
        (fresh + load(c)).forEach { e -> if (e.ts >= yearAgo) merged.putIfAbsent(e.title + "|" + e.ts / 60_000, e) }
        return merged.values.sortedByDescending { it.ts }
    }

    /**
     * משלים שנה אחורה מהשרת שלנו, ושומר בטלפון. אם השרת לא עונה - מנסים שוב בפעם הבאה.
     * מחזיר true אם נוספו התרעות (כדי לרענן את הרשימה)
     */
    fun backfill(c: android.content.Context): Boolean {
        val sp = c.getSharedPreferences("archive", android.content.Context.MODE_PRIVATE)
        if (sp.getBoolean("backfill3Done", false)) return false
        // השרת עונה - כל השנה ממנו בבקשה אחת
        fetchServer(0)?.let { all ->
            val before = load(c).size
            val m = merge(c, all); save(c, m)
            sp.edit().putBoolean("backfill3Done", true).apply()
            return m.size > before
        }
        // בלי השרת - אין השלמה של שנה (הארכיון של פיקוד העורף מחזיר רק את החודש האחרון, והוא מגיע בבקשה הרגילה)
        return false
    }

    /**
     * ההתרעות של החודש האחרון מהארכיון + מה שנשמר בטלפון מפעמים קודמות - כך הרשימה גדלה עם הזמן
     * ולא נמחקת אחרי חודש (נשמר עד שנה אחורה). בלי חיבור - מה שנשמר.
     */
    fun fetchAndKeep(c: android.content.Context): List<Prefs.Entry> {
        val stored = load(c)
        // מהשרת: רק מה שחדש (עם מרווח של יום); אם השרת לא עונה - הארכיון של פיקוד העורף
        val since = if (stored.isEmpty()) System.currentTimeMillis() - 31 * DAY else stored.maxOf { it.ts } - DAY
        val fresh = fetchServer(since) ?: try { fetchRaw() } catch (_: Exception) { emptyList() }
        val yearAgo = System.currentTimeMillis() - 366L * 24 * 3600 * 1000
        val merged = LinkedHashMap<String, Prefs.Entry>()
        // אותה התרעה = אותו סוג + אותה דקה (החדשה - עם שניות - גוברת על מה שנשמר)
        (fresh + stored).forEach { e -> if (e.ts >= yearAgo) merged.putIfAbsent(e.title + "|" + e.ts / 60_000, e) }
        val all = merged.values.sortedByDescending { it.ts }
        if (fresh.isNotEmpty()) save(c, all)
        return Prefs.collapse(all)
    }

    private fun file(c: android.content.Context) = java.io.File(c.filesDir, "archive.json")

    private fun load(c: android.content.Context): List<Prefs.Entry> = try {
        val arr = JSONArray(file(c).readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            // הסוג מחושב מחדש מהכותרת (לא מה שנשמר) - כך תיקוני סיווג חלים גם על התרעות ישנות
            val t = o.getString("t")
            val a = o.optJSONObject("a")
            Prefs.Entry("", AlertService.displayTitle(t), o.optString("b"), AlertService.levelOf(t), o.getLong("ts"),
                times = a?.keys()?.asSequence()?.associateWith { a.optLong(it) } ?: emptyMap())
        }
    } catch (_: Exception) { emptyList() }

    private fun save(c: android.content.Context, list: List<Prefs.Entry>) = try {
        val arr = JSONArray()
        list.forEach { e -> arr.put(org.json.JSONObject().put("t", e.title).put("b", e.body).put("l", e.level).put("ts", e.ts)
            .apply { if (e.times.isNotEmpty()) put("a", org.json.JSONObject().also { o -> e.times.forEach { (k, v) -> o.put(k, v) } }) }) }
        val tmp = java.io.File(c.filesDir, "archive.json.tmp")
        tmp.writeText(arr.toString()); tmp.renameTo(file(c))
    } catch (_: Exception) { false }

    /** השורות מהארכיון (לפני איחוד אירועים) */
    private fun fetchRaw(url: String = URL_MONTH): List<Prefs.Entry> {
        val conn = URL(url + "&t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 15000
        conn.useCaches = false
        conn.setRequestProperty("Referer", "https://www.oref.org.il/")
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RedAlert")
        val text = try {
            val b = conn.inputStream.use { it.readBytes() }
            // לפעמים עם BOM
            String(b, Charsets.UTF_8).trimStart('﻿').trim()
        } finally { conn.disconnect() }
        val rows = convert(text)
        if (!rows.startsWith("[")) return emptyList()
        val tz = java.util.TimeZone.getTimeZone("Asia/Jerusalem")
        val fmtSec = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = tz }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).apply { timeZone = tz }
        val fmtDT = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.US).apply { timeZone = tz }
        val arr = JSONArray(rows)
        // אותה התראה (סוג + דקה) = שורה אחת עם כל האזורים
        val groups = LinkedHashMap<String, Triple<String, LongArray, MutableList<String>>>()
        val times = HashMap<String, HashMap<String, Long>>()   // לכל שורה: מתי הגיע כל יישוב
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.optString("category_desc").ifEmpty { o.optString("title", "התרעה") }
            val full = o.optString("alertDate").replace('T', ' ')
            val date = full.take(16)
            // alertDate מעוגל לדקה - השניות האמיתיות בשדות date + time; הקיבוץ - לפי דקה, והשעה - השנייה הראשונה
            val d = o.optString("date"); val t = o.optString("time")
            val ts = (try { if (d.isNotEmpty() && t.length >= 8) fmtDT.parse("$d ${t.take(8)}")?.time else null } catch (_: Exception) { null })
                ?: (try { if (full.length >= 19) fmtSec.parse(full.take(19))?.time else null } catch (_: Exception) { null })
                ?: (try { fmt.parse(date)?.time } catch (_: Exception) { null }) ?: continue
            val g = groups.getOrPut("$title|$date") { Triple(title, longArrayOf(ts), mutableListOf()) }
            if (ts < g.second[0]) g.second[0] = ts
            val area = o.optString("data")
            if (area.isNotBlank() && area !in g.third) g.third.add(area)
            if (area.isNotBlank()) times.getOrPut("$title|$date") { HashMap() }.let { m -> m[area] = minOf(ts, m[area] ?: ts) }
        }
        val list = groups.map { (key, g) ->
            val (title, ts, areas) = g
            val level = AlertService.levelOf(title)
            // הסדר - לפי מתי הגיע כל יישוב
            val m = times[key] ?: emptyMap<String, Long>()
            Prefs.Entry("", AlertService.displayTitle(title),
                areas.sortedBy { m[it] ?: ts[0] }.joinToString(", "), level, ts[0], times = m)
        }.sortedByDescending { it.ts }
        return list
    }

    /**
     * הארכיון החדש ({"alarmsGroupedByDate":[{"key":"dd.MM.yyyy","value":[{data,date,time,dateTime,category_Desc}]}]})
     * -> מערך בפורמט הישן (data, category_desc, alertDate עם שניות, date, time). תשובה שכבר מערך - כמו שהיא
     */
    fun convert(text: String): String {
        if (!text.startsWith("{")) return text
        return try {
            val days = org.json.JSONObject(text).optJSONArray("alarmsGroupedByDate") ?: return "[]"
            val out = JSONArray()
            for (i in 0 until days.length()) {
                val rows = days.getJSONObject(i).optJSONArray("value") ?: continue
                for (j in 0 until rows.length()) {
                    val o = rows.getJSONObject(j)
                    val d = o.optString("date"); val tm = o.optString("time")
                    val m = Regex("""^(\d{2})\.(\d{2})\.(\d{4})$""").find(d)
                    val alertDate = if (m != null && tm.length >= 8) "${m.groupValues[3]}-${m.groupValues[2]}-${m.groupValues[1]}T${tm.take(8)}"
                        else o.optString("dateTime")
                    out.put(org.json.JSONObject().put("data", o.optString("data")).put("category_desc", o.optString("category_Desc"))
                        .put("alertDate", alertDate).put("date", d).put("time", tm))
                }
            }
            out.toString()
        } catch (_: Exception) { "[]" }
    }
}

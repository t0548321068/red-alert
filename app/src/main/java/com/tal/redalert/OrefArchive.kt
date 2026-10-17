package com.tal.redalert

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** כל ההתראות שהיו בארץ - מהארכיון של פיקוד העורף (החודש האחרון) */
object OrefArchive {
    private const val BASE = "https://alerts-history.oref.org.il/Shared/Ajax/GetAlarmsHistory.aspx?lang=he"
    private const val URL_MONTH = "$BASE&mode=3"
    private const val DAY = 24 * 3600 * 1000L

    /**
     * פעם אחת: משלים מהארכיון שנה אחורה (טווחי תאריכים של שבועיים), ושומר בטלפון.
     * מחזיר true אם נוספו התרעות (כדי לרענן את הרשימה)
     */
    fun backfill(c: android.content.Context): Boolean {
        val sp = c.getSharedPreferences("archive", android.content.Context.MODE_PRIVATE)
        if (sp.getBoolean("backfillSecDone", false)) return false   // פעם נוספת - כדי להוסיף שניות להתרעות שכבר נשמרו
        val fmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Jerusalem") }
        val now = System.currentTimeMillis()
        val got = ArrayList<Prefs.Entry>()
        var ok = 0
        var to = now - 28 * DAY   // החודש האחרון כבר מגיע מהבקשה הרגילה
        while (to > now - 366 * DAY) {
            val from = to - 13 * DAY
            try {
                got += fetchRaw("$BASE&fromDate=${fmt.format(java.util.Date(from))}&toDate=${fmt.format(java.util.Date(to))}&mode=0")
                ok++
            } catch (_: Exception) { }
            to = from - DAY
        }
        if (ok == 0) return false   // אין חיבור - ננסה בפעם הבאה
        val merged = LinkedHashMap<String, Prefs.Entry>()
        (got + load(c)).forEach { e -> if (e.ts >= now - 366 * DAY) merged.putIfAbsent(e.title + "|" + e.ts / 60_000, e) }
        save(c, merged.values.sortedByDescending { it.ts })
        sp.edit().putBoolean("backfillSecDone", true).apply()
        return got.isNotEmpty()
    }

    /**
     * ההתרעות של החודש האחרון מהארכיון + מה שנשמר בטלפון מפעמים קודמות - כך הרשימה גדלה עם הזמן
     * ולא נמחקת אחרי חודש (נשמר עד שנה אחורה). בלי חיבור - מה שנשמר.
     */
    fun fetchAndKeep(c: android.content.Context): List<Prefs.Entry> {
        val stored = load(c)
        val fresh = try { fetchRaw() } catch (_: Exception) { emptyList() }
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
            Prefs.Entry("", t, o.optString("b"), AlertService.levelOf(t), o.getLong("ts"))
        }
    } catch (_: Exception) { emptyList() }

    private fun save(c: android.content.Context, list: List<Prefs.Entry>) = try {
        val arr = JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("t", it.title).put("b", it.body).put("l", it.level).put("ts", it.ts)) }
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
        if (!text.startsWith("[")) return emptyList()
        val tz = java.util.TimeZone.getTimeZone("Asia/Jerusalem")
        val fmtSec = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = tz }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).apply { timeZone = tz }
        val arr = JSONArray(text)
        // אותה התראה (סוג + דקה) = שורה אחת עם כל האזורים
        val groups = LinkedHashMap<String, Triple<String, LongArray, MutableList<String>>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.optString("category_desc").ifEmpty { o.optString("title", "התראה") }
            val full = o.optString("alertDate").replace('T', ' ')
            val date = full.take(16)
            // כולל שניות (אם יש); הקיבוץ - לפי דקה, והשעה - השנייה הראשונה בדקה
            val ts = (try { if (full.length >= 19) fmtSec.parse(full.take(19))?.time else null } catch (_: Exception) { null })
                ?: (try { fmt.parse(date)?.time } catch (_: Exception) { null }) ?: continue
            val g = groups.getOrPut("$title|$date") { Triple(title, longArrayOf(ts), mutableListOf()) }
            if (ts < g.second[0]) g.second[0] = ts
            val area = o.optString("data")
            if (area.isNotBlank() && area !in g.third) g.third.add(area)
        }
        val list = groups.values.map { (title, ts, areas) ->
            val level = AlertService.levelOf(title)
            Prefs.Entry("", if (level == AlertService.LEVEL_END) "האירוע הסתיים" else title,
                areas.joinToString(", "), level, ts[0])
        }.sortedByDescending { it.ts }
        return list
    }
}

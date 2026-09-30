package com.tal.redalert

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** כל ההתראות שהיו בארץ - מהארכיון של פיקוד העורף (החודש האחרון) */
object OrefArchive {
    private const val URL_MONTH = "https://alerts-history.oref.org.il/Shared/Ajax/GetAlarmsHistory.aspx?lang=he&mode=3"

    fun fetch(): List<Prefs.Entry> {
        val conn = URL(URL_MONTH + "&t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
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
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Jerusalem")
        }
        val arr = JSONArray(text)
        // אותה התראה (סוג + דקה) = שורה אחת עם כל האזורים
        val groups = LinkedHashMap<String, Triple<String, Long, MutableList<String>>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.optString("category_desc").ifEmpty { o.optString("title", "התראה") }
            val date = o.optString("alertDate").replace('T', ' ').take(16)
            val ts = try { fmt.parse(date)?.time } catch (_: Exception) { null } ?: continue
            val g = groups.getOrPut("$title|$date") { Triple(title, ts, mutableListOf()) }
            val area = o.optString("data")
            if (area.isNotBlank() && area !in g.third) g.third.add(area)
        }
        return groups.values.map { (title, ts, areas) ->
            val level = AlertService.levelOf(title)
            Prefs.Entry("", if (level == AlertService.LEVEL_END) "האירוע הסתיים" else title,
                areas.joinToString(", "), level, ts)
        }.sortedByDescending { it.ts }
    }
}

package com.tal.redalert

import org.json.JSONArray
import org.json.JSONObject

/**
 * הגדרות מהשרת (config.json): סיווג וכותרות ההתרעות, הנחיות לכל סוג, וזמני הבדיקות.
 * עד שהשרת שלח גרסה - הקובץ שבתוך האפליקציה (אותו תוכן).
 */
object Config {
    @Volatile private var cache: JSONObject? = null
    fun reset() { cache = null }

    private fun cfg(): JSONObject = cache ?: synchronized(this) {
        cache ?: (try { DataFiles.app?.let { JSONObject(DataFiles.text(it, "config.json")) } } catch (_: Exception) { null }
            // קובץ שהורד ופגום - חוזרים לזה שבאפליקציה
            ?: try { DataFiles.app?.let { JSONObject(it.assets.open("config.json").bufferedReader().use { r -> r.readText() }) } } catch (_: Exception) { null }
            ?: JSONObject()).also { cache = it }
    }

    private fun list(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map(it::getString) } ?: emptyList()
    private fun titles() = cfg().optJSONObject("titles") ?: JSONObject()

    val endWords get() = list(titles().optJSONArray("end"))
    val preWords get() = list(titles().optJSONArray("pre"))
    val endDisplay: String get() = titles().optString("endDisplay", "האירוע הסתיים")
    /** כותרת שמכילה מילה מהרשימה ולא מתחילה כבר בקידומת - מקבלת אותה ("איום מלבנון" ← "התרעה מקדימה - איום מלבנון") */
    fun prePrefix(t: String): String? {
        val o = titles().optJSONObject("prePrefix") ?: return null
        val prefix = o.optString("prefix")
        if (prefix.isEmpty() || t.startsWith(prefix.trimEnd(' ', '-'))) return null
        return if (list(o.optJSONArray("contains")).any { t.contains(it) }) prefix + t else null
    }

    /** הנחיות: הכלל הראשון שאחת המילים שלו בכותרת; כלל בלי מילים = ברירת המחדל */
    fun guidance(title: String): List<String> {
        val a = cfg().optJSONArray("guidance") ?: return emptyList()
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            val m = list(r.optJSONArray("match"))
            if (m.isEmpty() || m.any { title.contains(it) }) return list(r.optJSONArray("tips"))
        }
        return emptyList()
    }

    private fun minutes(key: String, def: Int) =
        (cfg().optJSONObject("timings")?.optInt(key, def) ?: def).coerceIn(1, 24 * 60) * 60_000L
    val watchdogMs get() = minutes("watchdogMin", 5)
    val watchdogServerMs get() = minutes("watchdogServerMin", 15)
    val serverStaleMs get() = minutes("serverStaleMin", 35)
}

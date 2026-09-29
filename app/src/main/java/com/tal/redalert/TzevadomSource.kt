package com.tal.redalert

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * מקור: tzevadom.com (זרם SSE).
 * פורמט ההודעות לא מתועד, לכן הפענוח זהיר:
 * מתקבלים רק שמות שמופיעים ברשימת האזורים הרשמית של פיקוד העורף.
 */
class TzevadomSource(
    private val ctx: Context,
    private val onAlert: (title: String, areas: List<String>) -> Unit
) {
    companion object {
        private const val SSE_URL = "https://api.tzevadom.com/sse"
        private const val PRE_TITLE = "בדקות הקרובות צפויות להתקבל התרעות באזורך"
        private const val END_TITLE = "האירוע הסתיים"
        private val KNOWN_TITLES = listOf(
            "ירי רקטות וטילים", "חדירת כלי טיס עוין", "חדירת מחבלים",
            "רעידת אדמה", "חשש לצונאמי", "אירוע חומרים מסוכנים",
            "חשש לאירוע רדיולוגי", "חשש לאירוע כימי"
        )
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** כל שמות האזורים הרשמיים (מאותו קובץ של צופר) */
    private val knownAreas: Set<String> by lazy {
        ctx.assets.open("tzofar_ids.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { it.split('\t').getOrNull(1)?.trim() }.toSet()
        }
    }

    @Volatile private var running = false

    fun start() {
        running = true
        Thread(::loop, "tzevadom-sse").start()
    }

    fun stop() { running = false }

    private fun loop() {
        while (running) {
            try { listen() } catch (_: Exception) { }
            if (running) Thread.sleep(5000)
        }
    }

    private fun listen() {
        val req = Request.Builder()
            .url(SSE_URL)
            .header("Accept", "text/event-stream")
            .header("Origin", "https://tzevadom.com")
            .header("Referer", "https://tzevadom.com/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) RedAlert")
            .build()
        try { doListen(req) } finally { SourceHealth.setOpen("tzevadom", false) }
    }

    private fun doListen(req: Request) {
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return
            SourceHealth.setOpen("tzevadom", true)
            val reader = resp.body?.source() ?: return
            val buf = StringBuilder()
            while (running) {
                val line = reader.readUtf8Line() ?: break
                SourceHealth.ok("tzevadom")
                when {
                    line.startsWith("data:") -> buf.append(line.removePrefix("data:").trim())
                    line.isEmpty() && buf.isNotEmpty() -> {
                        try { parse(buf.toString()) } catch (_: Exception) { }
                        buf.clear()
                    }
                }
            }
        }
    }

    private fun parse(payload: String) {
        val root: Any = when {
            payload.startsWith("{") -> JSONObject(payload)
            payload.startsWith("[") -> JSONArray(payload)
            else -> return
        }
        val strings = mutableListOf<String>()
        collect(root, strings)
        val text = strings.joinToString(" ")

        // תרגיל - מתעלמים
        if (text.contains("תרגיל") || payload.contains("\"isDrill\":true")) return

        val areas = strings.map { it.trim() }.filter { it in knownAreas }.distinct()
        if (areas.isEmpty()) return

        val title = when {
            text.contains("הסתיים") -> END_TITLE
            text.contains("בדקות הקרובות") -> PRE_TITLE
            else -> KNOWN_TITLES.firstOrNull { text.contains(it) } ?: "ירי רקטות וטילים"
        }
        onAlert(title, areas)
    }

    /** אוסף את כל המחרוזות מתוך ה-JSON (כולל מערכים מקוננים) */
    private fun collect(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> node.keys().forEach { collect(node.opt(it), out) }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
            is String -> {
                out.add(node)
                // לפעמים ערים מגיעות כמחרוזת אחת מופרדת בפסיקים
                if (node.contains(",")) node.split(",").forEach { out.add(it) }
            }
        }
    }
}

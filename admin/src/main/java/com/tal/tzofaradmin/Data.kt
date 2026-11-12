package com.tal.tzofaradmin

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * הנתונים של צופר חירום, כמו שהטלפונים מקבלים אותם: קובץ שיש בשרת קודם לקובץ שבתוך האפליקציה.
 * הקלטות היישובים (voice/areas) נמצאות רק בשרת.
 */
object Data {
    const val SERVER = "http://151.145.91.93:8080/data"

    class Area(val name: String, val district: String?, val shelter: Int?, val voice: String?, val voiceOk: Boolean) {
        val missing get() = !voiceOk || district == null || shelter == null
    }
    class Clip(val title: String, val path: String)

    @Volatile var areas: List<Area> = emptyList(); private set
    @Volatile var orphans: List<Clip> = emptyList(); private set   // הקלטה בלי יישוב
    @Volatile var phrases: List<Clip> = emptyList(); private set
    @Volatile var sounds: List<Clip> = emptyList(); private set
    @Volatile var serverOk = false; private set
    @Volatile var loadedAt = 0L; private set
    private var serverFiles: Set<String> = emptySet()

    /** כתובת להשמעה מהשרת, או null = מתוך האפליקציה */
    fun url(path: String): String? = if (path in serverFiles) "$SERVER/" + path.split('/').joinToString("/") {
        URLEncoder.encode(it, "UTF-8").replace("+", "%20") } else null

    private fun get(path: String): ByteArray? {
        val c = URL(if (path.isEmpty()) SERVER else "$SERVER/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 20000
        return try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null }
        catch (_: Exception) { null } finally { c.disconnect() }
    }

    private fun assetText(c: Context, p: String) = c.assets.open(p).bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun assetExists(c: Context, p: String) = try { c.assets.open(p).close(); true } catch (_: Exception) { false }
    private fun text(c: Context, p: String): String =
        (if (p in serverFiles) get(p)?.toString(Charsets.UTF_8) else null) ?: assetText(c, p)

    fun load(c: Context) {
        val idx = get("")?.let { try { JSONObject(it.toString(Charsets.UTF_8)) } catch (_: Exception) { null } }
        val voice = if (idx != null) get("voice.json")?.let { try { JSONObject(it.toString(Charsets.UTF_8)) } catch (_: Exception) { null } } else null
        serverOk = idx != null && voice != null
        serverFiles = buildSet {
            idx?.optJSONObject("files")?.keys()?.forEach { add(it) }
            voice?.keys()?.forEach { add(it) }
        }

        val js = text(c, "areas.js")
        val all = JSONObject(js.substring(js.indexOf('{'), js.lastIndexOf('}') + 1))
        val districts = JSONObject(text(c, "districts.json"))
        val migun = text(c, "migun.txt").lines().mapNotNull { l ->
            val p = l.split('\t'); if (p.size >= 2) p[1].trim().toIntOrNull()?.let { p[0].trim() to it } else null
        }.toMap()
        val vIdx = JSONObject(text(c, "voice/index.json"))

        fun voiceOk(rel: String?): Boolean {
            if (rel == null) return false
            val p = "voice/$rel"
            return p in serverFiles || assetExists(c, p)
        }
        areas = all.keys().asSequence().map { n ->
            val rel = vIdx.optString(n).ifEmpty { null }
            Area(n, districts.optString(n).ifEmpty { null },
                migun[n] ?: all.optJSONObject(n)?.takeIf { it.has("t") }?.optInt("t"),
                rel?.let { "voice/$it" }, voiceOk(rel))
        }.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale("he")), { it.name })).toList()
        orphans = vIdx.keys().asSequence().filter { !all.has(it) }.map { Clip(it, "voice/" + vIdx.getString(it)) }.toList()

        phrases = listOf("phrases", "extra").flatMap { d ->
            (c.assets.list("voice/$d") ?: emptyArray()).sorted().map { Clip(PHRASES[it.removeSuffix(".mp3")] ?: it, "voice/$d/$it") }
        }
        sounds = (c.assets.list("sounds") ?: emptyArray()).sorted().map {
            Clip(SOUNDS[it.substringBeforeLast('.')] ?: it, "sounds/$it")
        }
        loadedAt = System.currentTimeMillis()
    }

    private val SOUNDS = mapOf("app_siren" to "צופר", "app_beep" to "צפצוף")
    private val PHRASES = mapOf(
        "red_alert" to "צבע אדום", "rockets" to "ירי רקטות וטילים", "hostile_aircraft" to "חדירת כלי טיס עוין",
        "terrorist_infiltration" to "חדירת מחבלים", "earthquake" to "רעידת אדמה", "tsunami" to "צונאמי",
        "hazardous_materials" to "חומרים מסוכנים", "radiological" to "אירוע רדיולוגי", "nonconventional" to "אירוע לא קונבנציונלי",
        "early_warning" to "התרעה מקדימה", "event_ended" to "האירוע הסתיים", "event_end" to "סיום אירוע",
        "more_areas" to "ואזורים נוספים", "time_immediate" to "זמן להגעה: מיידי", "time_15s" to "זמן להגעה: 15 שניות",
        "time_30s" to "זמן להגעה: 30 שניות", "time_45s" to "זמן להגעה: 45 שניות", "time_1min" to "זמן להגעה: דקה",
        "time_1_5min" to "זמן להגעה: דקה וחצי", "in_areas" to "באזורים", "launches_detected" to "זוהו שיגורים",
        "mivzak_pikud_haoref" to "מבזק פיקוד העורף", "update_pikud_haoref" to "עדכון פיקוד העורף", "siren" to "צופר (הקלטה)",
    )
}

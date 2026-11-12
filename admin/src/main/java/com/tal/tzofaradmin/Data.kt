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
    class Clip(val title: String, val path: String, val group: String = "")

    @Volatile var areas: List<Area> = emptyList(); private set
    /** אזור (מחוז) → היישובים שבו, לפי הא"ב */
    @Volatile var districts: Map<String, List<Area>> = emptyMap(); private set
    @Volatile var orphans: List<Clip> = emptyList(); private set   // הקלטה בלי יישוב
    @Volatile var phrases: List<Clip> = emptyList(); private set
    @Volatile var sounds: List<Clip> = emptyList(); private set
    @Volatile var serverOk = false; private set
    @Volatile var loadedAt = 0L; private set
    private var serverFiles: Set<String> = emptySet()
    private var byName: Map<String, Area> = emptyMap()
    private val NATIONAL = setOf("כל הארץ", "ברחבי הארץ")
    private const val TEST = "התראת בדיקה"
    @Volatile private var national: List<Area> = emptyList()
    private var endWords = listOf<String>(); private var preWords = listOf<String>(); private var updateWords = listOf<String>()
    private var endDisplay = "האירוע הסתיים"
    private var prefix = ""; private var prefixWords = listOf<String>()
    private const val LEVEL_ALERT = 0; private const val LEVEL_PRE = 1; private const val LEVEL_END = 2

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
        val allAreas = all.keys().asSequence().map { n ->
            val rel = vIdx.optString(n).ifEmpty { null }
            Area(n, districts.optString(n).ifEmpty { null },
                migun[n] ?: all.optJSONObject(n)?.takeIf { it.has("t") }?.optInt("t"),
                rel?.let { "voice/$it" }, voiceOk(rel))
        }.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale("he")), { it.name })).toList()
        // "כל הארץ" / "ברחבי הארץ" - לא יישובים: מופיעים במשפטי ההקראה (אחר), ועדיין נמצאים בהתרעות
        areas = allAreas.filter { it.name !in NATIONAL }
        national = allAreas.filter { it.name in NATIONAL }
        // "התראת בדיקה" - הקלטה בלי יישוב בכוונה: גם היא במשפטי ההקראה (אחר)
        val test = vIdx.optString(TEST).ifEmpty { null }?.let { Clip(TEST, "voice/$it", "אחר") }
        orphans = vIdx.keys().asSequence().filter { !all.has(it) && it != TEST }.map { Clip(it, "voice/" + vIdx.getString(it)) }.toList()

        phrases = listOf("phrases", "extra").flatMap { d ->
            (c.assets.list("voice/$d") ?: emptyArray()).sorted().map {
                val k = it.removeSuffix(".mp3")
                Clip(PHRASES[k] ?: it, "voice/$d/$it", GROUPS.firstOrNull { g -> k in g.second }?.first ?: "אחר")
            }
        }.plus(national.filter { it.voice != null }.map { Clip(it.name, it.voice!!, "אחר") }).plus(listOfNotNull(test))
        .sortedWith(compareBy({ c -> GROUPS.indexOfFirst { it.first == c.group }.let { if (it < 0) GROUPS.size else it } },
            { c -> GROUPS.flatMap { it.second }.indexOf(c.path.substringAfterLast('/').removeSuffix(".mp3")) }))
        sounds = (c.assets.list("sounds") ?: emptyArray()).sorted().map {
            Clip(SOUNDS[it.substringBeforeLast('.')] ?: it, "sounds/$it")
        }
        byName = allAreas.associateBy { it.name }
        val col = java.text.Collator.getInstance(java.util.Locale("he"))
        this.districts = areas.filter { it.district != null }.groupBy { it.district!! }.toSortedMap(col)
        try {
            val t = JSONObject(text(c, "config.json")).getJSONObject("titles")
            fun l(k: String) = t.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            endWords = l("end"); preWords = l("pre"); updateWords = l("update"); endDisplay = t.optString("endDisplay", endDisplay)
            t.optJSONObject("prePrefix")?.let { o -> prefix = o.optString("prefix")
                prefixWords = o.optJSONArray("contains")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList() }
        } catch (_: Exception) { }
        loadedAt = System.currentTimeMillis()
    }

    // ---- היסטוריה: ההתרעות שהיו (מהארכיון של השרת), וההקראה שלהן כמו באפליקציה ----
    class Alert(val title: String, val raw: String, val ts: Long, val areas: List<String>)

    fun history(days: Int): List<Alert>? {
        val since = System.currentTimeMillis() - days * 24 * 3600 * 1000L
        val c = URL("http://151.145.91.93:8080/archive?since=$since").openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 30000; c.useCaches = false
        return try {
            if (c.responseCode != 200) null else {
                val arr = org.json.JSONArray(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i); val t = o.getString("t")
                    Alert(displayTitle(t), t, o.getLong("ts"), o.optString("b").split(",").map { it.trim() }.filter { it.isNotEmpty() })
                }.sortedByDescending { it.ts }
            }
        } catch (_: Exception) { null } finally { c.disconnect() }
    }

    fun isUpdate(t: String) = updateWords.any { t.contains(it) }
    /** 0 ירי · 1 מקדימה · 2 סיום */
    fun level(a: Alert) = levelOf(a.raw)
    /** זמן ההתגוננות הקצר ביותר מבין היישובים בהתרעה */
    fun shelterOf(a: Alert): Int? = a.areas.flatMap { match(it) }.mapNotNull { it.shelter }.minOrNull()
    /** ההתרעה שמוצגת עכשיו בהדמיה */
    @Volatile var simAlert: Alert? = null
    private fun levelOf(t: String) = when {
        endWords.any { t.contains(it) } || isUpdate(t) -> LEVEL_END
        preWords.any { t.contains(it) } -> LEVEL_PRE
        else -> LEVEL_ALERT
    }
    private fun displayTitle(raw: String): String {
        val t = raw.trim()
        return if (levelOf(t) == LEVEL_END && !isUpdate(t)) endDisplay
            else if (prefix.isNotEmpty() && !t.startsWith(prefix.trimEnd(' ', '-')) && prefixWords.any { t.contains(it) }) prefix + t else t
    }

    /** השם שהגיע מהמקור → היישובים במפה (כמו באפליקציה: "יבנה" לא תופס את "גן יבנה") */
    fun match(raw: String): List<Area> = byName[raw]?.let { listOf(it) }
        ?: byName.values.filter { it.name.startsWith("$raw -") || it.name.startsWith("$raw,") }

    /** ההקראה של התרעה, כמו באפליקציה: סוג → עד 4 יישובים (ואם יש עוד - "ואזורים נוספים") → זמן להגעה */
    fun clips(a: Alert): List<String>? {
        // עדכון (כמו "רעידת אדמה - הנחיות חזרה למבנים"): "עדכון פיקוד העורף. סיום אירוע. האירוע הסתיים. באזורים: ..."
        if (isUpdate(a.raw)) {
            val files = a.areas.mapNotNull { r -> match(r).firstOrNull { it.voiceOk }?.voice }.distinct()
            val shown = if (files.size <= 5) files else files.take(4)
            return listOf("voice/extra/update_pikud_haoref.mp3", "voice/extra/event_end.mp3", "voice/extra/event_ended.mp3",
                "voice/extra/in_areas.mp3") + shown + (if (files.size > shown.size) listOf("voice/phrases/more_areas.mp3") else emptyList())
        }
        val level = levelOf(a.raw); val t = a.raw
        val type = when {
            level == LEVEL_END -> "event_ended"; level == LEVEL_PRE -> "early_warning"
            t.contains("טילים") || t.contains("רקטות") -> "rockets"; t.contains("כלי טיס") -> "hostile_aircraft"
            t.contains("מחבלים") -> "terrorist_infiltration"; t.contains("רעידת") -> "earthquake"
            t.contains("צונאמי") -> "tsunami"; t.contains("חומרים מסוכנים") -> "hazardous_materials"
            t.contains("רדיולוג") -> "radiological"; t.contains("קונבנציונלי") -> "nonconventional"
            else -> "red_alert"
        }
        val out = mutableListOf("voice/phrases/$type.mp3")
        val files = a.areas.mapNotNull { r -> match(r).firstOrNull { it.voiceOk }?.voice }.distinct()
        val shown = if (files.size <= 5) files else files.take(4)
        out += shown
        if (files.size > shown.size) out += "voice/phrases/more_areas.mp3"
        val shelter = a.areas.flatMap { match(it) }.mapNotNull { it.shelter }.minOrNull()
        if (level == LEVEL_ALERT && shelter != null) {
            val s = listOf(0, 15, 30, 45, 60, 90).minByOrNull { Math.abs(it - shelter) }
            out += "voice/phrases/" + when (s) { 0 -> "time_immediate"; 15 -> "time_15s"; 30 -> "time_30s"
                45 -> "time_45s"; 60 -> "time_1min"; else -> "time_1_5min" } + ".mp3"
        }
        return out
    }

    /** קטגוריות במשפטי ההקראה, לפי הסדר */
    val GROUPS = listOf(
        "סוג ההתרעה" to listOf("red_alert", "early_warning", "event_ended", "event_end", "launches_detected", "mivzak_pikud_haoref", "update_pikud_haoref"),
        "סוג האירוע" to listOf("rockets", "hostile_aircraft", "terrorist_infiltration", "earthquake", "tsunami", "hazardous_materials", "radiological", "nonconventional"),
        "זמן התגוננות" to listOf("time_immediate", "time_15s", "time_30s", "time_45s", "time_1min", "time_1_5min"),
        "אחר" to listOf("all_of_israel", "all_over_the_country", "test_alert", "in_areas", "more_areas", "siren"),
    )

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

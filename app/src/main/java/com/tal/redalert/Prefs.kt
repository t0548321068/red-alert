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
    fun blinkColon(c: Context) = sp(c).getBoolean("blink_colon", false)
    fun setBlinkColon(c: Context, v: Boolean) = sp(c).edit().putBoolean("blink_colon", v).commit()
    fun showDate(c: Context) = sp(c).getBoolean("date", true)
    fun setShowDate(c: Context, v: Boolean) = sp(c).edit().putBoolean("date", v).commit()
    /** ברכה לפי השעה (בוקר טוב / ערב טוב...) */
    fun showGreeting(c: Context) = sp(c).getBoolean("greeting", true)
    fun setShowGreeting(c: Context, v: Boolean) = sp(c).edit().putBoolean("greeting", v).commit()
    fun showWeather(c: Context) = sp(c).getBoolean("weather", true)
    /** כל כמה דקות לרענן את מזג האוויר והמיקום שלו */
    fun weatherMinutes(c: Context) = sp(c).getInt("weatherMin", 2)
    fun setWeatherMinutes(c: Context, v: Int) = sp(c).edit().putInt("weatherMin", v).commit()
    fun setShowWeather(c: Context, v: Boolean) = sp(c).edit().putBoolean("weather", v).commit()

    /** הגרסה האחרונה שהמשתמש ראה עליה "מה חדש" */
    fun seenVersion(c: Context) = sp(c).getString("seenVer", "") ?: ""
    fun setSeenVersion(c: Context, v: String) = sp(c).edit().putString("seenVer", v).commit()

    // ---- פיד ארצי: כל ההתראות בארץ, בלי קשר לאזורים שלי ----
    private const val MAX_FEED = 600   // מספיק לשבוע במפה

    fun feed(c: Context): List<Entry> {
        val arr = try { JSONArray(sp(c).getString("feed", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry("", o.optString("title"), o.optString("body"), o.optInt("level"), o.optLong("ts"))
        }.map { clean(c, it) }.sortedByDescending { it.ts }.let { collapse(it) }
    }

    /**
     * ניקוי רשומות ישנות: "הוסר החשש" = סיום אירוע, ובלי שורות זבל מטלגרם
     * (שמות ערוצים, תאריכים, "היישובים הבאים")
     */
    private fun clean(c: Context, e: Entry): Entry {
        var x = e
        // כל סוגי הסיום ("החשש הוסר", "ניתן לצאת"...) = "האירוע הסתיים"
        if (e.level == 2 || AlertService.levelOf(e.title) == 2 ||
            e.body.contains("הוסר החשש") || e.body.contains("החשש הוסר"))
            x = x.copy(title = "האירוע הסתיים", level = 2)
        val parts = x.body.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val real = parts.filter { AreaData.isKnown(c, it) }
        return if (real.isNotEmpty() && real.size < parts.size) x.copy(body = real.joinToString(", ")) else x
    }

    /** מפתח ליישוב - זהה בכל המקורות: "קריית שמונה" = "קרית שמונה", "תל אביב - מרכז" = "תל-אביב" */
    fun areaKey(a: String) = a.split(" - ")[0].filter { it.isLetterOrDigit() && it != 'י' && it != 'ו' }

    /** חלון זמן שבו אותה התראה מכמה מקורות נחשבת כפילות */
    const val DUP_MS = 5 * 60 * 1000L
    const val END_MERGE_MS = 30 * 60 * 1000L

    /**
     * איחוד כפילויות לתצוגה: אותו סוג, עם יישוב משותף, בהפרש של עד 5 דקות = שורה אחת.
     * גם התראות מאותו סוג בהפרש של פחות מדקה וחצי = אותו אירוע.
     * נשארים הזמן, הכותרת והמקור של מי שהגיע ראשון.
     */
    fun collapse(list: List<Entry>): List<Entry> {
        val out = ArrayList<Entry>()
        val keys = ArrayList<MutableSet<String>>()
        for (e in list) {           // מהחדשה לישנה
            val ek = e.body.split(",").map { areaKey(it.trim()) }.filter { it.isNotEmpty() }.toSet()
            // סיום אירוע: כל הסיומים הרצופים (בלי התראה ביניהם, עד חצי שעה) = שורה אחת
            val last = out.lastOrNull()
            val i = if (e.level == 2 && last != null && last.level == 2 && e.ts > 0 &&
                last.ts - e.ts in 0 until END_MERGE_MS) out.lastIndex
            else out.indices.firstOrNull { j ->
                val k = out[j]
                k.level == e.level && e.ts > 0 && k.ts - e.ts in 0 until DUP_MS &&
                    (k.ts - e.ts < 90_000L || k.level == 2 || keys[j].any { it in ek })   // סיום אירוע - תמיד אחד
            }
            if (i == null) { out.add(e.copy(body = mergeBody(e.body, emptyList()))); keys.add(ek.toMutableSet()); continue }
            out[i] = e.copy(body = mergeBody(e.body, out[i].body.split(", ")))
            keys[i].addAll(ek)
        }
        return out.sortedByDescending { it.ts }   // תמיד מהחדשה לישנה
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

    // ---- התרעות לפי מיקום: התראה גם לפי המיקום הנוכחי ----
    fun nearMe(c: Context) = sp(c).getBoolean("nearMe", true)   // ברירת מחדל: התראות לפי מיקום
    fun setNearMe(c: Context, v: Boolean) = sp(c).edit().putBoolean("nearMe", v).commit()
    /** מרחק מקסימלי ל"התרעות לפי מיקום" בק"מ (0 = רק האזור שאני בתוכו). ברירת מחדל 1 ק"מ כמו עד עכשיו */
    fun nearRadiusKm(c: Context) = sp(c).getFloat("nearRadius", 1f)
    fun setNearRadiusKm(c: Context, v: Float) = sp(c).edit().putFloat("nearRadius", v).commit()
    val NEAR_RADII = FloatArray(11) { it.toFloat() }   // 11 נקודות: באזורך, 1 עד 10 ק"מ
    fun radiusLabel(km: Float) = when {
        km <= 0f -> "באזורך"
        km < 1f -> "${(km * 1000).toInt()} מטר"
        km == 1f -> "1 ק\"מ"
        else -> "${km.toInt()} ק\"מ"
    }
    /** המיקום האחרון שלפיו חושבו האזורים - לחישוב מחדש מיד כשמשנים מרחק */
    fun lastNearLoc(c: Context): Pair<Double, Double>? {
        val v = sp(c).getString("nearLoc", "") ?: ""
        val p = v.split(",").mapNotNull { it.toDoubleOrNull() }
        return if (p.size == 2) p[0] to p[1] else null
    }
    fun setLastNearLoc(c: Context, lat: Double, lon: Double) =
        sp(c).edit().putString("nearLoc", "$lat,$lon").apply()

    /** האזורים שזוהו לאחרונה סביב המיקום (לתצוגה ולסינון) */
    fun nearbyAreas(c: Context): List<String> =
        (sp(c).getString("nearby", "") ?: "").split("|").filter { it.isNotEmpty() }
    fun setNearbyAreas(c: Context, v: List<String>) =
        sp(c).edit().putString("nearby", v.joinToString("|")).commit()

    // ---- היסטוריית מיקום: באילו אזורי התראה הייתי ומתי (לטאב "שלי" במפה) ----
    // אזור -> רשימת קטעי זמן [התחלה, סוף]. נשמר שבוע אחורה.
    fun visits(c: Context): Map<String, List<LongArray>> {
        val o = try { JSONObject(sp(c).getString("visits2", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val m = HashMap<String, List<LongArray>>()
        o.keys().forEach { k ->
            val a = o.optJSONArray(k) ?: return@forEach
            m[k] = (0 until a.length()).map { i -> a.getJSONArray(i).let { longArrayOf(it.getLong(0), it.getLong(1)) } }
        }
        return m
    }
    @Synchronized
    fun addVisits(c: Context, zones: List<String>, ts: Long) {
        if (zones.isEmpty()) return
        val week = 8L * 24 * 3600 * 1000
        val m = visits(c).mapValues { (_, l) -> l.filter { ts - it[1] < week }.toMutableList() }.toMutableMap()
        zones.forEach { z ->
            val l = m.getOrPut(z) { mutableListOf() }
            val last = l.lastOrNull()
            // עדכון כל 2 דקות: אם הייתי כאן עד לפני 6 דקות - אותו ביקור, אחרת ביקור חדש
            if (last != null && ts - last[1] <= 6 * 60 * 1000) last[1] = ts else l.add(longArrayOf(ts, ts))
        }
        val o = JSONObject()
        m.forEach { (k, l) -> if (l.isNotEmpty()) o.put(k, JSONArray().apply { l.forEach { put(JSONArray().put(it[0]).put(it[1])) } }) }
        sp(c).edit().putString("visits2", o.toString()).apply()
    }

    /** האם הייתי באזור הזה בזמן הזה (עם מרווח של 3 דקות) */
    fun wasIn(v: Map<String, List<LongArray>>, zone: String, ts: Long): Boolean =
        v[zone]?.any { ts >= it[0] - 3 * 60 * 1000 && ts <= it[1] + 3 * 60 * 1000 } == true

    // ---- צליל לכל סוג התראה (ריק = ברירת מחדל, "silent" = בלי צליל) ----
    fun sound(c: Context, level: Int) = sp(c).getString("sound$level", "") ?: ""
    fun setSound(c: Context, level: Int, uri: String) = sp(c).edit().putString("sound$level", uri).commit()

    // ---- מתג כללי לצלילים ולרטט של ההתרעות (ברירת מחדל: פעיל) ----
    fun soundsOn(c: Context) = sp(c).getBoolean("soundsOn", true)
    fun setSoundsOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("soundsOn", v).commit()
    fun vibesOn(c: Context) = sp(c).getBoolean("vibesOn", true)
    fun setVibesOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("vibesOn", v).commit()

    /** עקיפת מצב שקט: כשהטלפון על שקט/רטט - ההתרעה בכל זאת משמיעה צליל (ברירת מחדל: כבוי) */
    fun bypassSilent(c: Context) = sp(c).getBoolean("bypassSilent", false)
    fun setBypassSilent(c: Context, v: Boolean) = sp(c).edit().putBoolean("bypassSilent", v).commit()

    // ---- הקראה בקול ----
    fun speakAlerts(c: Context) = sp(c).getBoolean("speak", true)
    fun shabbatMode(c: Context) = sp(c).getInt("shabbatMode", Shabbat.OFF)
    fun setShabbatMode(c: Context, v: Int) = sp(c).edit().putInt("shabbatMode", v).commit()
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
    /** ערכת צבעים: 0 כהה, 1 בהירה, 2 לפי המערכת */
    fun themeMode(c: Context) = sp(c).getInt("themeMode", if (sp(c).getBoolean("dark", true)) 0 else 1)
    fun setThemeMode(c: Context, v: Int) = sp(c).edit().putInt("themeMode", v).commit()
    val THEME_NAMES = arrayOf("כהה", "בהירה", "לפי המערכת")

    /** סוג תצוגת התרעה: 0 מסך מלא · 1 פופ-אפ · 2 כרטיס מעל השעון · 3 ממוזער */
    val ALERT_STYLES = arrayOf("מסך מלא", "פופ-אפ", "כרטיס מעל השעון", "ממוזער")
    fun alertStyle(c: Context) = sp(c).getInt("alertStyle", 0)
    /** מפה במסך ההתרעה (מסך מלא ופופ-אפ) */
    fun alertMap(c: Context) = sp(c).getBoolean("alertMap", true)
    fun setAlertMap(c: Context, v: Boolean) = sp(c).edit().putBoolean("alertMap", v).apply()
    fun setAlertStyle(c: Context, v: Int) = sp(c).edit().putInt("alertStyle", v).apply()

    /** ההתרעה הפעילה (לכרטיס מעל השעון): title, body, level, shelter, firedAt, source */
    fun setActiveAlert(c: Context, j: org.json.JSONObject?) =
        sp(c).edit().putString("activeAlert", j?.toString() ?: "").apply()
    /** ההתרעה שהכרטיס שלה הוסתר ב-✕ (לפי זמן הירי) */
    fun alertDismissed(c: Context) = sp(c).getLong("alertDismissed", 0L)
    fun setAlertDismissed(c: Context, firedAt: Long) = sp(c).edit().putLong("alertDismissed", firedAt).apply()
    fun activeAlert(c: Context): org.json.JSONObject? =
        try { sp(c).getString("activeAlert", "")?.takeIf { it.isNotEmpty() }?.let { org.json.JSONObject(it) } } catch (_: Exception) { null }
    fun darkTheme(c: Context) = when (themeMode(c)) {
        1 -> false
        2 -> (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        else -> true
    }
    fun setDarkTheme(c: Context, v: Boolean) = setThemeMode(c, if (v) 0 else 1)
    fun dialogTheme(c: Context) =
        if (darkTheme(c)) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    /** אפשרות הבטא נפתחה (7 לחיצות על מספר הגרסה) */
    /**
     * בטא רק למכשירים שברשימה (לפי מזהה המכשיר). רשימה ריקה = בלי הגבלה.
     * בכל מכשיר אחר הבטא כבויה ומוסתרת, גם אם הופעלה בעבר.
     */
    private val BETA_DEVICES = setOf("1F31D43C")   // הטלפון של טל

    /** מזהה קצר וקבוע של המכשיר (8 תווים) */
    fun deviceCode(c: Context): String {
        @android.annotation.SuppressLint("HardwareIds")
        val id = android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        return h.take(4).joinToString("") { "%02X".format(it) }
    }

    fun betaAllowed(c: Context) = BETA_DEVICES.isEmpty() || deviceCode(c).uppercase() in BETA_DEVICES

    fun betaUnlocked(c: Context) = sp(c).getBoolean("betaUnlocked", false) && betaAllowed(c)
    fun setBetaUnlocked(c: Context, v: Boolean) = sp(c).edit().putBoolean("betaUnlocked", v).commit()

    /** קבלת גרסאות בטא (לפני שהן משוחררות לכולם) */
    fun betaUpdates(c: Context) = sp(c).getBoolean("beta", false) && betaAllowed(c)
    fun setBetaUpdates(c: Context, v: Boolean) = sp(c).edit().putBoolean("beta", v).commit()

    /** גרסה שנבחר עבורה "מאוחר יותר" - לא מקפיצים עליה שוב */
    fun skippedVersion(c: Context) = sp(c).getString("skipVer", "") ?: ""
    fun setSkippedVersion(c: Context, v: String) = sp(c).edit().putString("skipVer", v).commit()
    /** גרסה שכבר נשלחה עליה התראה ברקע */
    fun notifiedVersion(c: Context) = sp(c).getString("notifVer", "") ?: ""
    fun setNotifiedVersion(c: Context, v: String) = sp(c).edit().putString("notifVer", v).commit()

    data class Entry(val time: String, val title: String, val body: String, val level: Int, val ts: Long = 0,
                     val src: String = "")

    /** היסטוריית התראות, החדשה ראשונה */
    fun history(c: Context): List<Entry> {
        val arr = try { JSONArray(sp(c).getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.optString("time"), o.optString("title"), o.optString("body"),
                o.optInt("level"), o.optLong("ts"), o.optString("src"))
        }.filter { it.body != "התראת בדיקה" }   // בדיקות מגרסאות קודמות לא מוצגות
            .map { clean(c, it) }
            .sortedByDescending { it.ts }
            .let { collapse(it) }
    }

    /** מוסיף יישובים לשורת ההתראה האחרונה (בלי כפילויות) */
    private fun mergeBody(body: String, add: List<String>): String {
        fun k(a: String) = areaKey(a)
        val keys = mutableSetOf<String>()
        // גם בתוך אותה שורה - כל יישוב פעם אחת
        val cur = body.split(",").map { it.trim() }.filter { it.isNotEmpty() && keys.add(k(it)) }
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

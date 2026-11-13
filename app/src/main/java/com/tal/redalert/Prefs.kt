package com.tal.redalert

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val FILE = "prefs"
    private const val MAX_HISTORY = 300   // מספיק לשבוע אחורה במפה

    // הגדרות נשמרות מיד לדיסק (commit) - שלא ילכו לאיבוד אם האפליקציה נסגרת לעדכון
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    /** האזנה לשינוי בהגדרות (למשל התרעה פעילה חדשה) - גם כשהמסך הראשי ברקע */
    fun listen(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = sp(c).registerOnSharedPreferenceChangeListener(l)
    fun unlisten(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = sp(c).unregisterOnSharedPreferenceChangeListener(l)

    /**
     * יישובים/אזורים שנבחרו. נשמר כמערך JSON - יש שמות עם פסיק בתוכם ("אשדוד - א,ב,ד,ה"),
     * ושמירה מופרדת בפסיקים פירקה אותם ואיבדה יישובים. ערך ישן (מופרד בפסיקים) עדיין נקרא
     */
    fun cities(c: Context): List<String> {
        val raw = sp(c).getString("cities", "") ?: ""
        if (raw.startsWith("[")) return try {
            val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) }.filter { it.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setCities(c: Context, list: List<String>) =
        sp(c).edit().putString("cities", JSONArray(list.distinct()).toString()).commit()

    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    /** מצב הכפתור הצף: כמו שורת החיוויים (קבוע / לא מוצג / לפי זמן). מגרסה קודמת: מתג מופעל/כבוי */
    fun testFabMode(c: Context) = sp(c).getInt("testFabMode", if (sp(c).getBoolean("testFab", true)) STATUS_ALWAYS else STATUS_HIDDEN)
    fun setTestFabMode(c: Context, v: Int) = sp(c).edit().putInt("testFabMode", v).apply()
    fun testFabSeconds(c: Context) = sp(c).getInt("testFabSec", 10)
    fun setTestFabSeconds(c: Context, v: Int) = sp(c).edit().putInt("testFabSec", v).apply()
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
    /** סוג תאריך: 0 = לועזי, 1 = עברי, 2 = שניהם */
    fun dateKind(c: Context) = sp(c).getInt("dateKind", 0)
    fun setDateKind(c: Context, v: Int) = sp(c).edit().putInt("dateKind", v).commit()
    /** ברכה לפי השעה (בוקר טוב / ערב טוב...) */
    fun showGreeting(c: Context) = sp(c).getBoolean("greeting", true)
    fun setShowGreeting(c: Context, v: Boolean) = sp(c).edit().putBoolean("greeting", v).commit()
    /** שם שמופיע אחרי הברכה ("ערב טוב טל"); ריק = בלי שם */
    fun greetingName(c: Context) = sp(c).getString("greetName", "") ?: ""
    fun setGreetingName(c: Context, v: String) = sp(c).edit().putString("greetName", v.trim().take(20)).commit()
    fun showWeather(c: Context) = sp(c).getBoolean("weather", true)
    /** דיוק המיקום האחרון שמוצג בחיווי (מטרים), מתי, ואיפה - נשמר בין פתיחות כדי שהחיווי לא יקפוץ */
    fun lastAcc(c: Context): Triple<Int, Long, Pair<Double, Double>> = Triple(sp(c).getInt("accM", -1), sp(c).getLong("accAt", 0L),
        sp(c).getFloat("accLat", 0f).toDouble() to sp(c).getFloat("accLon", 0f).toDouble())
    fun setLastAcc(c: Context, m: Int, lat: Double, lon: Double) = sp(c).edit().putInt("accM", m)
        .putLong("accAt", System.currentTimeMillis()).putFloat("accLat", lat.toFloat()).putFloat("accLon", lon.toFloat()).apply()
    /** שורת החיוויים במסך הראשי: 0 = מוצגת קבוע, 1 = לא מוצגת, 2 = כמה שניות אחרי פתיחה / נגיעה */
    const val STATUS_ALWAYS = 0; const val STATUS_HIDDEN = 1; const val STATUS_TIMED = 2
    fun statusMode(c: Context) = sp(c).getInt("statusMode", STATUS_ALWAYS)
    fun setStatusMode(c: Context, v: Int) = sp(c).edit().putInt("statusMode", v).apply()
    fun statusSeconds(c: Context) = sp(c).getInt("statusSec", 10)
    fun setStatusSeconds(c: Context, v: Int) = sp(c).edit().putInt("statusSec", v).apply()
    /** כל כמה דקות לרענן את מזג האוויר והמיקום שלו */
    fun weatherMinutes(c: Context) = sp(c).getInt("weatherMin", 10)
    fun setWeatherMinutes(c: Context, v: Int) = sp(c).edit().putInt("weatherMin", v).commit()
    fun setShowWeather(c: Context, v: Boolean) = sp(c).edit().putBoolean("weather", v).commit()
    /** פיד ארצי במסך הראשי: כמה דקות אחורה להציג (30 דקות עד 24 שעות) */
    fun feedMinutes(c: Context) = sp(c).getInt("feedMin", 30)
    fun setFeedMinutes(c: Context, v: Int) = sp(c).edit().putInt("feedMin", v).commit()
    /** פיד ארצי מוצג תמיד - גם כשאין התראות בטווח הזמן */
    fun feedAlways(c: Context) = sp(c).getBoolean("feedAlways", false)
    fun setFeedAlways(c: Context, v: Boolean) = sp(c).edit().putBoolean("feedAlways", v).commit()
    /** התראה כשנכנסת התרעה לפיד הארצי */
    fun feedNotify(c: Context) = sp(c).getBoolean("feedNotify", false)
    fun setFeedNotify(c: Context, v: Boolean) = sp(c).edit().putBoolean("feedNotify", v).commit()

    /** הגרסה האחרונה שהמשתמש ראה עליה "מה חדש" */
    fun seenVersion(c: Context) = sp(c).getString("seenVer", "") ?: ""
    fun setSeenVersion(c: Context, v: String) = sp(c).edit().putString("seenVer", v).commit()
    /** זמן ההתקנה שההבהרה כבר אושרה בו (התקנה חדשה = זמן אחר = מוצגת שוב) */
    fun disclaimerFor(c: Context) = sp(c).getLong("disclaimerFor", 0L)
    fun setDisclaimerFor(c: Context, at: Long) = sp(c).edit().putLong("disclaimerFor", at).apply()

    // ---- פיד ארצי: כל ההתראות בארץ, בלי קשר לאזורים שלי ----
    private const val MAX_FEED = 600   // מספיק לשבוע במפה

    /**
     * הפיד וההיסטוריה נקראים הרבה (המסך הראשי, ווידג'טים, מפה) - מנתחים אותם רק כשהם השתנו.
     * אותו טקסט שמור = אותה רשימה מוכנה מהפעם הקודמת
     */
    private class Parsed(val raw: String, val list: List<Entry>)
    @Volatile private var feedCache: Parsed? = null
    @Volatile private var historyCache: Parsed? = null

    fun feed(c: Context): List<Entry> {
        val raw = sp(c).getString("feed", "[]") ?: "[]"
        feedCache?.let { if (it.raw == raw) return it.list }
        return parseFeed(c, raw).also { feedCache = Parsed(raw, it) }
    }
    private fun parseFeed(c: Context, raw: String): List<Entry> {
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry("", AlertService.displayTitle(o.optString("title")), o.optString("body"), AlertService.levelOf(o.optString("title")), o.optLong("ts"))
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
            x = x.copy(title = if (AlertUi.update(e.title)) e.title else "האירוע הסתיים", level = 2)   // עדכון עם נוסח משלו - נשאר כמו שהגיע
        val parts = x.body.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val real = parts.filter { AreaData.isKnown(c, it) }
        return if (real.isNotEmpty() && real.size < parts.size) x.copy(body = real.joinToString(", ")) else x
    }

    /** מפתח ליישוב - זהה בכל המקורות: "קריית שמונה" = "קרית שמונה", "תל אביב - מרכז" = "תל-אביב".
     *  רק י/ו כפולות מאוחדות - "אילת" ו"אילות" הם שני יישובים */
    fun areaKey(a: String) = nameKey(a.split(" - ")[0])
    /** השם המלא (עם האזור): "ירושלים - צפון" ו"ירושלים - מרכז" נשארים שניים */
    fun nameKey(a: String) = a.filter { it.isLetterOrDigit() }.replace("יי", "י").replace("וו", "ו")

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
        val tops = ArrayList<Long>()   // הזמן של החדשה בכל שורה - החלון נמדד ממנה (לא "זוחל" אחורה)
        for (e in list) {           // מהחדשה לישנה
            val ek = e.body.split(",").map { areaKey(it.trim()) }.filter { it.isNotEmpty() }.toSet()
            // סיום אירוע: כל הסיומים הרצופים (בלי התראה ביניהם, עד חצי שעה) = שורה אחת
            val last = out.lastOrNull()
            val i = if (e.level == 2 && last != null && last.level == 2 && e.ts > 0 &&
                last.ts - e.ts in 0 until END_MERGE_MS) out.lastIndex
            else out.indices.firstOrNull { j ->
                val k = out[j]
                k.level == e.level && e.ts > 0 && tops[j] - e.ts in 0 until DUP_MS &&
                    (tops[j] - e.ts < 90_000L || k.level == 2 || keys[j].any { it in ek })   // סיום אירוע - תמיד אחד
            }
            if (i == null) { out.add(e.copy(body = mergeBody(e.body, emptyList()))); keys.add(ek.toMutableSet()); tops.add(e.ts); continue }
            out[i] = e.copy(body = mergeBody(e.body, out[i].body.split(", ")), times = mergeTimes(e, out[i]))
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

    // ---- דיוק מיקום ----
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
    /** פעם אחת: מחיקת היסטוריית האזורים שנשמרה בעבר (רק המפה הישנה השתמשה בה) */
    fun dropOldVisits(c: Context) { if (sp(c).contains("visits2")) sp(c).edit().remove("visits2").apply() }

    // ---- צליל לכל סוג התראה (ריק = ברירת מחדל, "silent" = בלי צליל) ----
    fun sound(c: Context, level: Int) = sp(c).getString("sound$level", "") ?: ""

    // ---- מתג כללי לצלילים ולרטט של ההתרעות (ברירת מחדל: פעיל) ----
    fun soundsOn(c: Context) = sp(c).getBoolean("soundsOn", true)
    fun setSoundsOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("soundsOn", v).commit()
    fun vibesOn(c: Context) = sp(c).getBoolean("vibesOn", true)
    fun setVibesOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("vibesOn", v).commit()

    /** עקיפת מצב שקט: כשהטלפון על שקט/רטט - ההתרעה בכל זאת משמיעה צליל (ברירת מחדל: כבוי) */
    fun bypassSilent(c: Context) = sp(c).getBoolean("bypassSilent", false)
    /** השתקה זמנית: עד מתי ההתרעות מגיעות בלי צליל, רטט והקראה (Long.MAX_VALUE = עד שמבטלים) */
    fun muteUntil(c: Context) = sp(c).getLong("muteUntil", 0L)
    fun setMuteUntil(c: Context, v: Long) = sp(c).edit().putLong("muteUntil", v).commit()
    /** משך ההשתקה שנבחר (לסימון בעמוד) */
    fun muteMin(c: Context) = sp(c).getLong("muteMin", 0L)
    fun setMuteMin(c: Context, v: Long) = sp(c).edit().putLong("muteMin", v).apply()
    fun mutedNow(c: Context) = System.currentTimeMillis() < muteUntil(c)
    /** צליל חזק: כל ההתרעות בעוצמה מלאה, גם כשהווליום של הטלפון נמוך (למשל בעבודה במוסך) */
    fun loudSound(c: Context) = sp(c).getBoolean("loudSound", false)
    fun setLoudSound(c: Context, v: Boolean) = sp(c).edit().putBoolean("loudSound", v).commit()
    fun setBypassSilent(c: Context, v: Boolean) = sp(c).edit().putBoolean("bypassSilent", v).commit()
    /** אוזניות מחוברות: הצופר וההקראה יוצאים גם מהרמקול של הטלפון (ברירת מחדל: דלוק) */
    fun speakerWithHeadset(c: Context) = sp(c).getBoolean("speakerWithHeadset", true)
    fun setSpeakerWithHeadset(c: Context, v: Boolean) = sp(c).edit().putBoolean("speakerWithHeadset", v).commit()
    /** עדכון אוטומטי ברקע: גרסה חדשה מותקנת לבד (ברירת מחדל: דלוק) */
    fun autoUpdate(c: Context) = sp(c).getBoolean("autoUpdate", true)
    fun setAutoUpdate(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoUpdate", v).commit()

    // ---- הקראה בקול ----
    fun speakAlerts(c: Context) = sp(c).getBoolean("speak", true)
    /** הקראה גם בהתרעת בדיקה */
    fun testSpeak(c: Context) = sp(c).getBoolean("testSpeak", true)
    fun setTestSpeak(c: Context, v: Boolean) = sp(c).edit().putBoolean("testSpeak", v).commit()
    fun shabbatMode(c: Context) = sp(c).getInt("shabbatMode", Shabbat.OFF)
    /** מסך שבת: שעון במסך (המסך נשאר דלוק). כבוי - המסך נכבה ונדלק רק בהתרעה */
    fun shabbatClock(c: Context) = sp(c).getBoolean("shabbatClock", false)
    fun setShabbatClock(c: Context, v: Boolean) = sp(c).edit().putBoolean("shabbatClock", v).commit()
    fun shabbatSeconds(c: Context) = sp(c).getBoolean("shabbatSec", false)
    fun setShabbatSeconds(c: Context, v: Boolean) = sp(c).edit().putBoolean("shabbatSec", v).commit()
    /** הפעלה מהירה מכפתור השעון: המצב שהיה לפני (-1 = לא בהפעלה מהירה). ביציאה חוזרים אליו */
    fun shabbatQuick(c: Context) = sp(c).getInt("shabbatQuick", -1)
    fun setShabbatQuick(c: Context, prev: Int) = sp(c).edit().putInt("shabbatQuick", prev).commit()
    fun shabbatExitUntil(c: Context) = sp(c).getLong("shabbatExit", 0L)
    fun setShabbatExitUntil(c: Context, v: Long) = sp(c).edit().putLong("shabbatExit", v).apply()
    fun setShabbatMode(c: Context, v: Int): Boolean {
        val ok = sp(c).edit().putInt("shabbatMode", v).remove("shabbatExit").remove("shabbatQuick").commit()   // שינוי מצב - מסך השבת חוזר
        try { ShabbatLocReceiver.schedule(c) } catch (_: Exception) { }   // התעוררות לפני השבת (באוטומטי)
        return ok
    }
    fun setSpeakAlerts(c: Context, v: Boolean) = sp(c).edit().putBoolean("speak", v).commit()
    /** קול ההקראה: גבר (ברירת מחדל, כמו בצופר) או אישה */
    fun maleVoice(c: Context) = sp(c).getBoolean("maleVoice", true)

    // ---- סוג רטט לכל סוג התראה ----

    // ---- שעות שקט: משתיק התראה מקדימה וסיום אירוע (ירי תמיד נשמע) ----
    fun quietOn(c: Context) = sp(c).getBoolean("quietOn", false)
    fun setQuietOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("quietOn", v).commit()
    /** דקות מתחילת היום */
    fun quietFrom(c: Context) = sp(c).getInt("quietFrom", 23 * 60)
    fun quietTo(c: Context) = sp(c).getInt("quietTo", 7 * 60)
    fun setQuiet(c: Context, from: Int, to: Int) =
        sp(c).edit().putInt("quietFrom", from).putInt("quietTo", to).commit()

    // ---- תזמון הגנה: האפליקציה פעילה רק בימים ובשעות שנבחרו ----
    fun schedOn(c: Context) = sp(c).getBoolean("schedOn", false)
    fun setSchedOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("schedOn", v).commit()
    // לכל יום שעות משלו (0 = ראשון ... 6 = שבת)
    fun schedDayOn(c: Context, d: Int) = sp(c).getBoolean("schedD${d}On", true)
    fun schedDayFrom(c: Context, d: Int) = sp(c).getInt("schedD${d}From", 7 * 60)
    fun schedDayTo(c: Context, d: Int) = sp(c).getInt("schedD${d}To", 23 * 60)
    fun setSchedDay(c: Context, d: Int, on: Boolean, from: Int, to: Int) = sp(c).edit()
        .putBoolean("schedD${d}On", on).putInt("schedD${d}From", from).putInt("schedD${d}To", to).commit()

    /** האם עכשיו מחוץ לתזמון (התרעות לא מתקבלות) */
    fun scheduleOffNow(c: Context): Boolean {
        if (!schedOn(c)) return false
        val k = java.util.Calendar.getInstance()
        val now = k.get(java.util.Calendar.HOUR_OF_DAY) * 60 + k.get(java.util.Calendar.MINUTE)
        val today = k.get(java.util.Calendar.DAY_OF_WEEK) - 1          // 0 = ראשון
        val yesterday = (today + 6) % 7
        // היום: מהשעה שלו (עד חצות אם הטווח חוצה חצות)
        val tf = schedDayFrom(c, today); val tt = schedDayTo(c, today)
        val inToday = schedDayOn(c, today) && when {
            tf == tt -> true                                             // כל היום
            tf < tt -> now >= tf && now < tt
            else -> now >= tf
        }
        // אתמול: טווח שחוצה חצות ממשיך עד הבוקר
        val yf = schedDayFrom(c, yesterday); val yt = schedDayTo(c, yesterday)
        val fromYesterday = schedDayOn(c, yesterday) && yf > yt && now < yt
        return !(inToday || fromYesterday)
    }

    fun isQuietNow(c: Context): Boolean {
        if (!quietOn(c)) return false
        val k = java.util.Calendar.getInstance()
        val now = k.get(java.util.Calendar.HOUR_OF_DAY) * 60 + k.get(java.util.Calendar.MINUTE)
        val f = quietFrom(c); val t = quietTo(c)
        if (f == t) return true   // אותה שעה בהתחלה ובסיום = כל היום (כמו בתזמון ההגנה)
        return if (f < t) now in f until t else now >= f || now < t   // גם טווח שחוצה חצות
    }

    // ---- ספירה לאחור פעילה (לווידג'ט) ----
    fun countdownUntil(c: Context) = sp(c).getLong("cdUntil", 0L)
    fun setCountdown(c: Context, until: Long, title: String) =
        sp(c).edit().putLong("cdUntil", until).putString("cdTitle", title).commit()

    // ---- אירוע ירי פעיל: מהירי עד הודעת הסיום (רשת ביטחון - שעה, אם הסיום לא הגיע) ----
    const val STAY_MS = 60 * 60 * 1000L
    /** התרעת בדיקה: ההתראה הנוספת נעלמת אחרי 10 דקות (אין הודעת סיום בבדיקה) */
    const val TEST_STAY_MS = 10 * 60 * 1000L
    fun stayUntil(c: Context) = sp(c).getLong("stayUntil", 0L)
    fun setStayUntil(c: Context, v: Long) = sp(c).edit().putLong("stayUntil", v).commit()

    // ---- עקיפת "נא לא להפריע" בזמן התראה ----
    fun dndOverride(c: Context) = sp(c).getBoolean("dndOverride", true)
    fun setDndOverride(c: Context, v: Boolean) = sp(c).edit().putBoolean("dndOverride", v).commit()

    /** ערכת צבעים: 0 כהה, 1 בהירה, 2 לפי המערכת */
    fun themeMode(c: Context) = sp(c).getInt("themeMode", if (sp(c).getBoolean("dark", true)) 0 else 1)
    fun setThemeMode(c: Context, v: Int) = sp(c).edit().putInt("themeMode", v).commit()

    /** סוג תצוגת התרעה: 0 מסך מלא · 1 פופ-אפ · 2 כרטיס מעל השעון · 3 ממוזער */
    fun alertStyle(c: Context) = sp(c).getInt("alertStyle", 0)
    /** התאמה אישית לכל נושא (0 התרעות · 1 מבזקים והנחיות · 2 עדכונים): -1 = לפי סוג התצוגה הכללי */
    fun topicStyle(c: Context, level: Int) = sp(c).getInt("alertStyle_$level", -1)
    fun setTopicStyle(c: Context, level: Int, v: Int) = sp(c).edit().putInt("alertStyle_$level", v).apply()
    /** סוג התצוגה בפועל לנושא: ההתאמה האישית, ואם אין - הכללי */
    fun alertStyleFor(c: Context, level: Int) = resolveStyle(customStyles(c), topicStyle(c, level), alertStyle(c))
    /** ההחלטה עצמה (בלי טלפון - נבדק בבדיקות): התאמה אישית פעילה ונבחר סגנון לנושא - הוא; אחרת הכללי */
    fun resolveStyle(custom: Boolean, topic: Int, general: Int) = if (custom && topic in 0..3) topic else general
    /** מתג ההתאמה האישית (כבוי = כל הנושאים לפי הבחירה הכללית). ברירת מחדל: פעיל אם כבר נבחר משהו לנושא */
    fun customStyles(c: Context) = sp(c).getBoolean("customStyles", (0..2).any { topicStyle(c, it) in 0..3 })
    fun setCustomStyles(c: Context, v: Boolean) = sp(c).edit().putBoolean("customStyles", v).apply()
    /** אחרי סגירת מסך מלא / פופ-אפ: 0 = כרטיס במסך הראשי, 1 = ממוזער (החלון הקטן למעלה) */
    fun afterClose(c: Context) = sp(c).getInt("afterClose", 0)
    /** מסך התרעה נפתח - רק כדי שהמסך הראשי (שמאזין לשינויים) יתעדכן */
    fun markAlertScreen(c: Context) = sp(c).edit().putLong("alertScreenAt", System.currentTimeMillis()).apply()
    /** איפה נגמרת שורת החיוויים במסך הראשי (פיקסלים מראש המסך) - החלון הממוזער מתחתיה. 0 = לא ידוע */
    fun miniY(c: Context) = sp(c).getInt("miniY", 0)
    fun setMiniY(c: Context, v: Int) { if (v > 0 && v != miniY(c)) sp(c).edit().putInt("miniY", v).apply() }
    /** רוחב שורת החיוויים ומיקומה מהצד (פיקסלים) - החלון הממוזער באותו רוחב בדיוק */
    fun miniH(c: Context) = sp(c).getInt("miniH", 0)
    fun setMiniH(c: Context, v: Int) { if (v > 0 && v != miniH(c)) sp(c).edit().putInt("miniH", v).apply() }
    fun miniX(c: Context) = sp(c).getInt("miniX", -1)
    fun miniW(c: Context) = sp(c).getInt("miniW", 0)
    fun setMiniBox(c: Context, x: Int, w: Int) { if (w > 0 && (x != miniX(c) || w != miniW(c))) sp(c).edit().putInt("miniX", x).putInt("miniW", w).apply() }
    fun setAfterClose(c: Context, v: Int) = sp(c).edit().putInt("afterClose", v).apply()
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
    @Volatile private var activeCache: Pair<String, org.json.JSONObject?>? = null
    fun activeAlert(c: Context): org.json.JSONObject? {
        val raw = sp(c).getString("activeAlert", "") ?: ""
        activeCache?.let { if (it.first == raw) return it.second }
        val j = try { raw.takeIf { it.isNotEmpty() }?.let { org.json.JSONObject(it) } } catch (_: Exception) { null }
        activeCache = raw to j
        return j
    }
    /** ערכת צבעים "לפי זמן": 0 = משקיעה עד זריחה, 1 = לוח זמנים מותאם (דקות מחצות) */
    fun themeTimeMode(c: Context) = sp(c).getInt("themeTimeMode", 0)
    fun setThemeTimeMode(c: Context, v: Int) = sp(c).edit().putInt("themeTimeMode", v).apply()
    fun darkFrom(c: Context) = sp(c).getInt("darkFrom", 19 * 60)
    fun setDarkFrom(c: Context, v: Int) = sp(c).edit().putInt("darkFrom", v).apply()
    fun darkTo(c: Context) = sp(c).getInt("darkTo", 7 * 60)
    fun setDarkTo(c: Context, v: Int) = sp(c).edit().putInt("darkTo", v).apply()
    /** "לפי זמן": האם עכשיו חשוך, ומתי ההחלפה הבאה */
    fun darkByTime(c: Context, now: Long = System.currentTimeMillis()): Pair<Boolean, Long> {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        if (themeTimeMode(c) == 1) {
            val min = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val from = darkFrom(c); val to = darkTo(c)
            val dark = if (from == to) false else if (from < to) min in from until to else min >= from || min < to
            val next = if (dark) to else from
            val nextCal = (cal.clone() as java.util.Calendar).apply {
                set(java.util.Calendar.HOUR_OF_DAY, next / 60); set(java.util.Calendar.MINUTE, next % 60)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                if (timeInMillis <= now) add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            return dark to nextCal.timeInMillis
        }
        // משקיעה עד זריחה - לפי המיקום האחרון (בלי מיקום: ירושלים)
        val (_, _, pos) = lastAcc(c)
        val lat = if (pos.first != 0.0) pos.first else 31.78
        val lon = if (pos.second != 0.0) pos.second else 35.22
        val rise = Shabbat.sunEvent(cal, lat, lon, rising = true)
        val set = Shabbat.sunEvent(cal, lat, lon, rising = false)
        return when {
            now < rise -> true to rise
            now < set -> false to set
            else -> true to Shabbat.sunEvent((cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, 1) }, lat, lon, rising = true)
        }
    }
    fun darkTheme(c: Context) = when (themeMode(c)) {
        1 -> false
        3 -> darkByTime(c).first
        2 -> (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        else -> true
    }

    /**
     * בטא רק למכשירים שברשימה (לפי מזהה המכשיר). רשימה ריקה = בלי הגבלה.
     * בכל מכשיר אחר הבטא כבויה ומוסתרת, גם אם הופעלה בעבר.
     */
    // המכשירים של טל - רק עד שהשרת ענה פעם ראשונה (אחר כך הרשימה בשרת קובעת)
    private val BETA_DEVICES = setOf("1F31D43C", "CD97E56F")

    /** מזהה קצר וקבוע של המכשיר (8 תווים) */
    fun deviceCode(c: Context): String {
        @android.annotation.SuppressLint("HardwareIds")
        val id = android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        return h.take(4).joinToString("") { "%02X".format(it) }
    }

    /** מורשה לבטא: לפי התשובה האחרונה מהשרת; אם השרת עוד לא ענה אף פעם - לפי הרשימה המקורית */
    fun betaAllowed(c: Context): Boolean =
        if (sp(c).contains("betaSrv")) sp(c).getBoolean("betaSrv", false) else deviceCode(c).uppercase() in BETA_DEVICES

    /** שואלים את השרת אם המכשיר מורשה לבטא (לכל היותר פעם ב-10 דקות, ברקע) */
    fun refreshBeta(c: Context) {
        val now = System.currentTimeMillis()
        if (now - sp(c).getLong("betaSrvAt", 0) < 10 * 60_000L) return
        sp(c).edit().putLong("betaSrvAt", now).apply()
        Thread {
            try {
                val conn = java.net.URL("http://151.145.91.93:8080/beta?code=" + deviceCode(c)).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000; conn.readTimeout = 4000
                val ok = try { if (conn.responseCode != 200) null else
                    org.json.JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).getBoolean("allowed")
                } finally { conn.disconnect() } ?: return@Thread
                val changed = betaAllowed(c) != ok || !sp(c).contains("betaSrv")
                sp(c).edit().putBoolean("betaSrv", ok).apply()
                if (changed) PushService.subscribe(c)   // הודעות התקלה - רק למכשירי הבטא
            } catch (_: Exception) { }
        }.start()
    }

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
    /** מתי נבדק לאחרונה אם יש גרסה חדשה (גם בבדיקה האוטומטית) */
    fun lastUpdateCheck(c: Context) = sp(c).getLong("lastUpdCheck", 0L)
    fun setLastUpdateCheck(c: Context) = sp(c).edit().putLong("lastUpdCheck", System.currentTimeMillis()).apply()
    fun updateAvail(c: Context): Pair<String, Int> = (sp(c).getString("updAvail", "") ?: "") to sp(c).getInt("updAvailBuild", 0)
    fun setUpdateAvail(c: Context, ver: String, build: Int) = sp(c).edit().putString("updAvail", ver).putInt("updAvailBuild", build).apply()
    fun setNotifiedVersion(c: Context, v: String) = sp(c).edit().putString("notifVer", v).commit()

    data class Entry(val time: String, val title: String, val body: String, val level: Int, val ts: Long = 0,
                     val src: String = "",
                     /** מתי הגיע כל יישוב (יישוב שלא כאן - בזמן של השורה) */
                     val times: Map<String, Long> = emptyMap())

    /** הזמן של כל יישוב בשורה */
    fun areaTimes(e: Entry): Map<String, Long> =
        e.body.split(",").map { it.trim() }.filter { it.isNotEmpty() }.associateWith { e.times[it] ?: e.ts }
    /** השעה שמוצגת בשורה ברשימה: של היישוב הראשון שהגיע מבין היישובים האמיתיים (isTown); אין כאלה - השעה של השורה */
    fun rowTime(e: Entry, isTown: (String) -> Boolean): Long =
        areaTimes(e).filterKeys(isTown).values.minOrNull() ?: e.ts

    /** איחוד זמנים של שתי שורות - לכל יישוב הזמן המוקדם */
    fun mergeTimes(a: Entry, b: Entry): Map<String, Long> {
        val m = HashMap(areaTimes(b))
        areaTimes(a).forEach { (k, v) -> m[k] = minOf(v, m[k] ?: v) }
        return m
    }
    private fun timesJson(e: Entry) = JSONObject().also { o -> e.times.forEach { (k, v) -> o.put(k, v) } }
    private fun timesOf(o: JSONObject?): Map<String, Long> = o?.keys()?.asSequence()?.associateWith { o.optLong(it) } ?: emptyMap()

    /** היסטוריית התראות, החדשה ראשונה */
    fun history(c: Context): List<Entry> {
        val raw = sp(c).getString("history", "[]") ?: "[]"
        historyCache?.let { if (it.raw == raw) return it.list }
        return parseHistory(c, raw).also { historyCache = Parsed(raw, it) }
    }
    private fun parseHistory(c: Context, raw: String): List<Entry> {
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.optString("time"), AlertService.displayTitle(o.optString("title")), o.optString("body"),
                AlertService.levelOf(o.optString("title")), o.optLong("ts"), o.optString("src"), timesOf(o.optJSONObject("at")))
        }.filter { it.body != "התראת בדיקה" }   // בדיקות מגרסאות קודמות לא מוצגות
            .map { clean(c, it) }
            .sortedByDescending { it.ts }
            .let { collapse(it) }
    }

    /**
     * "הכול" משלים ממה שהטלפון קיבל: שורה מהארכיון שיש לה שורה תואמת ב"שלי" (אותו סוג וכותרת, עד 5 דקות) -
     * הסדר של השרת (סדר ההגעה בזמן אמת, לכל הטלפונים), ואחריו יישובים שהגיעו לטלפון ואין בשרת
     */
    fun withMine(all: List<Entry>, mine: List<Entry>): List<Entry> {
        if (mine.isEmpty()) return all
        return all.map { e ->
            val m = mine.firstOrNull { it.level == e.level && it.title == e.title && e.ts > 0 && it.ts > 0 && Math.abs(it.ts - e.ts) < DUP_MS }
            if (m == null) e else e.copy(body = mergeBody(e.body, m.body.split(",").map { it.trim() }.filter { it.isNotEmpty() }),
                times = HashMap(areaTimes(m)).apply { putAll(areaTimes(e)) }, src = e.src.ifEmpty { m.src })   // המקור - של השרת; אין - מה שהתריע אצלי
        }
    }

    /**
     * "שלי" לפי השרת: לכל התרעה שהגיעה אליי - השורה התואמת מהשרת (אותו סוג וכותרת, עד 5 דקות):
     * היישובים והסדר והשעות (גם של השורה) של השרת, רק אלה שלי (keep), ואחריהם יישובים שהגיעו אליי ואין בשרת.
     * כך תקלה בטלפון ברגע הקבלה לא משבשת את הרשימה
     */
    fun mineFromServer(mine: List<Entry>, all: List<Entry>, keep: (String) -> Boolean): List<Entry> {
        if (all.isEmpty()) return mine
        return mine.map { m ->
            val e = all.firstOrNull { it.level == m.level && it.title == m.title && it.ts > 0 && m.ts > 0 && Math.abs(it.ts - m.ts) < DUP_MS }
                ?: return@map m
            fun split(b: String) = b.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val srv = split(e.body).filter(keep)
            val extra = split(m.body).filter { a -> a !in srv && keep(a) }
            val areas = srv + extra
            if (areas.isEmpty()) return@map m
            val t = areaTimes(m) + areaTimes(e)   // השעות של השרת קובעות
            // גם השעה של השורה - של פיקוד העורף (מהשרת), כמו ב"הכול"
            m.copy(body = areas.joinToString(", "), times = areas.associateWith { t[it] ?: m.ts }, ts = e.ts)
        }
    }

    internal fun mergeBody(body: String, add: List<String>): String {
        fun k(a: String) = nameKey(a)
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
        // מתי הגיע כל יישוב שהצטרף - לפירוט לפי שעות במסך ההתרעה מההתרעות האחרונות
        val now = System.currentTimeMillis()
        val at = o.optJSONObject("at") ?: JSONObject()
        val had = o.optString("body").split(",").map { it.trim() }.toSet()
        add.filter { it !in had && !at.has(it) }.forEach { at.put(it, now) }
        if (at.length() > 0) o.put("at", at)
        o.put("body", mergeBody(o.optString("body"), add))
        sp(c).edit().putString("history", arr.toString()).apply()
    }

    @Synchronized
    fun addHistory(c: Context, e: Entry) {
        val list = listOf(e) + history(c).take(MAX_HISTORY - 1)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("time", it.time).put("title", it.title)
                .put("body", it.body).put("level", it.level).put("ts", it.ts).put("src", it.src)
                .apply { if (it.times.isNotEmpty()) put("at", timesJson(it)) })
        }
        sp(c).edit().putString("history", arr.toString()).apply()
    }
}

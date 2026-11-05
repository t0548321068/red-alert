package com.tal.redalert

import android.content.Context
import android.icu.util.HebrewCalendar
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

/**
 * מצב שבת: כבוי / דלוק / אוטומטי (שבת וחג לפי המיקום).
 * בזמן מצב שבת כל התראה מוצגת דקה אחת ונסגרת לבד, עם הקראה קולית.
 */
object Shabbat {
    const val OFF = 0
    const val ON = 1
    const val AUTO = 2

    // זמנים כמו בלוח "עתים לבינה" (itimlabina.co.il), מתוך shabbat.json (נמדד מהאתר לאורך שנה שלמה, 125 מקומות):
    // לכל מקום - המצולע שלו באתר, ההפרש של הדלקת הנרות מהשקיעה ושל צאת השבת מ-8.5° (משתנה לפי עונה).
    // המקום = המצולע הקטן ביותר שמכיל את המיקום, כמו באתר; מחוץ לכולם - ברירת המחדל של האתר.
    // הקובץ מתעדכן מהשרת (DataFiles) - תיקון זמנים בלי גרסה חדשה
    private class Place(val s: DoubleArray, val e: DoubleArray, val poly: Array<DoubleArray>, val area: Double)
    @Volatile private var places: Pair<List<Place>, Place>? = null
    private fun arr(a: org.json.JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
    private fun data(): Pair<List<Place>, Place> = places ?: synchronized(this) {
        places ?: try {
            val o = org.json.JSONObject(DataFiles.text(DataFiles.app!!, "shabbat.json"))
            val d = o.getJSONObject("def")
            val def = Place(arr(d.getJSONArray("s")), arr(d.getJSONArray("e")), emptyArray(), Double.MAX_VALUE)
            val c = o.getJSONArray("c")
            val list = List(c.length()) { i ->
                val x = c.getJSONObject(i); val p = x.getJSONArray("p")
                val poly = Array(p.length()) { arr(p.getJSONArray(it)) }
                var area = 0.0
                for (k in poly.indices) { val q = poly[k]; val r = poly[(k + 1) % poly.size]; area += q[1] * r[0] - r[1] * q[0] }
                Place(arr(x.getJSONArray("s")), arr(x.getJSONArray("e")), poly, Math.abs(area) / 2)
            }
            (list to def).also { places = it }
        } catch (_: Exception) {   // לא אמור לקרות - ברירת המחדל של האתר (30 דק׳ לפני השקיעה)
            emptyList<Place>() to Place(doubleArrayOf(-30.0, 0.0, 0.0, 0.0, 0.0), DoubleArray(5), emptyArray(), 0.0)
        }
    }
    /** השרת עדכן את הקובץ - טוענים מחדש */
    fun reload() { places = null }
    private fun inside(lat: Double, lon: Double, p: Array<DoubleArray>): Boolean {
        var c = false; var j = p.size - 1
        for (i in p.indices) {
            val a = p[i]; val b = p[j]
            if ((a[0] > lat) != (b[0] > lat) && lon < (b[1] - a[1]) * (lat - a[0]) / (b[0] - a[0]) + a[1]) c = !c
            j = i
        }
        return c
    }
    private fun place(lat: Double, lon: Double): Place {
        val (list, def) = data()
        return list.filter { it.poly.size >= 3 && inside(lat, lon, it.poly) }.minByOrNull { it.area } ?: def
    }
    /** ההפרש בדקות ליום בשנה (עונתי) */
    private fun offset(k: DoubleArray, day: Calendar): Double {
        val w = 2 * PI * day.get(Calendar.DAY_OF_YEAR) / 365.25
        return k[0] + k[1] * cos(w) + k[2] * sin(w) + k[3] * cos(2 * w) + k[4] * sin(2 * w)
    }
    // מעוגל לדקה הקרובה, כמו בלוח (ההפרשים בקובץ כבר כוללים מרווח: הכניסה לא אחרי הלוח והיציאה לא לפניו)
    private fun roundMin(ms: Long) = (ms + 30_000) / 60_000 * 60_000
    private fun candle(prev: Calendar, lat: Double, lon: Double) =
        roundMin(sunset(prev, lat, lon, 90.833) + (offset(place(lat, lon).s, prev) * 60_000).toLong())

    fun label(mode: Int) = when (mode) { ON -> "דלוק"; AUTO -> "אוטומטי"; else -> "כבוי" }

    // ---- הזמנים מהשרת: אותו חישוב, במקום אחד (תיקון זמנים בלי לעדכן את האפליקציה). אין תשובה - חישוב בטלפון ----
    private const val SERVER = "http://151.145.91.93:8080/shabbat"
    private fun sp(c: Context) = c.getSharedPreferences("shabbat_srv", Context.MODE_PRIVATE)
    /** הזמנים השמורים תקפים רק לאותה גרסת אפליקציה ואותם נתונים מהשרת - תיקון זמנים נקלט מיד */
    private fun tag(c: Context) = Updater.currentVersion(c) + "/" + DataFiles.version(c)

    /** רענון ברקע: פעם בשעה (בקשה קטנה אחת), או כשזזת מעל 5 ק"מ */
    fun refresh(c: Context) {
        val (lat, lon) = where(c)
        val s = sp(c)
        val moved = FloatArray(1).also { android.location.Location.distanceBetween(
            s.getFloat("lat", 0f).toDouble(), s.getFloat("lon", 0f).toDouble(), lat, lon, it) }[0] > 5000f
        if (!moved && s.getString("tag", "") == tag(c) && System.currentTimeMillis() - s.getLong("at", 0L) < 3600_000L) return
        Thread {
            try {
                val conn = java.net.URL("$SERVER?lat=%.4f&lon=%.4f".format(java.util.Locale.US, lat, lon)).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000; conn.readTimeout = 5000
                val txt = try { conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { conn.disconnect() }
                org.json.JSONObject(txt).getJSONArray("windows")   // תקין
                s.edit().putString("json", txt).putLong("at", System.currentTimeMillis()).putString("tag", tag(c))
                    .putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply()
            } catch (_: Exception) { }
        }.start()
    }

    /** השבתות והחגים הקרובים מהשרת (כניסה, יציאה) - אם נשמרו בשבוע האחרון ולא זזת מעל 5 ק"מ */
    private fun serverWindows(c: Context, lat: Double, lon: Double): List<Pair<Long, Long>>? = try {
        val s = sp(c)
        val far = FloatArray(1).also { android.location.Location.distanceBetween(
            s.getFloat("lat", 0f).toDouble(), s.getFloat("lon", 0f).toDouble(), lat, lon, it) }[0] > 5000f
        if (far || s.getString("tag", "") != tag(c) || System.currentTimeMillis() - s.getLong("at", 0L) > 7 * 24 * 3600_000L) null
        else {
            val a = org.json.JSONObject(s.getString("json", "") ?: "").getJSONArray("windows")
            List(a.length()) { a.getJSONObject(it).let { w -> w.getLong("in") to w.getLong("out") } }
        }
    } catch (_: Exception) { null }

    // המסך הראשי שואל כל שנייה - בודקים לכל היותר פעם בחצי דקה (מיקום + חישוב)
    @Volatile private var activeAt = 0L
    @Volatile private var activeVal = false
    fun active(c: Context, now: Long = System.currentTimeMillis(), cached: Boolean = true): Boolean = when (Prefs.shabbatMode(c)) {
        ON -> true
        AUTO -> {
            if (cached && Math.abs(now - activeAt) < 30_000) activeVal
            else (try { holyNow(c, now) } catch (_: Exception) { false }).also { activeAt = now; activeVal = it }
        }
        else -> false
    }

    /** מתי מצב השבת האוטומטי נגמר / מתחיל (להצגה) */
    fun endsAt(c: Context, now: Long = System.currentTimeMillis()): Long? {
        val (lat, lon) = where(c)
        serverWindows(c, lat, lon)?.let { ws -> return ws.firstOrNull { now >= it.first - 24 * 3600_000L && now < it.second }?.second }
        val day = cal(now)
        return if (isHoly(day)) tzeit(day, lat, lon) else null
    }

    /** השבת/החג הנוכחי או הקרוב: (כניסה, יציאה) לפי המיקום - כמו שמצב השבת האוטומטי משתמש */
    fun nextWindow(c: Context, now: Long = System.currentTimeMillis()): Pair<Long, Long>? {
        val (lat, lon) = where(c)
        serverWindows(c, lat, lon)?.firstOrNull { it.second > now }?.let { return it }
        val base = cal(now)
        for (i in -1..60) {
            val day = (base.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            val prev = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
            if (!isHoly(day) || isHoly(prev)) continue          // תחילת רצף של ימים קדושים
            val start = candle(prev, lat, lon)
            var last = day
            while (true) {
                val next = (last.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
                if (isHoly(next)) last = next else break        // חג שצמוד לשבת - יציאה אחת בסוף
            }
            val end = tzeit(last, lat, lon)
            if (end > now) return start to end
        }
        return null
    }

    /** האם יש מיקום שמור (אחרת הזמנים לפי מרכז הארץ) */
    fun hasLocation(c: Context): Boolean = try { Weather.lastLocation(c) != null } catch (_: Exception) { false }

    private fun where(c: Context): Pair<Double, Double> {
        val l = try { Weather.lastLocation(c) } catch (_: Exception) { null }
        return if (l != null) l.latitude to l.longitude else 31.78 to 35.0   // ברירת מחדל: מרכז הארץ
    }

    private fun holyNow(c: Context, now: Long): Boolean {
        val (lat, lon) = where(c)
        serverWindows(c, lat, lon)?.let { ws -> return ws.any { now >= it.first && now < it.second } }
        val today = cal(now)
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        // היום קדוש ועוד לא יצאו הכוכבים
        if (isHoly(today) && now < tzeit(today, lat, lon)) return true
        // מחר קדוש ועברה כניסת השבת/החג היום
        if (isHoly(tomorrow) && now >= candle(today, lat, lon)) return true
        return false
    }

    private fun cal(ms: Long) = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem")).apply { timeInMillis = ms }

    /** שבת, או יום טוב בארץ (ראש השנה, יום כיפור, סוכות, שמיני עצרת, פסח, שביעי של פסח, שבועות) */
    private fun isHoly(day: Calendar): Boolean {
        if (day.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) return true
        val h = HebrewCalendar(android.icu.util.TimeZone.getTimeZone("Asia/Jerusalem"), android.icu.util.ULocale.getDefault())
        h.timeInMillis = (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 12) }.timeInMillis
        val m = h.get(android.icu.util.Calendar.MONTH); val d = h.get(android.icu.util.Calendar.DAY_OF_MONTH)
        return when (m) {
            HebrewCalendar.TISHRI -> d in setOf(1, 2, 10, 15, 22)
            HebrewCalendar.NISAN -> d == 15 || d == 21
            HebrewCalendar.SIVAN -> d == 6
            else -> false
        }
    }

    private fun tzeit(day: Calendar, lat: Double, lon: Double) =
        roundMin(sunset(day, lat, lon, 98.5) + (offset(place(lat, lon).e, day) * 60_000).toLong())   // צאת השבת כמו בעתים לבינה

    /** שקיעה (או זנית אחר) ביום נתון - אלגוריתם NOAA, מחזיר זמן UTC במילישניות */
    private fun sunset(day: Calendar, lat: Double, lon: Double, zenith: Double): Long {
        val n = day.get(Calendar.DAY_OF_YEAR)
        val lngHour = lon / 15.0
        val t = n + ((18 - lngHour) / 24)
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
        l = (l + 360) % 360
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
        ra = (ra + 360) % 360
        ra += (floor(l / 90) * 90 - floor(ra / 90) * 90)
        ra /= 15
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(Math.toRadians(zenith)) - sinDec * sin(Math.toRadians(lat))) / (cosDec * cos(Math.toRadians(lat)))
        val h = Math.toDegrees(acos(cosH.coerceIn(-1.0, 1.0))) / 15
        val localT = h + ra - 0.06571 * t - 6.622
        val ut = ((localT - lngHour) % 24 + 24) % 24
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH))
        }
        return utc.timeInMillis + (ut * 3600_000).toLong()
    }
}

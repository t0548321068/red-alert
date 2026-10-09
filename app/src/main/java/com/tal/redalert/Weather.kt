package com.tal.redalert

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** מזג אוויר לפי מיקום - Open-Meteo (חינמי, בלי מפתח) */
object Weather {

    data class Now(val temp: Int, val code: Int, val isDay: Boolean, val place: String, val accuracy: Int = 0)

    // המיקום המדויק האחרון (עד 30 מ׳) - שמיקום גס מהרשת לא ידרוס אותו
    @Volatile private var precise: Location? = null

    /**
     * בוחר באיזה מיקום להשתמש לאזורים:
     * מיקום מדויק נשמר. מיקום גס (מעל 50 מ׳) לא מחליף מיקום מדויק מ-10 הדקות האחרונות,
     * אלא אם הוא מראה שזזנו רחוק (יותר מהסטייה שלו + 500 מ׳).
     */
    fun chooseLocation(loc: Location): Location {
        if (loc.accuracy in 0.1f..30f) { precise = loc; return loc }
        val p = precise ?: return loc
        val fresh = System.currentTimeMillis() - p.time < 10 * 60 * 1000L
        val movedFar = loc.distanceTo(p) > loc.accuracy + 500f
        return if (loc.accuracy > 50f && fresh && !movedFar) p else loc
    }

    /** מיקום אחרון שידוע למכשיר - גיבוי אם אין מיקום עדכני */
    @SuppressLint("MissingPermission")
    fun lastLocation(c: Context): Location? {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
    }

    /** מיקום מהרשת (סלולר / Wi-Fi) - כמעט בלי סוללה. null אם אין */
    @SuppressLint("MissingPermission")
    fun networkLocation(c: Context, done: (Location?) -> Unit) {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { done(null); return }
        val main = Handler(Looper.getMainLooper())
        var finished = false
        fun finish(l: Location?) { if (!finished) { finished = true; done(l) } }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, c.mainExecutor) { finish(it) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, { finish(it) }, Looper.getMainLooper())
            }
        } catch (_: Exception) { finish(null); return }
        main.postDelayed({ finish(null) }, 15_000)
    }

    /**
     * מיקום בדיוק המרבי שהמכשיר מסוגל לו:
     * - אוסף מיקומים מכל המקורות ומחכה עד שהדיוק מפסיק להשתפר (עד 30 שניות)
     * - לומד את הדיוק הטוב ביותר שהמכשיר הגיע אליו אי פעם, ומסיים מיד כשמגיעים אליו
     * - מזהה אם למכשיר יש GPS כפול־תדר (L5) - אז מחכה יותר, כי אפשר להגיע ל-1-3 מטר
     */
    @SuppressLint("MissingPermission")
    fun freshLocation(c: Context, done: (Location?) -> Unit) {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val main = Handler(Looper.getMainLooper())
        var finished = false
        val listeners = mutableListOf<LocationListener>()
        var gnssCb: GnssStatus.Callback? = null
        fun stopAll() {
            listeners.forEach { try { lm.removeUpdates(it) } catch (_: Exception) { } }
            gnssCb?.let { try { lm.unregisterGnssStatusCallback(it) } catch (_: Exception) { } }
        }
        fun finish(l: Location?) {
            if (finished) return
            finished = true
            stopAll()
            l?.let { Prefs.recordAccuracy(c, it.accuracy) }
            done(l ?: lastLocation(c))
        }

        // היעד: הדיוק הטוב ביותר שהמכשיר הזה הגיע אליו (+ מרווח קטן). בפעם הראשונה - 5 מטר.
        val deviceBest = Prefs.bestAccuracy(c)
        val target = if (deviceBest > 0f) maxOf(deviceBest * 1.3f, deviceBest + 1f) else 5f

        // מיקום מהדקה האחרונה שכבר בדיוק המרבי - אין צורך לחכות
        lastLocation(c)?.let {
            val ageMs = System.currentTimeMillis() - it.time
            if (ageMs < 60 * 1000 && it.accuracy in 0.1f..target) { finish(it); return }
        }

        val providers = buildList {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER))
                add(LocationManager.FUSED_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) { finish(null); return }

        // זיהוי GPS כפול־תדר: לוויין שמשדר ב-L5/E5 (~1176 מגה־הרץ)
        try {
            gnssCb = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    for (i in 0 until status.satelliteCount) {
                        if (status.hasCarrierFrequencyHz(i) && status.getCarrierFrequencyHz(i) in 1.1e9f..1.2e9f) {
                            Prefs.setDualFrequency(c, true)
                            return
                        }
                    }
                }
            }
            lm.registerGnssStatusCallback(gnssCb!!, main)
        } catch (_: Exception) { }

        var best: Location? = null
        var lastImprove = 0L
        for (p in providers) {
            val l = LocationListener { loc ->
                if (best == null || loc.accuracy < best!!.accuracy) {
                    best = loc
                    lastImprove = System.currentTimeMillis()
                }
                if (loc.accuracy in 0.1f..target) main.post { finish(best) }   // הגענו למקסימום של המכשיר
            }
            listeners += l
            try { lm.requestLocationUpdates(p, 0L, 0f, l, Looper.getMainLooper()) } catch (_: Exception) { }
        }

        // בדיקה כל שנייה: אם יש מיקום לוויני והדיוק לא השתפר 6 שניות (10 בכפול־תדר) - זה המקסימום כרגע
        val check = object : Runnable {
            override fun run() {
                if (finished) return
                val b = best
                val stall = if (Prefs.dualFrequency(c)) 10_000 else 6_000
                if (b != null && b.provider != LocationManager.NETWORK_PROVIDER &&
                    System.currentTimeMillis() - lastImprove > stall) { finish(b); return }
                main.postDelayed(this, 1000)
            }
        }
        main.postDelayed(check, 1000)
        main.postDelayed({ finish(best) }, 30_000)
    }

    /** קריאת רשת - להריץ מחוץ ל-UI thread */
    fun fetch(c: Context, loc: Location): Now {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
            .format(Locale.US, loc.latitude, loc.longitude) +
            "&current=temperature_2m,weather_code,is_day&timezone=auto"
        val cur = getJson(url).getJSONObject("current")
        return Now(
            Math.round(cur.getDouble("temperature_2m")).toInt(),
            cur.optInt("weather_code"),
            cur.optInt("is_day", 1) == 1,
            placeName(c, loc),
            Math.round(loc.accuracy)
        )
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "RedAlert-Android/1.0")
        return try {
            JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
    }

    /**
     * "שכונה, עיר" - קודם מ-OpenStreetMap (מחזיר שכונות בארץ באופן עקבי),
     * ואם אין תשובה - מהטלפון
     */
    /** שם העיר בלבד (בלי שכונה) - קריאת רשת, להריץ מחוץ ל-UI thread */
    fun cityName(c: Context, loc: Location): String = placeName(c, loc).substringAfterLast(", ")

    @Suppress("DEPRECATION")
    private fun placeName(c: Context, loc: Location): String {
        try {
            val url = "https://nominatim.openstreetmap.org/reverse?format=json&zoom=17" +
                "&lat=%.5f&lon=%.5f".format(Locale.US, loc.latitude, loc.longitude) +
                "&accept-language=" + URLEncoder.encode("he", "UTF-8")
            val addr = getJson(url).optJSONObject("address")
            if (addr != null) {
                val city = listOf("city", "town", "village", "municipality")
                    .map { addr.optString(it) }.firstOrNull { it.isNotBlank() }
                val hood = listOf("neighbourhood", "suburb", "quarter", "residential")
                    .map { addr.optString(it) }.firstOrNull { it.isNotBlank() && it != city }
                val name = listOfNotNull(hood, city).joinToString(", ")
                if (name.isNotBlank()) return name
            }
        } catch (_: Exception) { }
        return try {
            val a = Geocoder(c, Locale("he")).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                ?: return ""
            val city = a.locality ?: a.subAdminArea
            val hood = (a.subLocality ?: a.thoroughfare)?.takeIf { it != city }
            listOfNotNull(hood, city).joinToString(", ")
        } catch (_: Exception) { "" }
    }

    fun describe(code: Int, isDay: Boolean): Pair<String, String> = when (code) {
        0 -> (if (isDay) "☀️" else "🌙") to "בהיר"
        1, 2 -> (if (isDay) "🌤" else "☁️") to "מעונן חלקית"
        3 -> "☁️" to "מעונן"
        45, 48 -> "🌫" to "ערפל"
        in 51..57 -> "🌦" to "טפטוף"
        in 61..67 -> "🌧" to "גשם"
        in 71..77 -> "❄️" to "שלג"
        in 80..82 -> "🌦" to "ממטרים"
        in 95..99 -> "⛈" to "סופת רעמים"
        else -> "🌡" to ""
    }
}

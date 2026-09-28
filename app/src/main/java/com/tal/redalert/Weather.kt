package com.tal.redalert

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
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

    /** מיקום אחרון שידוע למכשיר - גיבוי אם אין מיקום עדכני */
    @SuppressLint("MissingPermission")
    fun lastLocation(c: Context): Location? {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
    }

    /**
     * מיקום עדכני ומדויק: מבקש מיקום חדש מהטלפון (עד 15 שניות),
     * ואם לא הגיע - חוזר למיקום האחרון הידוע.
     */
    @SuppressLint("MissingPermission")
    fun freshLocation(c: Context, done: (Location?) -> Unit) {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val main = Handler(Looper.getMainLooper())
        var finished = false
        fun finish(l: Location?) {
            if (finished) return
            finished = true
            done(l ?: lastLocation(c))
        }

        // מיקום אחרון שנמדד בדקה האחרונה בדיוק גבוה (GPS) - מספיק, בלי לחכות
        lastLocation(c)?.let {
            val ageMs = System.currentTimeMillis() - it.time
            if (ageMs < 60 * 1000 && it.accuracy in 0.1f..40f) { finish(it); return }
        }

        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER))
                add(LocationManager.FUSED_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        }
        if (providers.isEmpty()) { finish(null); return }

        val listeners = mutableListOf<LocationListener>()
        fun stopAll() = listeners.forEach { try { lm.removeUpdates(it) } catch (_: Exception) { } }

        // אוספים מיקומים מכל המקורות ולוקחים את המדויק ביותר.
        // מיקום רשת (לא מדויק) מגיע בדרך כלל ראשון - לכן לא עוצרים עליו.
        var best: Location? = null
        for (p in providers) {
            val l = LocationListener { loc ->
                if (best == null || loc.accuracy < best!!.accuracy) best = loc
                if (loc.accuracy in 0.1f..40f) {   // מדויק מספיק - מסיימים מיד
                    stopAll()
                    main.post { finish(best) }
                }
            }
            listeners += l
            try { lm.requestLocationUpdates(p, 0L, 0f, l, Looper.getMainLooper()) } catch (_: Exception) { }
        }
        main.postDelayed({ stopAll(); finish(best) }, 12_000)
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

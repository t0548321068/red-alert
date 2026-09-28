package com.tal.redalert

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** מזג אוויר לפי מיקום - Open-Meteo (חינמי, בלי מפתח) */
object Weather {

    data class Now(val temp: Int, val code: Int, val isDay: Boolean, val place: String)

    /** מיקום אחרון שידוע למכשיר (לא מדליק GPS - חוסך סוללה) */
    @SuppressLint("MissingPermission")
    fun lastLocation(c: Context): Location? {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
    }

    /** קריאת רשת - להריץ מחוץ ל-UI thread */
    fun fetch(c: Context, loc: Location): Now {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
            .format(Locale.US, loc.latitude, loc.longitude) +
            "&current=temperature_2m,weather_code,is_day&timezone=auto"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val json = try {
            JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
        val cur = json.getJSONObject("current")
        return Now(
            Math.round(cur.getDouble("temperature_2m")).toInt(),
            cur.optInt("weather_code"),
            cur.optInt("is_day", 1) == 1,
            placeName(c, loc)
        )
    }

    @Suppress("DEPRECATION")
    private fun placeName(c: Context, loc: Location): String = try {
        val a = Geocoder(c, Locale("he")).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
        a?.locality ?: a?.subAdminArea ?: ""
    } catch (_: Exception) { "" }

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

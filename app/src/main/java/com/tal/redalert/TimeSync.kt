package com.tal.redalert

import android.content.Context
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL

/**
 * שעה אחידה בכל הטלפונים: הפרש בין השעון של הטלפון לשעון השרת (שמסונכרן ל-NTP).
 * נמדד עם סימן החיים מהשרת (הטלפון כבר ער) - לכל היותר פעם בשעה, בקשה קטנה אחת.
 * השעונים באפליקציה מציגים now() במקום השעה של הטלפון
 */
object TimeSync {
    private const val URL_TIME = "http://151.145.91.93:8080/time"
    @Volatile private var offset = Long.MIN_VALUE
    // נקודת עוגן: שעת השרת ברגע המדידה + השעון הרציף של הטלפון באותו רגע.
    // כך גם אם שעון הטלפון קופץ (תיקון מהסלולר / שינוי ידני) - השעה לא משתנה עד המדידה הבאה
    @Volatile private var anchorServer = 0L
    @Volatile private var anchorElapsed = -1L
    private fun sp(c: Context) = c.getSharedPreferences("timesync", Context.MODE_PRIVATE)

    /** השעה לפי השרת (אם עוד לא נמדד - השעה של הטלפון) */
    fun now(c: Context): Long {
        val e = SystemClock.elapsedRealtime()
        if (anchorElapsed in 0..e && e - anchorElapsed < 24 * 3600_000L) return anchorServer + (e - anchorElapsed)
        if (offset == Long.MIN_VALUE) offset = sp(c).getLong("offset", 0L)   // אחרי הפעלה מחדש של הטלפון - ההפרש האחרון
        return System.currentTimeMillis() + offset
    }

    /** מודד מחדש אם עברה שעה מהמדידה האחרונה (או force). להריץ מחוץ ל-UI thread */
    fun sync(c: Context, force: Boolean = false) {
        val p = sp(c)
        if (!force && System.currentTimeMillis() - p.getLong("at", 0) < 60 * 60_000L) return
        // שלוש מדידות, לוקחים את זו עם זמן התגובה הקצר ביותר (הכי מדויקת)
        var best: Pair<Long, Long>? = null   // rtt to offset
        repeat(3) {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val conn = URL(URL_TIME).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000; conn.readTimeout = 4000; conn.useCaches = false
                val server = try { conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.trim().toLong() } finally { conn.disconnect() }
                val rtt = SystemClock.elapsedRealtime() - t0
                // השרת ענה בערך באמצע הדרך
                val off = server + rtt / 2 - System.currentTimeMillis()
                if (rtt < 2000 && (best == null || rtt < best!!.first)) best = rtt to off
            } catch (_: Exception) { }
        }
        val b = best ?: return
        offset = b.second
        anchorElapsed = SystemClock.elapsedRealtime(); anchorServer = System.currentTimeMillis() + b.second
        p.edit().putLong("offset", b.second).putLong("at", System.currentTimeMillis()).apply()
    }
}

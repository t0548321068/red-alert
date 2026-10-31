package com.tal.redalert

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * דיווח לשרת (לאתר של טל):
 * - פעם ב-10 דקות, יחד עם סימן החיים מהשרת (הטלפון כבר ער - בלי בזבוז סוללה): גרסה, דגם, מוגן או לא
 * - קריסה: נשמרת בטלפון ברגע הקריסה, ונשלחת בפעם הבאה שהאפליקציה רצה
 * בלי שום מידע אישי - רק המזהה המקוצר של המכשיר (אותו קוד של הבטא)
 */
object Reporter {
    /** קודם https (הדיווח כולל אזורים); אם נכשל (למשל DNS לא עונה) - ישירות לכתובת ה-IP */
    private val SERVERS = listOf("https://tzofar-app.duckdns.org", "http://151.145.91.93:8080")
    private fun sp(c: Context) = c.getSharedPreferences("reporter", Context.MODE_PRIVATE)

    private fun version(c: Context) = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    /** מופעל פעם אחת בהפעלת האפליקציה: תופס קריסות, ושולח קריסה שעוד לא נשלחה */
    fun install(c: Context) {
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val err = android.util.Log.getStackTraceString(e).take(12000)
                sp(app).edit().putString("crash", JSONObject().put("err", err).put("at", System.currentTimeMillis())
                    .put("v", version(app)).toString()).commit()   // commit - לפני שהתהליך נסגר
            } catch (_: Exception) { }
            prev?.uncaughtException(t, e)
        }
        Thread { sendCrash(app) }.start()
    }

    /** עם כל סימן חיים מהשרת (פעם ב-10 דקות) */
    fun onHeartbeat(c: Context) {
        val now = System.currentTimeMillis()
        if (now - sp(c).getLong("pingAt", 0) < 9 * 60_000L) return
        sp(c).edit().putLong("pingAt", now).apply()
        Thread {
            try {
                val on = Prefs.enabled(c) && !Prefs.scheduleOffNow(c)
                call("/ping?d=${Prefs.deviceCode(c)}&v=${enc(version(c))}&m=${enc(Build.MODEL ?: "")}&on=${if (on) 1 else 0}")
            } catch (_: Exception) { }
            sendCrash(c)
        }.start()
    }

    private fun sendCrash(c: Context) {
        val s = sp(c).getString("crash", null) ?: return
        try {
            val j = JSONObject(s).put("d", Prefs.deviceCode(c)).put("m", Build.MODEL ?: "")
            val code = call("/crash", j.toString())
            if (code in 200..299 || code == 400) sp(c).edit().remove("crash").apply()   // 400 = פגום, אין טעם לנסות שוב
        } catch (_: Exception) { }
    }

    /** דיווח על בעיה ממסך "יצירת קשר" - להריץ מחוץ ל-UI thread. true = הגיע לשרת */
    fun sendReport(c: Context, text: String, info: String): Boolean = try {
        val j = JSONObject().put("d", Prefs.deviceCode(c)).put("v", version(c)).put("m", Build.MODEL ?: "")
            .put("text", text).put("info", info)
        call("/report", j.toString(), 8000, "application/json; charset=utf-8") in 200..299
    } catch (_: Exception) { false }

    /** בקשה לשרת (POST כשיש body) - מנסה כל כתובת לפי הסדר. מחזיר קוד תשובה, או -1 אם אף אחת לא ענתה */
    private fun call(path: String, body: String? = null, timeout: Int = 5000, type: String = "application/json"): Int {
        for (s in SERVERS) {
            try {
                val conn = URL(s + path).openConnection() as HttpURLConnection
                conn.connectTimeout = timeout; conn.readTimeout = timeout
                if (body != null) {
                    conn.requestMethod = "POST"; conn.doOutput = true
                    conn.setRequestProperty("Content-Type", type)
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                return try { conn.responseCode } finally { conn.disconnect() }
            } catch (_: Exception) { }
        }
        return -1
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

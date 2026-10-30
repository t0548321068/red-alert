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
    private const val SERVER = "http://151.145.91.93:8080"
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
                val u = "$SERVER/ping?d=${Prefs.deviceCode(c)}&v=${enc(version(c))}&m=${enc(Build.MODEL ?: "")}&on=${if (on) 1 else 0}"
                (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 5000 }.let {
                    try { it.responseCode } finally { it.disconnect() }
                }
            } catch (_: Exception) { }
            sendCrash(c)
        }.start()
    }

    private fun sendCrash(c: Context) {
        val s = sp(c).getString("crash", null) ?: return
        try {
            val j = JSONObject(s).put("d", Prefs.deviceCode(c)).put("m", Build.MODEL ?: "")
            val conn = URL("$SERVER/crash").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(j.toString().toByteArray()) }
            val code = try { conn.responseCode } finally { conn.disconnect() }
            if (code in 200..299 || code == 400) sp(c).edit().remove("crash").apply()   // 400 = פגום, אין טעם לנסות שוב
        } catch (_: Exception) { }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

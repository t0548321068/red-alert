package com.tal.tzofarinbox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** השרת: כניסה עם קוד האתר (כמו ניהול צופר), רשימת הפניות, תשובה / סימון / מחיקה */
object Api {
    private const val SITE = "https://tzofar-app.duckdns.org"
    private fun sp(c: Context) = c.getSharedPreferences("inbox", Context.MODE_PRIVATE)
    fun loggedIn(c: Context) = !sp(c).getString("token", "").isNullOrEmpty()

    /** null = נכנס, אחרת הודעת שגיאה */
    fun login(c: Context, code: String): String? {
        val conn = URL("$SITE/api/login").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
            when (conn.responseCode) {
                200 -> {
                    val tok = conn.headerFields["Set-Cookie"].orEmpty().firstNotNullOfOrNull { Regex("tz=([^;]+)").find(it)?.groupValues?.get(1) }
                    if (tok == null) "אין תשובה מהשרת" else { sp(c).edit().putString("token", tok).apply(); null }
                }
                401 -> "קוד שגוי"
                429 -> "יותר מדי ניסיונות – נסה בעוד דקה"
                else -> "השרת לא זמין"
            }
        } catch (_: Exception) { "אין חיבור לשרת" } finally { conn.disconnect() }
    }

    /** GET (body=null) או POST. מחזיר JSON, "login" אם צריך להיכנס שוב, או null אם נכשל */
    private fun call(c: Context, body: JSONObject?): String? {
        val tok = sp(c).getString("token", "") ?: return "login"
        val conn = URL("$SITE/api/inbox").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000; conn.readTimeout = 10000
            conn.setRequestProperty("Cookie", "tz=$tok")
            if (body != null) {
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            when (conn.responseCode) {
                200 -> conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                401 -> { sp(c).edit().remove("token").apply(); "login" }
                else -> null
            }
        } catch (_: Exception) { null } finally { conn.disconnect() }
    }

    /** הפניות, או "login" / null */
    fun list(c: Context): Any? {
        val r = call(c, null) ?: return null
        if (r == "login") return r
        return try { JSONObject(r).optJSONArray("reports") ?: JSONArray() } catch (_: Exception) { null }
    }

    fun reply(c: Context, id: String, text: String) = call(c, JSONObject().put("id", id).put("reply", text))
    fun markRead(c: Context, id: String, read: Boolean) = call(c, JSONObject().put("id", id).put("read", read))
    fun delete(c: Context, id: String) = call(c, JSONObject().put("id", id).put("del", true))
}

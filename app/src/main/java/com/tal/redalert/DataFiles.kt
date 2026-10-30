package com.tal.redalert

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * קבצי נתונים שאפשר לעדכן מהשרת בלי גרסה חדשה: הגדרות (config.json), זמני מרחב מוגן (migun.txt),
 * יישובים וגבולות (areas.js, districts.json), מזהי צופר, והקלטות מתוקנות (voice/...).
 * השרת מחזיק רק מה שהשתנה; כל קובץ שלא הורד - נקרא כרגיל מתוך האפליקציה.
 */
object DataFiles {
    private const val SERVER = "http://151.145.91.93:8080/data"

    /** נקבע ב-App בהפעלה - כך גם פונקציות בלי Context (סיווג ההתרעות) קוראות את ההגדרות */
    @Volatile var app: Context? = null
        private set
    fun init(c: Context) { app = c.applicationContext }

    private fun dir(c: Context) = File(c.filesDir, "data")
    /** הקובץ שהורד מהשרת (אם יש) */
    fun file(c: Context, path: String): File? = File(dir(c), path).takeIf { it.isFile }
    fun open(c: Context, path: String): InputStream = file(c, path)?.inputStream() ?: c.assets.open(path)
    fun text(c: Context, path: String): String = open(c, path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    fun exists(c: Context, path: String): Boolean =
        file(c, path) != null || try { c.assets.open(path).close(); true } catch (_: Exception) { false }

    /** המפה (WebView) טוענת קבצים מתוך האפליקציה - קובץ שעודכן מהשרת (למשל areas.js) נטען במקומו */
    fun web(c: Context, url: String): android.webkit.WebResourceResponse? {
        val path = url.substringAfter("file:///android_asset/", "").substringBefore('#').substringBefore('?')
        if (path.isEmpty()) return null
        val f = file(c, path) ?: return null
        val mime = when (f.extension) { "js" -> "application/javascript"; "json" -> "application/json"; else -> "text/plain" }
        return android.webkit.WebResourceResponse(mime, "utf-8", f.inputStream())
    }

    private fun sp(c: Context) = c.getSharedPreferences("data_files", Context.MODE_PRIVATE)
    /** גרסת הנתונים שכבר אצלנו - משווים לגרסה שמגיעה בסימן החיים של השרת */
    fun version(c: Context): String = sp(c).getString("v", "") ?: ""

    @Volatile private var syncing = false

    /** בדיקה מול השרת (לכל היותר פעם בשעה, או מיד כשהגרסה בשרת השתנתה), והורדה של מה שחדש */
    fun sync(c: Context, force: Boolean = false) {
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        if (syncing || (!force && now - sp(app).getLong("at", 0) < 60 * 60_000L)) return
        syncing = true
        sp(app).edit().putLong("at", now).apply()
        Thread {
            try { if (run(app)) onChanged() } catch (_: Exception) { } finally { syncing = false }
        }.start()
    }

    /** מחזיר true אם משהו השתנה */
    private fun run(c: Context): Boolean {
        val idx = JSONObject(get("")?.toString(Charsets.UTF_8) ?: return false)
        val v = idx.optString("v")
        if (v.isEmpty() || v == version(c)) return false
        val files = idx.getJSONObject("files")
        val have = JSONObject(sp(c).getString("files", "{}") ?: "{}")
        var changed = false
        var complete = true
        for (path in files.keys()) {
            val hash = files.getString(path)
            if (have.optString(path) == hash && file(c, path) != null) continue
            val bytes = get(path)
            if (bytes == null) { complete = false; break }   // השרת עמוס / הגבלה - נמשיך בפעם הבאה
            val f = File(dir(c), path)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            have.put(path, hash)
            changed = true
            sp(c).edit().putString("files", have.toString()).apply()
        }
        // קובץ שהוסר מהשרת - חוזרים לגרסה שבתוך האפליקציה
        for (path in have.keys().asSequence().toList()) if (!files.has(path)) {
            File(dir(c), path).delete(); have.remove(path); changed = true
        }
        sp(c).edit().putString("files", have.toString()).apply()
        if (complete) sp(c).edit().putString("v", v).apply()
        return changed
    }

    private fun get(path: String): ByteArray? {
        val conn = URL(if (path.isEmpty()) SERVER else "$SERVER/" + path.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 15000
        return try { if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null }
        catch (_: Exception) { null } finally { conn.disconnect() }
    }

    /** קבצים חדשים - טוענים מחדש בפעם הבאה שצריך אותם */
    private fun onChanged() {
        Config.reset()
        AreaData.reset()
        Speaker.resetIndex()
    }
}

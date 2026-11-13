package com.tal.tzofaradmin

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * עדכון אוטומטי כמו בצופר חירום: בכל פתיחה בודק בגיטהאב (גרסה "admin" - "ניהול צופר · 1.0.2"),
 * ואם יש גרסה חדשה - מוריד ומתקין לבד.
 */
object Update {
    private const val API = "https://api.github.com/repos/t0548321068/red-alert/releases/tags/admin"
    @Volatile var status = ""
    @Volatile var busy = false
    @Volatile var needPermission = false
    @Volatile var lastCheck = 0L
    @Volatile var latestSeen = ""

    fun current(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    private fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
    private fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    private fun latest(api: String = API): Pair<String, String>? {
        val conn = URL(api).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val j = try { if (conn.responseCode != 200) return null; JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }) } finally { conn.disconnect() }
        val v = Regex("(\\d+(?:\\.\\d+)+)\\s*$").find(j.optString("name"))?.groupValues?.get(1) ?: return null
        val a = j.optJSONArray("assets") ?: return null
        val url = (0 until a.length()).map { a.getJSONObject(it).optString("browser_download_url") }.firstOrNull { it.endsWith(".apk") } ?: return null
        return v to url
    }

    fun check(c: Context, refresh: () -> Unit) {
        if (busy) return
        busy = true; lastCheck = System.currentTimeMillis(); status = "בודק עדכונים…"; refresh()
        Thread {
            try {
                val l = latest()
                when {
                    l == null -> status = "אין חיבור לגיטהאב"
                    !newer(l.first, current(c)) -> { latestSeen = l.first; status = "" }
                    !c.packageManager.canRequestPackageInstalls() -> { needPermission = true; status = "יש גרסה ${l.first} – צריך לאשר התקנת עדכונים" }
                    else -> {
                        needPermission = false
                        status = "מוריד גרסה ${l.first}…"; refresh()
                        val f = File(c.cacheDir, "admin-update.apk")
                        val conn = URL(l.second).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
                        try { conn.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } } } finally { conn.disconnect() }
                        status = "מתקין גרסה ${l.first}…"; refresh()
                        install(c, f)
                    }
                }
            } catch (_: Exception) { status = "ההורדה נכשלה" }
            busy = false; refresh()
        }.start()
    }

    // ---- עריכת צופר: התקנה מכאן (אפליקציה נפרדת, גרסה "editor" בגיטהאב). אחרי ההתקנה היא מתעדכנת לבד ----
    const val EDITOR = "com.tal.tzofareditor"
    private const val EDITOR_API = "https://api.github.com/repos/t0548321068/red-alert/releases/tags/editor"
    @Volatile var editorStatus = ""
    @Volatile var editorBusy = false

    fun editorInstalled(c: Context) = try { c.packageManager.getPackageInfo(EDITOR, 0); true } catch (_: Exception) { false }

    /** מוריד את עריכת צופר ומתקין. בהתקנה הראשונה אנדרואיד שואל פעם אחת "להתקין?" */
    fun installEditor(c: Context, refresh: () -> Unit) {
        if (editorBusy) return
        if (!c.packageManager.canRequestPackageInstalls()) { needPermission = true; editorStatus = "צריך לאשר התקנת אפליקציות"; refresh(); return }
        editorBusy = true; editorStatus = "בודק…"; refresh()
        Thread {
            try {
                val l = latest(EDITOR_API)
                if (l == null) editorStatus = "אין חיבור לגיטהאב"
                else {
                    editorStatus = "מוריד גרסה ${l.first}…"; refresh()
                    val f = File(c.cacheDir, "editor.apk")
                    val conn = URL(l.second).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
                    try { conn.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } } } finally { conn.disconnect() }
                    editorStatus = "מתקין…"; refresh()
                    val pi = c.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(EDITOR) }
                    val id = pi.createSession(params)
                    pi.openSession(id).use { s ->
                        s.openWrite("editor.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                        val intent = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java).putExtra("editor", true),
                            PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                        s.commit(intent.intentSender)
                    }
                }
            } catch (_: Exception) { editorStatus = "ההורדה נכשלה" }
            editorBusy = false; refresh()
        }.start()
    }

    private fun install(c: Context, f: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            // אנדרואיד 12+: עדכון של עצמה בלי לחיצה על "עדכן" (אחרי שהעדכון הראשון אושר)
            if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("admin.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            s.commit(intent.intentSender)
        }
    }
}

/** תוצאת ההתקנה: אם אנדרואיד מבקש אישור - פותחים את מסך האישור; אחרי התקנה - האפליקציה נפתחת מחדש */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                (i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> if (i.getBooleanExtra("editor", false)) Update.editorStatus = ""
            else -> if (i.getBooleanExtra("editor", false)) Update.editorStatus = "ההתקנה בוטלה" else Update.status = "ההתקנה בוטלה"
        }
    }
}

/** אחרי עדכון - פותחים את האפליקציה מחדש */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            try { c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }
}

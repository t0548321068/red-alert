package com.tal.redalert.editor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** עדכון אוטומטי של עריכת צופר: בגיטהאב גרסה "editor" בשם "עריכת צופר · 1.0.2" - משווים לגרסה המותקנת */
object Update {
    private const val API = "https://api.github.com/repos/t0548321068/red-alert/releases/tags/editor"
    @Volatile var status = ""
    @Volatile var busy = false
    @Volatile var needPermission = false
    @Volatile var lastCheck = 0L

    fun current(c: Context): String = c.getString(com.tal.redalert.R.string.editor_version)
    private fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
    private fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    fun maybeCheck(c: Context) {
        if (System.currentTimeMillis() - lastCheck > 60_000 || (needPermission && c.packageManager.canRequestPackageInstalls())) check(c) { Editor.refreshMenus() }
    }

    private fun latest(): Pair<String, String>? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val j = try { if (conn.responseCode != 200) return null; JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }) } finally { conn.disconnect() }
        val n = Regex("(\\d+(?:\\.\\d+)+)\\s*$").find(j.optString("name"))?.groupValues?.get(1) ?: return null
        val a = j.optJSONArray("assets") ?: return null
        val url = (0 until a.length()).map { a.getJSONObject(it).optString("browser_download_url") }.firstOrNull { it.endsWith(".apk") } ?: return null
        return n to url
    }

    /** background = מהבדיקה ברקע: בלי לפתוח את האפליקציה אחרי ההתקנה. done - בסוף (גם כשאין עדכון) */
    fun check(c: Context, background: Boolean = false, done: (() -> Unit)? = null, refresh: () -> Unit) {
        if (busy) { done?.invoke(); return }
        busy = true; lastCheck = System.currentTimeMillis()
        val app = c.applicationContext
        app.getSharedPreferences("editor_update", Context.MODE_PRIVATE).edit().putBoolean("reopen", !background).apply()
        Thread {
            try {
                val l = latest()
                when {
                    l == null -> status = ""
                    !newer(l.first, current(app)) -> status = ""
                    !app.packageManager.canRequestPackageInstalls() -> { needPermission = true; status = "יש גרסה חדשה – צריך לאשר התקנת עדכונים" }
                    else -> {
                        needPermission = false
                        status = "מוריד גרסה חדשה…"; refresh()
                        val f = File(app.cacheDir, "editor-update.apk")
                        val conn = URL(l.second).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
                        try { conn.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } } } finally { conn.disconnect() }
                        status = "מתקין…"; refresh()
                        install(app, f)
                    }
                }
            } catch (_: Exception) { status = "ההורדה נכשלה" }
            busy = false; refresh(); done?.invoke()
        }.start()
    }

    private fun install(c: Context, f: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("editor.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            s.commit(intent.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
                // ברקע אי אפשר לפתוח מסך - התראה: לחיצה פותחת את אישור ההתקנה
                if (!c.getSharedPreferences("editor_update", Context.MODE_PRIVATE).getBoolean("reopen", true)) UpdateJob.notify(c, confirm)
                else try { c.startActivity(confirm) } catch (_: Exception) { UpdateJob.notify(c, confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Update.status = "ההתקנה בוטלה"
        }
    }
}

/** אחרי עדכון - פותחים מחדש */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // עדכון שהתחיל ברקע - לא פותחים את האפליקציה באמצע מה שעושים בטלפון
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED && c.getSharedPreferences("editor_update", Context.MODE_PRIVATE).getBoolean("reopen", true))
            try { c.startActivity(Intent(c, com.tal.redalert.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }
}

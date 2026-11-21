package com.tal.tzofarinbox

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** עדכון אוטומטי כמו בניהול צופר: בכל פתיחה בודק (גרסה "inbox" - "פניות צופר · 1.0.0"), ומתקין לבד */
object Update {
    private const val APK = "https://github.com/t0548321068/red-alert/releases/download/inbox/tzofar-inbox.apk"
    @Volatile var status = ""
    @Volatile private var busy = false

    fun current(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    private fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
    private fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    private fun latest(): String? = try {
        val conn = URL("https://tzofar-app.duckdns.org/apps").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        try { if (conn.responseCode == 200) JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).optString("inbox").ifEmpty { null } else null }
        finally { conn.disconnect() }
    } catch (_: Exception) { null }

    fun check(c: Context, refresh: () -> Unit) {
        if (busy) return
        busy = true
        Thread {
            try {
                val v = latest()
                if (v != null && newer(v, current(c))) {
                    if (!c.packageManager.canRequestPackageInstalls()) status = "יש גרסה $v – צריך לאשר התקנת עדכונים"
                    else {
                        status = "מוריד גרסה $v…"; refresh()
                        val f = File(c.cacheDir, "inbox-update.apk")
                        val conn = URL(APK).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
                        try { conn.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } } } finally { conn.disconnect() }
                        status = "מתקין גרסה $v…"; refresh()
                        install(c, f)
                    }
                }
            } catch (_: Exception) { status = "העדכון נכשל" }
            busy = false; refresh()
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
            s.openWrite("inbox.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            s.commit(intent.intentSender)
        }
    }
}

/** אנדרואיד מבקש אישור - פותחים את מסך האישור */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                (i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Update.status = ""
            else -> Update.status = "ההתקנה בוטלה"
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

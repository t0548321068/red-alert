package com.tal.tzofarrelay

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
 * עדכון הממסר מתוך האפליקציה: בודק את הגרסה האחרונה בגיטהאב (גרסה "relay"), מוריד ומתקין.
 * באנדרואיד 8.1 אנדרואיד מבקש לאשר את ההתקנה בלחיצה אחת. אחרי ההתקנה הממסר עולה לבד (BootReceiver)
 */
object Update {
    private const val API = "https://api.github.com/repos/t0548321068/red-alert/releases/tags/relay"
    @Volatile var status = ""        // מה להציג ליד הכפתור
    @Volatile var busy = false

    fun current(c: Context): Int = try {
        @Suppress("DEPRECATION") c.packageManager.getPackageInfo(c.packageName, 0).versionCode
    } catch (_: Exception) { 0 }

    /** (מספר הגרסה האחרונה, קישור להורדה) - מהשם "ממסר צופר · N" */
    private fun latest(): Pair<Int, String>? {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val j = try { if (conn.responseCode != 200) return null; JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }) } finally { conn.disconnect() }
        val n = Regex("(\\d+)\\s*$").find(j.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val a = j.optJSONArray("assets") ?: return null
        val url = (0 until a.length()).map { a.getJSONObject(it).optString("browser_download_url") }.firstOrNull { it.endsWith(".apk") } ?: return null
        return n to url
    }

    /** כפתור "בדיקת עדכונים": בודק, ואם יש גרסה חדשה - מוריד ומתקין. רץ ברקע */
    fun checkAndInstall(c: Context, refresh: () -> Unit) {
        if (busy) return
        busy = true; status = "בודק…"; refresh()
        Thread {
            try {
                val l = latest()
                when {
                    l == null -> status = "אין חיבור לגיטהאב"
                    l.first <= current(c) -> status = "מעודכן · גרסה 1.${current(c)}"
                    !c.packageManager.canRequestPackageInstalls() -> {
                        status = "צריך לאשר התקנה מהממסר – ואז ללחוץ שוב"
                        c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    else -> {
                        status = "מוריד גרסה 1.${l.first}…"; refresh()
                        val f = File(c.cacheDir, "relay-update.apk")
                        val conn = URL(l.second).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
                        try { conn.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } } } finally { conn.disconnect() }
                        status = "מתקין…"; refresh()
                        install(c, f)
                    }
                }
            } catch (_: Exception) { status = "ההורדה נכשלה" }
            busy = false; refresh()
        }.start()
    }

    private fun install(c: Context, f: File) {
        val pi = c.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(c.packageName) })
        pi.openSession(id).use { s ->
            s.openWrite("relay.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = PendingIntent.getBroadcast(c, id, Intent(c, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            s.commit(intent.intentSender)
        }
    }
}

/** תוצאת ההתקנה: אנדרואיד מבקש אישור - פותחים את מסך האישור */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                (i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit   // הממסר מתחלף ועולה מחדש לבד
            else -> Update.status = "ההתקנה בוטלה – אפשר לנסות שוב"
        }
    }
}

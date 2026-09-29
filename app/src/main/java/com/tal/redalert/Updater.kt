package com.tal.redalert

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** בדיקה והורדה של גרסה חדשה מ-GitHub Releases */
object Updater {
    private const val API = "https://api.github.com/repos/t0548321068/red-alert/releases/latest"
    private const val MIME = "application/vnd.android.package-archive"

    private const val BETA_API = "https://api.github.com/repos/t0548321068/red-alert/releases/tags/beta"

    fun currentVersion(c: Context): String =
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"

    fun buildNumber(c: Context): Int = c.getString(R.string.build_number).toIntOrNull() ?: 0
    fun isBetaBuild(c: Context): Boolean = c.resources.getBoolean(R.bool.is_beta)

    /** לתצוגה: "1.20" או "1.20 בטא 153" */
    fun versionLabel(c: Context): String =
        currentVersion(c) + if (isBetaBuild(c)) " בטא ${buildNumber(c)}" else ""

    /** עדכון זמין: label לתצוגה, key לזיכרון "מאוחר יותר", url להורדה */
    data class Update(val label: String, val key: String, val url: String)

    /** הגרסה הכי מתאימה להתקנה: יציבה חדשה, או (אם ביקשו) בטא חדשה */
    private fun best(c: Context): Update? {
        val cur = currentVersion(c)
        fetch(API)?.let { (ver, url, _) ->
            if (isNewer(ver, cur)) return Update(ver, ver, url)
        }
        if (Prefs.betaUpdates(c)) fetch(BETA_API)?.let { (ver, url, build) ->
            if (build > buildNumber(c) && !isNewer(cur, ver)) return Update("$ver בטא $build", "beta-$build", url)
        }
        return null
    }

    /**
     * silent = בדיקה אוטומטית: בלי הודעה כשאין עדכון,
     * ובלי פופ-אפ לגרסה שכבר נבחר עבורה "מאוחר יותר" (אז רק דרך ההגדרות)
     */
    fun check(a: Activity, silent: Boolean) {
        Thread {
            var failed = false
            val u = try { best(a) } catch (_: Exception) { failed = true; null }
            a.runOnUiThread {
                if (a.isFinishing) return@runOnUiThread
                when {
                    u != null ->
                        if (!silent || Prefs.skippedVersion(a) != u.key) offer(a, u)
                    failed -> if (!silent) toast(a, "לא ניתן לבדוק עדכונים כרגע")
                    !silent -> toast(a, "יש לך את הגרסה האחרונה (${versionLabel(a)})")
                }
            }
        }.start()
    }

    /** לשירות ברקע: מחזיר גרסה חדשה (label) אם יש ולא נדחתה, אחרת null */
    fun pendingVersion(c: Context): String? {
        val u = try { best(c) } catch (_: Exception) { null } ?: return null
        if (Prefs.skippedVersion(c) == u.key) return null
        return u.label
    }

    /** (גרסה, קישור ל-APK, מספר בנייה) */
    private fun fetch(api: String): Triple<String, String, Int>? {
        val conn = URL(api).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val json = try {
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
        val body = json.optString("body")
        fun field(k: String) = Regex("$k=([\\w.]+)").find(body)?.groupValues?.get(1)
        val version = field("version") ?: json.optString("tag_name").removePrefix("v")
        val build = field("build")?.toIntOrNull() ?: 0
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val url = assets.getJSONObject(i).optString("browser_download_url")
            if (url.endsWith(".apk")) return Triple(version, url, build)
        }
        return null
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun offer(a: Activity, u: Update) {
        val beta = u.key.startsWith("beta")
        AlertDialog.Builder(a, Prefs.dialogTheme(a))
            .setTitle((if (beta) "🧪 גרסת בטא זמינה – " else "🆕 גרסה חדשה זמינה – ") + u.label)
            .setMessage("הגרסה שלך: ${versionLabel(a)}\nלהתקין עכשיו?" +
                (if (beta) "\n\nגרסת בטא – לפני שחרור לכולם, יכולות להיות בה תקלות." else "") +
                "\n\n\"מאוחר יותר\" – אפשר לעדכן בכל זמן דרך ⚙ ← בדיקת עדכונים")
            .setPositiveButton("התקן עכשיו") { _, _ -> download(a, u.label.replace(" ", "-"), u.url) }
            .setNegativeButton("מאוחר יותר") { _, _ -> Prefs.setSkippedVersion(a, u.key) }
            .setCancelable(false)
            .show()
    }

    private fun download(a: Activity, version: String, url: String) {
        // בלי אישור "התקנה ממקורות לא ידועים" אי אפשר להתקין
        if (!a.packageManager.canRequestPackageInstalls()) {
            toast(a, "יש לאשר התקנה מהאפליקציה הזו, ואז ללחוץ שוב על הורדה")
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${a.packageName}")))
            return
        }

        val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("צבע אדום $version")
            .setMimeType(MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "red-alert-$version.apk")
        val id = dm.enqueue(req)
        toast(a, "מוריד גרסה $version…")

        val app = a.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(id)
                if (uri == null) {
                    toast(app, "ההורדה נכשלה")
                    return
                }
                app.startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
    }

    private fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()
}

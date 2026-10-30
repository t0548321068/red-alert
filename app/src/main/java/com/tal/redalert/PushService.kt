package com.tal.redalert

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * התראות מיידיות מהשרת שלנו (דרך Firebase): השרת בודק את פיקוד העורף וצופר ושולח לכל הטלפונים.
 * ההתרעה עוברת לשירות ההתרעות כמו מכל מקור אחר (אותו סינון אזורים ואותה מניעת כפילות)
 */
class PushService : FirebaseMessagingService() {

    companion object {
        const val TOPIC = "alerts"
        private const val STATUS_URL = "http://151.145.91.93:8080/status"

        /**
         * בדיקת השרת (כפתור בעמוד המקורות): אילו מקורות תקינים אצלו, או null אם לא ענה.
         * השרת גם שולח מיד סימן חיים דרך Firebase - כך נבדקת כל הדרך עד הטלפון
         */
        fun checkServer(): List<String>? = try {
            val c = java.net.URL(STATUS_URL).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.useCaches = false
            try {
                if (c.responseCode != 200) null else {
                    val arr = org.json.JSONObject(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).getJSONArray("up")
                    List(arr.length()) { arr.getString(it) }
                }
            } finally { c.disconnect() }
        } catch (_: Exception) { null }
        fun subscribe(c: android.content.Context) {
            try { FirebaseMessaging.getInstance().subscribeToTopic(TOPIC) } catch (_: Exception) { }
            // הודעות על תקלות בשרת - רק למכשירי הבטא (שלך)
            try {
                if (Prefs.betaAllowed(c)) FirebaseMessaging.getInstance().subscribeToTopic("admin")
                else FirebaseMessaging.getInstance().unsubscribeFromTopic("admin")
            } catch (_: Exception) { }
        }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        if (d["type"] == "hb") {   // סימן חיים מהשרת + אילו מקורות תקינים אצלו
            AlertService.onServerHeartbeat(this, (d["up"] ?: "").split(',').filter { it.isNotEmpty() })
            // קבצי הנתונים בשרת השתנו (הגדרות / יישובים / הקלטות) - מורידים מיד
            d["dv"]?.takeIf { it.isNotEmpty() && it != DataFiles.version(this) }?.let { DataFiles.sync(this, force = true) }
            return
        }
        if (d["type"] == "update") { AlertService.onServerUpdate(); return }   // גרסה חדשה פורסמה
        if (d["type"] == "admin") { if (Prefs.betaAllowed(this)) showAdmin(d["text"] ?: return); return }
        if (d["type"] != "alert") return
        val title = d["title"] ?: return
        val src = d["src"] ?: ""
        var areas = (d["areas"] ?: "").split('|').map { it.trim() }.filter { it.isNotEmpty() }
        // טלגרם: רק שמות יישובים אמיתיים (כמו כשהטלפון קרא את הערוצים בעצמו)
        if (src.startsWith("tg:")) areas = areas.filter { AreaData.isKnown(this, it) }
        if (areas.isEmpty()) return
        val at = d["at"]?.toLongOrNull() ?: System.currentTimeMillis()
        val svc = AlertService.instance
        if (svc != null) {
            // הטלפון נשאר ער עד שההתרעה טופלה (בלי זה הוא יכול להירדם לפני הצלצול)
            val wl = (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "redalert:push").apply { acquire(30_000) }
            Thread {
                // השירות נסגר באמצע / תקלה - לפחות התראה רגילה, שההתרעה לא תלך לאיבוד
                try { svc.handle(title, areas, at, "push:$src") } catch (_: Exception) { AlertService.fallbackNotify(this, title, areas) }
                finally { try { if (wl.isHeld) wl.release() } catch (_: Exception) { } }
            }.start()
        } else {
            // השירות לא רץ - מעירים אותו (הודעה בעדיפות גבוהה מאפשרת להפעיל אותו מהרקע)
            if (!Prefs.enabled(this)) return
            val i = Intent(this, AlertService::class.java).setAction(AlertService.ACTION_PUSH_ALERT)
                .putExtra("title", title).putExtra("areas", areas.toTypedArray())
                .putExtra("at", at).putExtra("src", "push:$src")
            // אנדרואיד לא הרשה להפעיל את השירות - לפחות התראה רגילה, שההתרעה לא תלך לאיבוד
            try { startForegroundService(i) } catch (_: Exception) { AlertService.fallbackNotify(this, title, areas) }
        }
    }

    override fun onNewToken(token: String) { subscribe(this) }

    /** הודעה מהשרת על מקור שלא עונה / חזר לעבוד (רק במכשירי הבטא) */
    private fun showAdmin(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.getNotificationChannel("admin") == null) nm.createNotificationChannel(
            android.app.NotificationChannel("admin", "תקלות בשרת", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(text.hashCode(), android.app.Notification.Builder(this, "admin")
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setContentTitle("שרת ההתרעות")
            .setContentText(text)
            .setAutoCancel(true)
            .build())
    }
}

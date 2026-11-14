package com.tal.redalert.editor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.net.HttpURLConnection
import java.net.URL

/**
 * עדכון מיידי כמו בצופר חירום: מחובר כל הזמן ל-ntfy.sh. ברגע שגרסה חדשה של עריכת צופר מתפרסמת,
 * גיטהאב שולח הודעה לנושא, והעדכון יורד ומותקן לבד - בלי לפתוח את האפליקציה.
 * לא סומכים על תוכן ההודעה: כל הודעה רק מפעילה בדיקה מול גיטהאב עצמו.
 */
class UpdateListener : Service() {
    companion object {
        /** חייב להיות זהה לנושא ב-.github/workflows/editor.yml */
        const val TOPIC = "redalert-tal-editor-7f3k9q"
        private const val CHANNEL = "editor_listen"
        private const val ID = 7302

        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, UpdateListener::class.java)) } catch (_: Exception) { }   // ברקע ייתכן שאסור - הבדיקה התקופתית תנסה שוב
        }
    }

    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "עדכון אוטומטי של עריכת צופר", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(com.tal.redalert.R.mipmap.ic_editor)
            .setContentTitle("עריכת צופר").setContentText("מחכה לגרסה חדשה – מתעדכנת לבד").setOngoing(true).build()
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        if (!running) { running = true; Thread(::loop, "editor-update-push").start() }
        return START_STICKY
    }

    override fun onDestroy() { running = false; try { conn?.disconnect() } catch (_: Exception) { }; super.onDestroy() }

    private fun loop() {
        while (running) {
            try { listen() } catch (_: Exception) { }
            if (running) Thread.sleep(10_000)
        }
    }

    private fun listen() {
        // בכל התחברות - בדיקה אחת (אולי גרסה התפרסמה כשלא היה חיבור)
        if (System.currentTimeMillis() - Update.lastCheck > 10 * 60_000) Update.check(this, background = true) { }   // לא יותר מדי פניות לגיטהאב
        val c = URL("https://ntfy.sh/$TOPIC/json").openConnection() as HttpURLConnection
        conn = c
        c.connectTimeout = 15_000; c.readTimeout = 90_000   // ntfy שולח סימן חיים כל 45 שניות
        try {
            if (c.responseCode != 200) return
            c.inputStream.bufferedReader().use { r ->
                while (running) {
                    val line = r.readLine() ?: break
                    if (line.contains("\"event\":\"message\"")) Update.check(this, background = true) { }
                }
            }
        } finally { c.disconnect() }
    }
}

/** אחרי הפעלה מחדש של הטלפון - מתחברים שוב */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            UpdateListener.start(c); UpdateJob.schedule(c)
        }
    }
}

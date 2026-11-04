package com.tal.tzofarrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

/** השירות שרץ כל הזמן: בודק את פיקוד העורף ומעביר לשרת */
class RelayService : Service() {
    @Volatile private var running = false
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    companion object {
        @Volatile var isRunning = false
        fun start(c: Context) {
            if (!Relay.enabled(c) || Relay.key(c).isEmpty()) return
            c.startForegroundService(Intent(c, RelayService::class.java))
        }
        fun stop(c: Context) = c.stopService(Intent(c, RelayService::class.java))
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, note("מתחבר…"))
        if (!running) {
            running = true; isRunning = true
            Relay.startedAt = System.currentTimeMillis()
            // הטלפון מחובר לחשמל - מחזיקים אותו ער ואת ה-Wi-Fi פעיל, שהבדיקות לא ייעצרו
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relay:run").apply { acquire() }
            @Suppress("DEPRECATION")
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "relay:wifi").apply { acquire() }
            Thread { loop() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; isRunning = false
        try { wake?.release() } catch (_: Exception) { }
        try { wifi?.release() } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun loop() {
        var lastPing = 0L
        var lastNote = ""
        while (running) {
            val now = System.currentTimeMillis()
            val key = Relay.key(this)
            for (s in Relay.sources) {
                if (!running || now - s.lastTry < s.everyMs) continue
                s.lastTry = now
                try {
                    val (code, txt) = Relay.fetchOref(s.url)
                    if (code != 200) { s.error = if (code == 403) "חסום (403)" else "שגיאה $code"; continue }
                    s.lastOk = System.currentTimeMillis(); s.error = ""
                    send(key, s.key, txt)
                } catch (e: Exception) { s.error = "אין חיבור" }
            }
            // השרת יודע שהממסר חי גם כשפיקוד העורף לא עונה
            if (now - lastPing > 60_000) { lastPing = now; send(key, "ping", "") }
            val n = noteText()
            if (n != lastNote) { lastNote = n; (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, note(n)) }
            Thread.sleep(500)
        }
    }

    private fun send(key: String, kind: String, body: String) {
        try {
            val (code, resp) = Relay.post(key, kind, body)
            when (code) {
                200 -> {
                    Relay.serverOk = System.currentTimeMillis(); Relay.serverError = ""
                    val sent = Regex("\"sent\":(\\d+)").find(resp)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    Relay.addForwarded(this, sent)
                }
                401 -> Relay.serverError = "מפתח שגוי"
                else -> Relay.serverError = "שגיאה $code"
            }
        } catch (_: Exception) { Relay.serverError = "אין חיבור" }
    }

    private fun noteText(): String {
        val live = Relay.sources[0]
        val orefOk = System.currentTimeMillis() - live.lastOk < 15_000
        val srvOk = System.currentTimeMillis() - Relay.serverOk < 90_000
        return when {
            orefOk && srvOk -> "פעיל · פיקוד העורף מחובר"
            !srvOk -> "אין חיבור לשרת" + if (Relay.serverError.isNotEmpty()) " (${Relay.serverError})" else ""
            else -> "פיקוד העורף לא עונה" + if (live.error.isNotEmpty()) " (${live.error})" else ""
        }
    }

    private fun note(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("relay") == null)
            nm.createNotificationChannel(NotificationChannel("relay", "ממסר", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "relay")
            .setSmallIcon(R.drawable.ic_antenna)
            .setContentTitle("צופר ממסר")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}

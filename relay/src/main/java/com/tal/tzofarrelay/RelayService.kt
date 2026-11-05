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

/**
 * השירות שרץ כל הזמן (המתג דלוק):
 * בהמתנה - פעם בדקה בודק את שני השרתים. שניהם לא עונים 3 בדיקות ברצף - נכנס לפעולה:
 * בודק את המקורות ושולח לטלפונים. אחד השרתים חזר - חוזר להמתנה.
 */
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
        startForeground(1, note("בהמתנה"))
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
        if (Relay.active) { Relay.active = false; Thread { Relay.admin(this, "⚠️ הממסר בבית כובה בזמן שהשרתים לא עונים") }.start() }
        try { wake?.release() } catch (_: Exception) { }
        try { wifi?.release() } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun loop() {
        var lastCheck = 0L
        var downChecks = 0
        var hbAt = 0L
        var lastNote = ""
        while (running) {
            val now = System.currentTimeMillis()
            // פעם בדקה: שני השרתים
            if (now - lastCheck >= 60_000) {
                lastCheck = now
                Relay.checkServers(this)
                val t = System.currentTimeMillis()
                val primary = t - Relay.primaryOk < 30_000
                // השרת המשני מכסה רק כשהוא באמת בפעולה (הראשי לא עונה) או כשהראשי עובד
                val secondary = t - Relay.secondaryOk < 30_000 && (primary || Relay.secondaryActive)
                if (primary || secondary) {
                    downChecks = 0
                    if (Relay.active) {
                        Relay.active = false
                        Relay.admin(this, if (primary) "✅ השרת הראשי חזר – הממסר חזר להמתנה" else "✅ השרת המשני פעיל – הממסר חזר להמתנה")
                    }
                } else if (++downChecks >= 3 && !Relay.active) {
                    Relay.active = true; Relay.activeSince = t; hbAt = 0
                    Relay.sources.forEach { it.lastTry = 0 }
                    Relay.admin(this, "⚠️ שני השרתים לא עונים – הממסר בבית נכנס לפעולה" + if (Relay.drill) " (בדיקה)" else "")
                    if (Relay.drill) Relay.testAlert(this)
                }
            }
            if (Relay.active) {
                for (s in Relay.sources) {
                    if (!running || System.currentTimeMillis() - s.lastTry < s.everyMs) continue
                    s.lastTry = System.currentTimeMillis()
                    try {
                        val (code, txt) = Relay.fetchSrc(s)
                        if (code != 200) { s.error = if (code == 403) "חסום (403)" else "שגיאה $code"; continue }
                        s.lastOk = System.currentTimeMillis(); s.error = ""
                        Relay.handle(this, s, txt)
                    } catch (e: Exception) { s.error = "אין חיבור" }
                }
                // סימן חיים לטלפונים כל 10 דקות - שלא יעברו לבדוק בעצמם
                if (System.currentTimeMillis() - hbAt > 10 * 60_000) { hbAt = System.currentTimeMillis(); Relay.heartbeat(this) }
            }
            val n = if (Relay.active) "פעיל – שולח לטלפונים (השרתים לא עונים)" else "בהמתנה – השרתים עובדים"
            if (n != lastNote) { lastNote = n; (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, note(n)) }
            Thread.sleep(500)
        }
    }

    private fun note(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("relay") == null)
            nm.createNotificationChannel(NotificationChannel("relay", "ממסר", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "relay")
            .setSmallIcon(R.drawable.ic_antenna)
            .setContentTitle("ממסר צופר")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}

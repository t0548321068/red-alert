package com.tal.redalert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AlertService : Service() {

    companion object {
        private const val URL_ALERTS = "https://www.oref.org.il/WarningMessages/alert/alerts.json"
        private const val URL_HISTORY = "https://www.oref.org.il/warningMessages/alert/History/AlertsHistory.json"
        private const val URL_ARCHIVE = "https://alerts-history.oref.org.il/Shared/Ajax/GetAlarmsHistory.aspx?lang=he&mode=1"
        private const val POLL_MS = 2000L
        private const val HISTORY_POLL_MS = 10000L
        private const val HISTORY_WINDOW_MS = 90000L
        private const val CH_SERVICE = "service"
        private const val CH_ALERT = "alerts"
        private const val ID_SERVICE = 1
        private const val ID_ALERT = 2
        const val ACTION_TEST = "test"

        private const val CH_INFO = "info"

        const val LEVEL_ALERT = 0
        const val LEVEL_PRE = 1
        const val LEVEL_END = 2

        /** סיווג לפי נוסח ההודעה של פיקוד העורף */
        fun levelOf(title: String): Int = when {
            title.contains("הסתיים") -> LEVEL_END
            title.contains("בדקות הקרובות") || title.contains("צפויות") ||
                title.contains("מקדימה") -> LEVEL_PRE
            else -> LEVEL_ALERT
        }

        fun start(c: Context, testTitle: String? = null) {
            val i = Intent(c, AlertService::class.java)
            if (testTitle != null) {
                i.action = ACTION_TEST
                i.putExtra("title", testTitle)
            }
            c.startForegroundService(i)
        }

        fun stop(c: Context) = c.stopService(Intent(c, AlertService::class.java))
    }

    @Volatile private var running = false
    private var lastId = ""
    private var tzofar: TzofarSource? = null
    private var telegram: List<TelegramSource> = emptyList()
    private var tzevadom: TzevadomSource? = null

    /** אזור -> (סוג, זמן) - למניעת כפילות בין מקורות */
    private val seen = HashMap<String, Pair<Int, Long>>()
    private val DEDUP_MS = 3 * 60 * 1000L
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val n = Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("צבע אדום פעיל")
            .setContentText("מאזין להתראות פיקוד העורף")
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_SERVICE, n)
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "redalert:poll").apply { acquire() }
        running = true
        Thread(::loop, "oref-poll").start()
        Thread(::historyLoop, "oref-history").start()
        tzofar = TzofarSource(this) { title, areas -> handle(title, areas) }.also { it.start() }
        tzevadom = TzevadomSource(this) { title, areas -> handle(title, areas) }.also { it.start() }
        telegram = listOf("PikudHaOref_all", "tzevaadomm", "CumtaAlertsChannel", "Radar_Alerts").map { ch ->
            TelegramSource(ch) { title, areas -> handle(title, areas) }.also { it.start() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) {
            val title = intent.getStringExtra("title") ?: "ירי רקטות וטילים"
            fire(title, listOf("התראת בדיקה"), forceScreen = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        tzofar?.stop()
        telegram.forEach { it.stop() }
        tzevadom?.stop()
        ringtone?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun loop() {
        while (running) {
            try {
                check()
            } catch (_: Exception) {
                // רשת נפלה / תשובה לא תקינה - מנסים שוב בסבב הבא
            }
            Thread.sleep(POLL_MS)
        }
    }

    private fun fetchOref(url: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        val conn = URL(url + sep + "t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.useCaches = false
        conn.setRequestProperty("Referer", "https://www.oref.org.il/")
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RedAlert")
        val bytes = try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        return decode(bytes).trim()
    }

    /** מקור 1: הקובץ החי של פיקוד העורף */
    private fun check() {
        val text = fetchOref(URL_ALERTS)
        if (text.isEmpty()) return

        val json = JSONObject(text)
        val id = json.optString("id")
        if (id.isEmpty() || id == lastId) return
        lastId = id

        val title = json.optString("title", "התראה")
        val arr = json.optJSONArray("data") ?: return
        handle(title, (0 until arr.length()).map { arr.getString(it) })
    }

    /**
     * מקור 2: היסטוריית פיקוד העורף (title / alertDate)
     * מקור 3: ארכיון פיקוד העורף (category_desc / alertDate עם T)
     * שניהם גיבוי להתראות שנפלו בין בדיקות.
     */
    private fun historyLoop() {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Jerusalem")
        }
        while (running) {
            for (url in listOf(URL_HISTORY, URL_ARCHIVE)) {
                try {
                    val text = fetchOref(url)
                    if (!text.startsWith("[")) continue
                    val arr = org.json.JSONArray(text)
                    val now = System.currentTimeMillis()
                    val byTitle = LinkedHashMap<String, MutableList<String>>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val date = o.optString("alertDate").replace('T', ' ').take(19)
                        val t = fmt.parse(date)?.time ?: continue
                        if (now - t > HISTORY_WINDOW_MS) continue
                        val title = o.optString("title").ifEmpty { o.optString("category_desc", "התראה") }
                        byTitle.getOrPut(title) { mutableListOf() }.add(o.optString("data"))
                    }
                    byTitle.forEach { (title, areas) -> handle(title, areas) }
                } catch (_: Exception) { }
            }
            Thread.sleep(HISTORY_POLL_MS)
        }
    }

    /** נקודת כניסה משותפת לכל המקורות: סינון ערים + מניעת כפילות */
    @Synchronized
    fun handle(title: String, areas: List<String>) {
        val level = levelOf(title)
        val now = System.currentTimeMillis()
        val filter = Prefs.cities(this)
        val fresh = areas.filter { a -> a.isNotBlank() }
            .filter { a -> filter.isEmpty() || filter.any { f -> a.contains(f) } }
            .filter { a ->
                val prev = seen[a]
                prev == null || prev.first != level || now - prev.second > DEDUP_MS
            }
        if (fresh.isEmpty()) return
        fresh.forEach { seen[it] = level to now }
        fire(title, fresh)
    }

    /** השרת מחזיר לפעמים UTF-8 עם BOM ולפעמים UTF-16 */
    private fun decode(b: ByteArray): String = when {
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() ->
            String(b, 2, b.size - 2, Charsets.UTF_16LE)
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
            String(b, 3, b.size - 3, Charsets.UTF_8)
        else -> String(b, Charsets.UTF_8)
    }

    private fun fire(title: String, areas: List<String>, forceScreen: Boolean = false) {
        val body = areas.joinToString(", ")
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        Prefs.addHistory(this, Prefs.Entry(time, title, body, levelOf(title), System.currentTimeMillis()))

        val level = levelOf(title)
        val full = Intent(this, AlertActivity::class.java)
            .putExtra("title", title).putExtra("body", body).putExtra("level", level)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fullPi = PendingIntent.getActivity(
            this, 1, full, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = Notification.Builder(this, if (level == LEVEL_END) CH_INFO else CH_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ALERT, n)

        // מסך מלא גם כשהטלפון פתוח ובשימוש:
        // בבדיקה (האפליקציה בחזית) או עם הרשאת "הצגה מעל אפליקציות אחרות"
        if (forceScreen || android.provider.Settings.canDrawOverlays(this)) {
            try { startActivity(full) } catch (_: Exception) { }
        }

        when (level) {
            LEVEL_ALERT -> playAlarm(RingtoneManager.TYPE_ALARM, 15000)
            LEVEL_PRE -> playAlarm(RingtoneManager.TYPE_NOTIFICATION, 3000)
            else -> main.post { ringtone?.stop() }
        }
    }

    /** צליל בערוץ "שעון מעורר" - נשמע גם במצב שקט */
    private fun playAlarm(type: Int, durationMs: Long) {
        main.post {
            ringtone?.stop()
            val uri = RingtoneManager.getDefaultUri(type)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
            main.postDelayed({ ringtone?.stop() }, durationMs)
        }
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "שירות רקע", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "התראות צבע אדום", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // הצליל מושמע ידנית כאזעקה
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "סיום אירוע", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
    }
}

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
        private const val CH_ALERT = "alerts_v2"   // בלי רטט בערוץ - רטט לפי סוג, ידנית
        private const val ID_SERVICE = 1
        private const val ID_ALERT = 2
        const val ACTION_TEST = "test"
        const val ACTION_SILENCE = "silence"
        const val ACTION_VOICE = "voice"

        /** החלפת קול ההקראה בשירות שכבר רץ */
        fun reloadVoice(c: Context) {
            c.startService(Intent(c, AlertService::class.java).setAction(ACTION_VOICE))
        }

        /** עוצר צליל ורטט של ההתראה הנוכחית (ההתראה עצמה נשארת) */
        fun silence(c: Context) {
            c.startService(Intent(c, AlertService::class.java).setAction(ACTION_SILENCE))
        }

        private const val CH_INFO = "info"
        private const val CH_QUIET = "quiet"
        private const val CH_WATCH = "watchdog_v2"
        private const val ID_WATCH = 4
        private const val UNPROTECTED_AFTER_MS = 2 * 60 * 1000L
        private const val CH_STAY = "stay"
        private const val ID_STAY = 6
        private const val CH_UPDATE = "updates_v3"  // ערוץ חדש כדי שההתראה תקפוץ עם צליל
        private const val ID_UPDATE = 3

        const val LEVEL_ALERT = 0
        const val LEVEL_PRE = 1
        const val LEVEL_END = 2

        /** סיווג לפי נוסח ההודעה של פיקוד העורף */
        fun levelOf(title: String): Int = when {
            title.contains("הסתיים") || title.contains("הוסר") || title.contains("ניתן לצאת") -> LEVEL_END
            title.contains("בדקות הקרובות") || title.contains("צפויות") ||
                title.contains("מקדימה") -> LEVEL_PRE
            else -> LEVEL_ALERT
        }

        /** chime = מנגינה שההאזנה חזרה לפעול (רק אחרי הדלקת הטלפון / עדכון, לא בלחיצה על "מוגן") */
        fun start(c: Context, testTitle: String? = null, chime: Boolean = false) {
            val i = Intent(c, AlertService::class.java).putExtra("chime", chime)
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
    private var updatePush: UpdatePush? = null
    private var speaker: Speaker? = null

    /** "יישוב|סוג" -> זמן האירוע - למניעת כפילות בין מקורות */
    private val seen = HashMap<String, Long>()
    private val seenFeed = HashMap<String, Long>()
    private val DEDUP_MS = Prefs.DUP_MS
    /** התראות מאותו סוג בתוך דקה וחצי = אותו אירוע -> שורה אחת ברשימה */
    private val MERGE_MS = 90 * 1000L

    /** מפתח ליישוב - אותו יישוב בכל המקורות ("תל אביב - מרכז העיר" = "תל אביב" = "תל-אביב") */
    private fun key(area: String, level: Int) = Prefs.areaKey(area) + "|" + level
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val n = Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setColor(0xFFD50000.toInt())   // צבע האפליקציה בהתראה
            .setContentTitle("צבע אדום פעיל")
            .setContentText("מאזין להתראות פיקוד העורף")
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            // עם סוג "מיקום" השירות יכול לקרוא מיקום גם ברקע (ל"קרוב אליי").
            // אם אי אפשר כרגע (למשל הופעל מהרקע אחרי הדלקה) - ממשיכים בלי.
            try {
                startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } catch (_: Exception) {
                startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } else {
            startForeground(ID_SERVICE, n)
        }
        // טיימר שהייה שהיה פעיל לפני הפעלה מחדש של השירות
        val stayLeft = Prefs.stayUntil(this) - System.currentTimeMillis()
        if (stayLeft > 0) main.postDelayed(stayDone, stayLeft) else Prefs.setStayUntil(this, 0)
        speaker = Speaker(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "redalert:poll").apply { acquire() }
        running = true
        Thread(::loop, "oref-poll").start()
        Thread(::historyLoop, "oref-history").start()
        Thread(::updateLoop, "update-check").start()
        Thread(::watchdogLoop, "watchdog").start()
        updatePush = UpdatePush { onUpdatePing() }.also { it.start() }
        Thread(::nearLoop, "near-me").start()
        tzofar = TzofarSource(this) { title, areas -> handle(title, areas, source = "tzofar") }.also { it.start() }
        telegram = listOf("PikudHaOref_all", "tzevaadomm", "CumtaAlertsChannel", "Radar_Alerts").map { ch ->
            TelegramSource(ch) { title, areas ->
                // רק שמות יישובים אמיתיים - בלי שורות כותרת, תאריכים ושמות ערוצים
                val real = areas.filter { AreaData.isKnown(this, it) }
                if (real.isNotEmpty()) handle(title, real, source = "tg:$ch")
            }.also { it.start() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // מנגינה רק כשההאזנה חזרה לבד (הדלקת הטלפון / עדכון) - לא בלחיצה ידנית
        if (intent?.getBooleanExtra("chime", false) == true) playAppChime()
        if (intent?.action == ACTION_VOICE) {
            speaker?.applyVoice()
            return START_STICKY
        }
        if (intent?.action == ACTION_SILENCE) {
            speaker?.stop()
            main.post { ringtone?.stop() }
            Vibes.stop(this)
            return START_STICKY
        }
        if (intent?.action == ACTION_TEST) {
            val title = intent.getStringExtra("title") ?: "ירי רקטות וטילים"
            val mine = Prefs.cities(this) + Prefs.nearbyAreas(this)
            fire(title, listOf("התראת בדיקה"), forceScreen = true, test = true,
                shelter = AreaData.shelterSeconds(this, mine) ?: 15)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        speaker?.shutdown()
        tzofar?.stop()
        telegram.forEach { it.stop() }
        updatePush?.stop()
        ringtone?.stop()
        main.removeCallbacks(stayDone)
        main.removeCallbacks(stayNotif)
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
        SourceHealth.ok("oref")   // תשובה תקינה (גם ריקה = אין התראות)
        if (text.isEmpty()) return

        val json = JSONObject(text)
        val id = json.optString("id")
        if (id.isEmpty() || id == lastId) return
        lastId = id

        val title = json.optString("title", "התראה")
        val arr = json.optJSONArray("data") ?: return
        handle(title, (0 until arr.length()).map { arr.getString(it) }, source = "oref")
    }

    /**
     * שומר: אם אין אינטרנט או שאף מקור התרעות לא מחובר יותר מ-2 דקות -
     * התראה "לא מוגן". כשהחיבור חוזר - ההתראה מתחלפת ב"חזר לפעול".
     */
    private fun watchdogLoop() {
        var badSince = 0L
        var alerted = false
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        Thread.sleep(30_000)
        while (running) {
            val reason = unprotectedReason()
            val now = System.currentTimeMillis()
            if (reason != null) {
                if (badSince == 0L) badSince = now
                if (!alerted && now - badSince >= UNPROTECTED_AFTER_MS) {
                    alerted = true
                    nm.notify(ID_WATCH, Notification.Builder(this, CH_WATCH)
                        .setSmallIcon(R.drawable.ic_stat_siren)
            .setColor(0xFFD50000.toInt())   // צבע האפליקציה בהתראה
                        .setContentTitle("⚠️ צבע אדום לא מוגן")
                        .setContentText("$reason – התראות לא יגיעו כרגע")
                        .setContentIntent(openApp())
                        .setOngoing(true)
                        .build())
                }
            } else {
                if (alerted) {
                    nm.notify(ID_WATCH, Notification.Builder(this, CH_WATCH)
                        .setSmallIcon(R.drawable.ic_stat_siren)
            .setColor(0xFFD50000.toInt())   // צבע האפליקציה בהתראה
                        .setContentTitle("✅ צבע אדום חזר לפעול")
                        .setContentText("${SourceHealth.upCount()}/${SourceHealth.total()} מקורות מחוברים")
                        .setContentIntent(openApp())
                        .setAutoCancel(true)
                        .setTimeoutAfter(60_000)
                        .build())
                }
                badSince = 0L
                alerted = false
            }
            Thread.sleep(15_000)
        }
        nm.cancel(ID_WATCH)
    }

    private fun unprotectedReason(): String? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return when {
            caps == null -> "אין חיבור לרשת"
            !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "אין אינטרנט"
            SourceHealth.upCount() == 0 -> "אין חיבור לשרתי ההתרעות"
            else -> null
        }
    }

    /**
     * גרסה חדשה: התראה מיידית דרך ntfy (GitHub שולח ברגע הפרסום),
     * ובנוסף בדיקה כל 30 דקות כגיבוי. התראה אחת לכל גרסה, לחיצה פותחת את חלון ההתקנה.
     */
    private fun updateLoop() {
        Thread.sleep(60_000)
        while (running) {
            notifyIfNewVersion()
            Thread.sleep(30 * 60 * 1000L)
        }
    }

    /** הודעה מ-ntfy: בודקים מול GitHub (עם כמה ניסיונות - הפרסום לפעמים מתעכב בכמה שניות) */
    private fun onUpdatePing() {
        Thread {
            for (i in 0 until 4) {
                if (notifyIfNewVersion()) return@Thread
                Thread.sleep(15_000)
            }
        }.start()
    }

    @Synchronized
    private fun notifyIfNewVersion(): Boolean {
        val v = Updater.pendingVersion(this) ?: return false
        if (Prefs.notifiedVersion(this) == v) return true
        Prefs.setNotifiedVersion(this, v)
        val n = Notification.Builder(this, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setColor(0xFFD50000.toInt())   // צבע האפליקציה בהתראה
            .setContentTitle("🆕 גרסה חדשה זמינה – $v")
            .setContentText("לחץ כדי להתקין")
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_UPDATE, n)
        return true
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
                    SourceHealth.ok(if (url == URL_HISTORY) "history" else "archive")
                    if (!text.startsWith("[")) continue
                    val arr = org.json.JSONArray(text)
                    val now = System.currentTimeMillis()
                    // לפי סוג + זמן ההתראה המקורי (כדי שהתראה ישנה לא תיחשב חדשה)
                    val byTitle = LinkedHashMap<Pair<String, Long>, MutableList<String>>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val date = o.optString("alertDate").replace('T', ' ').take(19)
                        val t = fmt.parse(date)?.time ?: continue
                        if (now - t > HISTORY_WINDOW_MS) continue
                        val title = o.optString("title").ifEmpty { o.optString("category_desc", "התראה") }
                        byTitle.getOrPut(title to t) { mutableListOf() }.add(o.optString("data"))
                    }
                    val src = if (url == URL_HISTORY) "history" else "archive"
                    byTitle.forEach { (k, areas) -> handle(k.first, areas, k.second, src) }
                } catch (_: Exception) { }
            }
            Thread.sleep(HISTORY_POLL_MS)
        }
    }

    /** נקודת כניסה משותפת לכל המקורות: סינון ערים + מניעת כפילות */
    @Synchronized
    fun handle(title: String, rawAreas: List<String>, eventTime: Long = System.currentTimeMillis(),
               source: String = "") {
        val level = levelOf(title)
        val now = System.currentTimeMillis()
        // בלי כפילויות בתוך ההודעה עצמה
        val areas = rawAreas.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { key(it, level) }

        // פיד ארצי: כל ההתראות, לפני הסינון לפי אזורים (בלי צליל)
        val feedNew = areas.filter { a ->
            val prev = seenFeed[key(a, level)]
            prev == null || eventTime - prev > DEDUP_MS
        }
        if (feedNew.isNotEmpty()) {
            feedNew.forEach { seenFeed[key(it, level)] = eventTime }
            Prefs.addFeed(this, Prefs.Entry("", title, feedNew.joinToString(", "), level, now), MERGE_MS)
        }

        val manual = Prefs.cities(this)
        val near = if (Prefs.nearMe(this)) Prefs.nearbyAreas(this) else emptyList()
        // בלי ערים ובלי מיקום ידוע - כל הארץ (עדיף התראה מיותרת מאשר לפספס)
        val allCountry = manual.isEmpty() && near.isEmpty()
        val fresh = areas
            .filter { a ->
                allCountry || manual.any { f -> a.contains(f) } ||
                    near.any { n -> n == a || n.startsWith("$a -") || a.startsWith("$n -") }
            }
            .filter { a ->
                val prev = seen[key(a, level)]
                prev == null || eventTime - prev > DEDUP_MS
            }
        if (fresh.isEmpty()) return
        fresh.forEach { seen[key(it, level)] = eventTime }

        // המקור הכי מהיר כבר התריע על האירוע הזה (לפני פחות מדקה וחצי) -
        // היישובים הנוספים מצטרפים לאותה שורה, בלי צליל ומסך נוספים
        val last = Prefs.history(this).firstOrNull()
        if (last != null && last.level == level && now - last.ts < MERGE_MS) {
            Prefs.mergeFirstHistory(this, fresh)
            AlertWidget.updateAll(this)
            return
        }
        fire(title, fresh, source = source)
    }

    /** השרת מחזיר לפעמים UTF-8 עם BOM ולפעמים UTF-16 */
    private fun decode(b: ByteArray): String = when {
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() ->
            String(b, 2, b.size - 2, Charsets.UTF_16LE)
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
            String(b, 3, b.size - 3, Charsets.UTF_8)
        else -> String(b, Charsets.UTF_8)
    }

    /**
     * "קרוב אליי": כל 2 דקות לוקח מיקום עדכני (לא רק מה שהטלפון זוכר)
     * ומזהה באילו אזורים המכשיר נמצא עכשיו.
     */
    private fun nearLoop() {
        while (running) {
            try {
                if (Prefs.nearMe(this)) main.post {
                    Weather.freshLocation(this) { loc ->
                        if (loc != null) Thread {
                            try {
                                val list = AreaData.areasAt(this, loc.latitude, loc.longitude).take(8)
                                if (list.isNotEmpty() && list != Prefs.nearbyAreas(this)) Prefs.setNearbyAreas(this, list)
                            } catch (_: Exception) { }
                        }.start()
                    }
                }
            } catch (_: Exception) { }
            Thread.sleep(2 * 60 * 1000L)
        }
    }

    private fun fire(title: String, areas: List<String>, forceScreen: Boolean = false, shelter: Int? = null,
                     test: Boolean = false, source: String = "") {
        val srcName = if (test) "בדיקה" else if (source.isEmpty()) "" else SourceHealth.name(source)
        val body = areas.joinToString(", ")
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        // בדיקה - לא נשמרת בהיסטוריה, במפה ובווידג'ט
        if (!test) Prefs.addHistory(this, Prefs.Entry(time, title, body, levelOf(title), System.currentTimeMillis(), srcName))

        val level = levelOf(title)
        val quiet = level != LEVEL_ALERT && Prefs.isQuietNow(this)
        val shelterSec = shelter ?: if (level == LEVEL_ALERT) AreaData.shelterSeconds(this, areas) else null
        val full = Intent(this, AlertActivity::class.java)
            .putExtra("title", title).putExtra("body", body).putExtra("level", level)
            .putExtra("shelter", shelterSec ?: -1).putExtra("firedAt", System.currentTimeMillis())
            .putExtra("source", srcName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fullPi = PendingIntent.getActivity(
            this, 1, full, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val channel = when {
            quiet -> CH_QUIET
            level == LEVEL_END -> CH_INFO
            else -> CH_ALERT
        }
        val text = if (shelterSec != null && level == LEVEL_ALERT)
            "$body · זמן למרחב מוגן: ${AreaData.shelterText(shelterSec)}" else body
        val nb = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setColor(0xFFD50000.toInt())   // צבע האפליקציה בהתראה
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(fullPi)
            .setAutoCancel(true)
        if (!quiet) nb.setFullScreenIntent(fullPi, true)
        // ספירה לאחור ישר בשורת ההתראות
        if (level == LEVEL_ALERT && shelterSec != null && shelterSec > 0) {
            nb.setWhen(System.currentTimeMillis() + shelterSec * 1000L)
                .setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                .setSubText("זמן להגעה למרחב המוגן")
        }
        val n = nb.build()
        if (!quiet && level != LEVEL_END) overrideDnd()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ALERT, n)

        // מסך מלא גם כשהטלפון פתוח ובשימוש:
        // בבדיקה (האפליקציה בחזית) או עם הרשאת "הצגה מעל אפליקציות אחרות"
        if (!quiet) wakeScreen()
        val screenOff = !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (!quiet && (forceScreen || screenOff || android.provider.Settings.canDrawOverlays(this))) {
            try { startActivity(full) } catch (_: Exception) { }
        }

        // ספירה לאחור בווידג'ט הגדול
        // אחרי שנגמר הזמן להגעה - ההתראה עוברת לספירת השהייה (10 דקות)
        if (level == LEVEL_ALERT && !test) {
            main.removeCallbacks(stayNotif)
            stayNotifPi = fullPi; stayTitle = title; stayBody = body; stayEnd = System.currentTimeMillis() + Prefs.STAY_MS
            main.postDelayed(stayNotif, ((shelterSec ?: 0).coerceAtLeast(0) * 1000L) + 500)
        } else if (level == LEVEL_END) main.removeCallbacks(stayNotif)

        if (test) { /* בלי ספירה וטיימר בווידג'ט */ }
        else if (level == LEVEL_ALERT && shelterSec != null && shelterSec > 0) {
            Prefs.setCountdown(this, System.currentTimeMillis() + shelterSec * 1000L, title)
            main.postDelayed({ AlertWidget.updateAll(this) }, shelterSec * 1000L + 500)
        } else if (level == LEVEL_END) Prefs.setCountdown(this, 0, "")

        // טיימר שהייה במרחב המוגן: 10 דקות מהירי, "הסתיים" מבטל אותו
        if (!test) main.removeCallbacks(stayDone)
        if (test) { }
        else if (level == LEVEL_ALERT) {
            Prefs.setStayUntil(this, System.currentTimeMillis() + Prefs.STAY_MS)
            main.postDelayed(stayDone, Prefs.STAY_MS)
        } else if (level == LEVEL_END) {
            Prefs.setStayUntil(this, 0)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_STAY)
        }

        val playSound = {
            when {
                quiet -> main.post { ringtone?.stop() }
                level == LEVEL_ALERT -> playAlarm(level, RingtoneManager.TYPE_ALARM, 15000)
                level == LEVEL_PRE -> playAlarm(level, RingtoneManager.TYPE_NOTIFICATION, 3000)
                Sounds.effective(this, level) != Sounds.SILENT ->
                    playAlarm(level, RingtoneManager.TYPE_NOTIFICATION, 3000)   // סיום - רק אם נבחר צליל
                else -> main.post { ringtone?.stop() }
            }
            Unit
        }

        // הקראה: קודם מקריאים (סוג, אזורים, זמן להגעה) ואז הצליל.
        // הרטט מתחיל מיד. אם ההקראה נתקעת - הצליל מתחיל בכל מקרה אחרי 8 שניות.
        if (!quiet && Prefs.speakAlerts(this)) {
            val started = java.util.concurrent.atomic.AtomicBoolean(false)
            val once = { if (started.compareAndSet(false, true)) main.post { playSound() } }
            speaker?.speak(Speaker.textFor(title, areas, shelterSec)) { once() } ?: once()
            main.postDelayed({ once() }, 8000)
        } else {
            playSound()
        }

        if (!quiet) vibrate(level)
        AlertWidget.updateAll(this)
    }

    /** ההתראה בשורת ההתראות: ספירת שהייה במרחב המוגן, לחיצה פותחת את מסך הספירה */
    private var stayNotifPi: PendingIntent? = null
    private var stayTitle = ""; private var stayBody = ""; private var stayEnd = 0L
    private val stayNotif = Runnable {
        if (System.currentTimeMillis() >= stayEnd) return@Runnable
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setColor(0xFFB45309.toInt())
            .setContentTitle("⏳ נשארים במרחב המוגן")
            .setContentText("$stayTitle · $stayBody")
            .setSubText("עד שאפשר לצאת")
            .setWhen(stayEnd).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(stayNotifPi)
            .setAutoCancel(true)
            .setTimeoutAfter(stayEnd - System.currentTimeMillis())
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ALERT, n)
    }

    /** עברו 10 דקות מהירי - מודיעים שאפשר לצאת */
    private val stayDone = Runnable {
        Prefs.setStayUntil(this, 0)
        val n = Notification.Builder(this, CH_STAY)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setColor(0xFF2E7D32.toInt())
            .setContentTitle("✅ עברו 10 דקות – אפשר לצאת מהמרחב המוגן")
            .setContentText("אלא אם התקבלה הנחיה אחרת")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_STAY, n)
        AlertWidget.updateAll(this)
    }

    /**
     * "נא לא להפריע" פעיל - מכבים אותו לדקה בזמן ההתראה ומחזירים את המצב הקודם.
     * דורש הרשאת "גישה לנא לא להפריע" (⚙ ← נא לא להפריע).
     */
    private var dndSaved = -1
    private fun overrideDnd() {
        if (!Prefs.dndOverride(this)) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        val cur = nm.currentInterruptionFilter
        if (cur == NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (dndSaved == -1) dndSaved = cur
        try { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) } catch (_: Exception) { return }
        main.removeCallbacks(dndRestore)
        main.postDelayed(dndRestore, 60_000)
    }
    private val dndRestore = Runnable {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // מחזירים רק אם המשתמש לא שינה בינתיים בעצמו
        if (dndSaved != -1 && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
            try { nm.setInterruptionFilter(dndSaved) } catch (_: Exception) { }
        dndSaved = -1
    }

    /** מדליק את המסך כשהוא כבוי - שההתראה תופיע מיד גם על מסך הנעילה */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        try {
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "redalert:screen").acquire(20_000)
        } catch (_: Exception) { }
    }

    /** מנגינת האפליקציה (צליל 56) - נשמעת כשההאזנה חוזרת לבד (אחרי עדכון, אחרי הדלקת הטלפון) */
    private fun playAppChime() {
        try {
            android.media.MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setDataSource(this@AlertService,
                    android.net.Uri.parse("android.resource://$packageName/${R.raw.app_notify}"))
                setOnCompletionListener { it.release() }
                prepare()
                start()
            }
        } catch (_: Exception) { }
    }

    /** רטט לפי הבחירה בהגדרות (לכל סוג התראה בנפרד) */
    private fun vibrate(level: Int) = Vibes.play(this, Prefs.vibe(this, level))

    /** צליל בערוץ "שעון מעורר" - נשמע גם במצב שקט */
    private fun playAlarm(level: Int, type: Int, durationMs: Long) {
        val uri = Sounds.uri(this, Sounds.effective(this, level), level) ?: return
        main.post {
            ringtone?.stop()
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
                setSound(null, null)     // הצליל מושמע ידנית כאזעקה
                enableVibration(false)   // הרטט מופעל ידנית, שונה לכל סוג
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        val appSound = android.net.Uri.parse("android.resource://$packageName/${R.raw.app_notify}")
        val notifAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CH_UPDATE, "עדכוני גרסה", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(appSound, notifAttrs)
            })
        nm.deleteNotificationChannel("updates_v2")
        nm.deleteNotificationChannel("watchdog")
        nm.deleteNotificationChannel("updates")
        nm.deleteNotificationChannel("alerts")
        nm.createNotificationChannel(
            NotificationChannel(CH_QUIET, "שעות שקט", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "האפליקציה לא מוגנת", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(appSound, notifAttrs)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        nm.createNotificationChannel(
            NotificationChannel(CH_STAY, "יציאה מהמרחב המוגן", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(appSound, notifAttrs)
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

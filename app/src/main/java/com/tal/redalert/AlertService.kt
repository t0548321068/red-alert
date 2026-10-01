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
        private const val HISTORY_POLL_MS = 30000L   // גיבוי בלבד
        private const val HISTORY_WINDOW_MS = 90000L
        private const val CH_SERVICE = "service"
        private const val CH_ALERT = "alerts_v3"   // בינתיים: צליל ורטט ברירת מחדל של הטלפון
        /** בינתיים כל הצלילים והרטטים - של הטלפון (לא של האפליקציה) */
        const val PHONE_DEFAULTS = true
        private const val ID_SERVICE = 1
        private const val ID_ALERT = 2
        const val ACTION_TEST = "test"
        const val ACTION_SILENCE = "silence"
        const val ACTION_VOICE = "voice"
        const val ACTION_PUSH = "push"
        const val ACTION_WATCHDOG = "watchdog"
        private const val WATCHDOG_MS = 5 * 60 * 1000L

        /** שומר: מעיר את השירות כל ~5 דקות גם כשהטלפון ישן */
        fun scheduleWatchdog(c: Context) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = PendingIntent.getForegroundService(c, 30,
                Intent(c, AlertService::class.java).setAction(ACTION_WATCHDOG),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            try {
                am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + WATCHDOG_MS, pi)
            } catch (_: Exception) { }
        }

        /** האם היה אירוע ב-10 הדקות האחרונות (אז בודקים את כל המקורות בתדירות גבוהה) */
        @Volatile var lastEventAt = 0L
        fun inEvent() = System.currentTimeMillis() - lastEventAt < 10 * 60 * 1000L

        /** בטא הופעלה/כובתה - התחברות מחדש להתראות העדכון */
        fun reloadPush(c: Context) {
            c.startService(Intent(c, AlertService::class.java).setAction(ACTION_PUSH))
        }

        /** החלפת קול ההקראה בשירות שכבר רץ */
        fun reloadVoice(c: Context) {
            c.startService(Intent(c, AlertService::class.java).setAction(ACTION_VOICE))
        }

        /** עוצר צליל ורטט של ההתראה הנוכחית (ההתראה עצמה נשארת) */
        fun silence(c: Context) {
            c.startService(Intent(c, AlertService::class.java).setAction(ACTION_SILENCE))
        }

        private const val CH_INFO = "info_v2"
        private const val CH_QUIET = "quiet"
        private const val CH_WATCH = "watchdog_v4"
        private const val ID_WATCH = 4
        private const val UNPROTECTED_AFTER_MS = 2 * 60 * 1000L
        private const val CH_STAY = "stay_v3"
        private const val ID_STAY = 6
        private const val ID_LISTEN = 7
        private const val CH_UPDATE = "updates_v5"  // ערוץ חדש - הצליל לפי שם הקובץ (לא לפי מספר שמשתנה בין גרסאות)
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

        /**
         * manual = הופעל בלחיצה (על "מוגן", בדיקה, שינוי הגדרה) - בלי התראת "מאזין".
         * בכל הפעלה אחרת (הדלקת הטלפון, אחרי עדכון, חזרה אחרי שנסגר) - התראה עם המנגינה.
         */
        private const val ID_OFF = 8

        /** התראה קבועה כשההאזנה כבויה: "לא מוגן" + כפתור הפעלה */
        fun showOff(c: Context) {
            val nm = c.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH_WATCH) == null) nm.createNotificationChannel(
                NotificationChannel(CH_WATCH, "האפליקציה לא מוגנת", NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                })
            val open = PendingIntent.getActivity(c, 20, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val enable = PendingIntent.getActivity(c, 21,
                Intent(c, MainActivity::class.java).putExtra("enable", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(ID_OFF, Notification.Builder(c, CH_WATCH)
                .setSmallIcon(R.drawable.ic_stat_siren)
                .setColor(0xFFD50000.toInt())
                .setContentTitle("⚠️ לא מוגן")
                .setContentText("צבע אדום כבוי – התראות לא יגיעו")
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "הפעלה", enable).build())
                .setOngoing(true)
                .build())
        }

        fun clearOff(c: Context) =
            (c.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_OFF)

        fun start(c: Context, testTitle: String? = null, manual: Boolean = false) {
            clearOff(c)
            val i = Intent(c, AlertService::class.java).putExtra("manual", manual || testTitle != null)
            if (testTitle != null) {
                i.action = ACTION_TEST
                i.putExtra("title", testTitle)
            }
            c.startForegroundService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, AlertService::class.java))
            showOff(c)
        }
    }

    @Volatile private var running = false
    /** השירות עלה זה עתה (עוד לא טופלה הפקודה הראשונה) */
    private var fresh = true
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
    /** מחזיק את הטלפון ער לזמן קצוב (לא כל הזמן) */
    private fun awake(ms: Long) { try { wakeLock?.acquire(ms) } catch (_: Exception) { } }
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
        SourceHealth.reset()   // מקורות מתחברים - "מתחבר" ולא "מנותק"
        // טיימר שהייה שהיה פעיל לפני הפעלה מחדש של השירות
        val stayLeft = Prefs.stayUntil(this) - System.currentTimeMillis()
        if (stayLeft > 0) main.postDelayed(stayDone, stayLeft) else Prefs.setStayUntil(this, 0)
        speaker = Speaker(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // בלי להחזיק את המעבד ער כל הזמן: הטלפון ישן, והחיבור הקבוע לצופר מעיר אותו כשיש התראה.
        // שומר: מעיר את הטלפון כל כמה דקות כדי לוודא שהחיבורים חיים (ולבדוק את פיקוד העורף)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "redalert:poll").apply { setReferenceCounted(false) }
        scheduleWatchdog(this)
        running = true
        Thread(::loop, "oref-poll").start()
        Thread(::historyLoop, "oref-history").start()
        Thread(::updateLoop, "update-check").start()
        Thread(::watchdogLoop, "watchdog").start()
        // בלי חיבור קבוע ל-ntfy (חיסכון בסוללה) - בדיקת עדכונים כל 6 שעות ובפתיחת האפליקציה
        Thread(::nearLoop, "near-me").start()
        tzofar = TzofarSource(this) { title, areas -> handle(title, areas, source = "tzofar") }.also { it.start() }
        telegram = listOf("PikudHaOref_all", "tzevaadomm", "CumtaAlertsChannel", "Radar_Alerts").map { ch ->
            TelegramSource(ch, { telegramInterval() }) { title, areas ->
                // רק שמות יישובים אמיתיים - בלי שורות כותרת, תאריכים ושמות ערוצים
                val real = areas.filter { AreaData.isKnown(this, it) }
                if (real.isNotEmpty()) handle(title, real, source = "tg:$ch")
            }.also { it.start() }
        }
    }

    /** טלגרם: 5 שניות באירוע, 30 שניות כשהמסך דלוק, 2 דקות כשהוא כבוי */
    private fun telegramInterval(): Long = when {
        inEvent() -> 5_000L
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive -> 30_000L
        else -> 120_000L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // מנגינה רק כשההאזנה חזרה לבד (הדלקת הטלפון / עדכון) - לא בלחיצה ידנית
        // השירות רק עכשיו עלה, ולא בלחיצה ידנית - התראה "מאזין" עם המנגינה
        if (fresh) {
            fresh = false
            if (intent?.getBooleanExtra("manual", false) != true) announceListening()
        }
        if (intent?.action == ACTION_WATCHDOG) {
            awake(20_000)   // כמה שניות ער: החיבורים מתחדשים ופיקוד העורף נבדק
            tzofar?.ensureConnected()
            scheduleWatchdog(this)
            return START_STICKY
        }
        if (intent?.action == ACTION_PUSH) {
            updatePush?.reconnect()
            return START_STICKY
        }
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
                shelter = AreaData.shelterSeconds(this, mine) ?: 15, mapAreas = mine)
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
            Thread.sleep(6 * 60 * 60 * 1000L)   // כל 6 שעות (וגם בכל פתיחה של האפליקציה)
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
    fun handle(rawTitle: String, rawAreas: List<String>, eventTime: Long = System.currentTimeMillis(),
               source: String = "") {
        awake(60_000)   // התראה הגיעה - הטלפון נשאר ער לזמן הטיפול בה
        lastEventAt = System.currentTimeMillis()
        val level = levelOf(rawTitle)
        // כל סוגי הסיום ("החשש הוסר" וכו') מוצגים כ"האירוע הסתיים"
        val title = if (level == LEVEL_END) "האירוע הסתיים" else rawTitle
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
        // רק לפי המיקום + האזורים שהוספתי. אף פעם לא "כל הארץ"
        val fresh = areas
            .filter { a ->
                manual.any { f -> a.contains(f) } ||
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
        if (last != null && last.level == level &&
            (now - last.ts < MERGE_MS || (level == LEVEL_END && now - last.ts < DEDUP_MS))) {
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
    /**
     * "קרוב אליי": כל 5 דקות. קודם מיקום מהרשת (כמעט בלי סוללה);
     * GPS נדלק רק אם זזנו (מעל 300 מ׳) או שהמיקום מהרשת לא מספיק מדויק.
     */
    private var lastGps: android.location.Location? = null
    private fun nearLoop() {
        while (running) {
            try {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED) main.post {
                    awake(40_000)
                    Weather.networkLocation(this) { net ->
                        val prev = lastGps
                        val needGps = net == null || prev == null || net.accuracy > 150f || net.distanceTo(prev) > 300f
                        if (needGps) Weather.freshLocation(this) { loc -> if (loc != null) { lastGps = loc; useLocation(loc) } }
                        else useLocation(prev!!)   // לא זזנו - המיקום המדויק הקודם עדיין נכון
                    }
                }
            } catch (_: Exception) { }
            Thread.sleep(5 * 60 * 1000L)
        }
    }

    private fun useLocation(loc: android.location.Location) = Thread {
        try {
            Prefs.addVisits(this, AreaData.areasAt(this, loc.latitude, loc.longitude, 0.0), System.currentTimeMillis())
            if (Prefs.nearMe(this)) {
                val list = AreaData.areasAt(this, loc.latitude, loc.longitude, 1.0).take(4)
                if (list.isNotEmpty() && list != Prefs.nearbyAreas(this)) Prefs.setNearbyAreas(this, list)
            }
        } catch (_: Exception) { }
    }.start()

    private fun fire(title: String, areas: List<String>, forceScreen: Boolean = false, shelter: Int? = null,
                     test: Boolean = false, source: String = "", mapAreas: List<String>? = null) {
        val srcName = if (test) "בדיקה" else if (source.isEmpty()) "" else SourceHealth.name(source)
        val body = areas.joinToString(", ")
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        // בדיקה - לא נשמרת בהיסטוריה, במפה ובווידג'ט
        if (!test) Prefs.addHistory(this, Prefs.Entry(time, title, body, levelOf(title), System.currentTimeMillis(), srcName))

        val level = levelOf(title)
        // מצב שבת: כל התראה במסך מלא לדקה אחת, עם הקראה (גם אם כבויה), בלי שעות שקט
        val shabbat = Shabbat.active(this)
        val quiet = !shabbat && level != LEVEL_ALERT && Prefs.isQuietNow(this)
        val shelterSec = shelter ?: if (level == LEVEL_ALERT) AreaData.shelterSeconds(this, areas) else null
        val full = Intent(this, AlertActivity::class.java)
            .putExtra("title", title).putExtra("body", body).putExtra("level", level)
            .putExtra("shelter", shelterSec ?: -1).putExtra("firedAt", System.currentTimeMillis())
            .putExtra("source", srcName)
            .putExtra("autoCloseMs", if (shabbat) 60_000L else 0L)
            .putExtra("mapAreas", (mapAreas ?: areas).joinToString(", "))   // בבדיקה: המפה מראה את האזורים שלי
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
        // סיום אירוע כשהחלון הקטן פתוח - מוצג בו (ירוק), בלי מסך מלא
        val toMini = level == LEVEL_END && MiniOverlay.isShowing()
        if (!quiet && !toMini) nb.setFullScreenIntent(fullPi, true)
        // ספירה לאחור ישר בשורת ההתראות
        // ספירה לאחור גדולה בתוך ההתראה הקופצת
        if (level == LEVEL_ALERT && shelterSec != null && shelterSec > 0) {
            countdownView(nb, title, body, "זמן להגעה למרחב המוגן", shelterSec * 1000L, 0xFFD50000.toInt())
        }
        val n = nb.build()
        if (!quiet && level != LEVEL_END) overrideDnd()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ALERT, n)

        // מסך מלא גם כשהטלפון פתוח ובשימוש:
        // בבדיקה (האפליקציה בחזית) או עם הרשאת "הצגה מעל אפליקציות אחרות"
        if (!quiet) wakeScreen()
        val screenOff = !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (toMini) main.post { MiniOverlay.showEnd(body) }
        else if (!quiet && (forceScreen || shabbat || screenOff || android.provider.Settings.canDrawOverlays(this))) {
            try { startActivity(full) } catch (_: Exception) { }
        }

        // ספירה לאחור בווידג'ט הגדול
        // אחרי שנגמר הזמן להגעה - ההתראה עוברת לספירת השהייה (10 דקות)
        if (level == LEVEL_ALERT && !test) {
            main.removeCallbacks(stayNotif)
            stayNotifPi = fullPi; stayTitle = title; stayBody = body; stayEnd = System.currentTimeMillis() + (shelterSec ?: 0).coerceAtLeast(0) * 1000L + Prefs.STAY_MS
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
            // 10 הדקות מתחילות אחרי שנגמר הזמן להגעה למרחב המוגן
            val enterMs = (shelterSec ?: 0).coerceAtLeast(0) * 1000L
            Prefs.setStayUntil(this, System.currentTimeMillis() + enterMs + Prefs.STAY_MS)
            main.postDelayed(stayDone, enterMs + Prefs.STAY_MS)
        } else if (level == LEVEL_END) {
            Prefs.setStayUntil(this, 0)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_STAY)
        }

        val playSound = {
            if (PHONE_DEFAULTS) Unit   // הטלפון משמיע את צליל ההתראה בעצמו
            else when {
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
        if (!quiet && (Prefs.speakAlerts(this) || shabbat)) {
            val started = java.util.concurrent.atomic.AtomicBoolean(false)
            val once = { if (started.compareAndSet(false, true)) main.post { playSound() } }
            speaker?.speakAlert(title, areas, shelterSec) { once() } ?: once()
            main.postDelayed({ once() }, 8000)
        } else {
            playSound()
        }

        if (!quiet && !PHONE_DEFAULTS) vibrate(level)
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
            .setOnlyAlertOnce(true)
            .setContentIntent(stayNotifPi)
            .setAutoCancel(true)
            .setTimeoutAfter(stayEnd - System.currentTimeMillis())
        countdownView(n, "⏳ נשארים במרחב המוגן", "$stayTitle · $stayBody", "זמן מומלץ במרחב המוגן",
            stayEnd - System.currentTimeMillis(), 0xFFB45309.toInt())
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ALERT, n.build())
    }

    /** התראה עם ספירה לאחור גדולה (גם בחלון הקופץ וגם בשורת ההתראות) */
    private fun countdownView(nb: Notification.Builder, title: String, body: String, label: String, leftMs: Long, color: Int) {
        fun rv() = android.widget.RemoteViews(packageName, R.layout.notif_countdown).apply {
            setTextViewText(R.id.nc_title, title)
            setTextViewText(R.id.nc_body, body)
            setTextViewText(R.id.nc_label, label)
            setChronometer(R.id.nc_timer, android.os.SystemClock.elapsedRealtime() + leftMs, null, true)
            setChronometerCountDown(R.id.nc_timer, true)
            setTextColor(R.id.nc_timer, color)
        }
        nb.setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(rv())
            .setCustomHeadsUpContentView(rv())
            .setCustomBigContentView(rv())
    }

    /** עברו 10 הדקות - רק מעדכנים תצוגה. "ניתן לצאת" רק בהודעת סיום אירוע */
    private val stayDone = Runnable {
        Prefs.setStayUntil(this, 0)
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

    /** התראה "צבע אדום פעיל – מאזין" עם מנגינת האפליקציה (נעלמת לבד אחרי 15 שניות) */
    private fun announceListening() {
        val n = Notification.Builder(this, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setColor(0xFF2E7D32.toInt())
            .setContentTitle("צבע אדום פעיל")
            .setContentText("מאזין להתראות פיקוד העורף")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_LISTEN, n)
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
                // צליל ורטט - ברירת המחדל של הטלפון
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        // לפי שם ולא לפי מספר המשאב - המספר משתנה בין גרסאות, והערוץ שומר את הקישור לתמיד
        val appSound = android.net.Uri.parse("android.resource://$packageName/raw/app_notify")
        val notifAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CH_UPDATE, "עדכוני גרסה", NotificationManager.IMPORTANCE_HIGH).apply {
                // בינתיים: צליל ההתראות הרגיל של הטלפון (לא המנגינה של האפליקציה)
            })
        nm.deleteNotificationChannel("updates_v2")
        nm.deleteNotificationChannel("updates_v3")
        nm.deleteNotificationChannel("watchdog_v2")
        nm.deleteNotificationChannel("stay")
        nm.deleteNotificationChannel("alerts_v2")
        nm.deleteNotificationChannel("info")
        nm.deleteNotificationChannel("updates_v4")
        nm.deleteNotificationChannel("watchdog_v3")
        nm.deleteNotificationChannel("stay_v2")
        nm.deleteNotificationChannel("watchdog")
        nm.deleteNotificationChannel("updates")
        nm.deleteNotificationChannel("alerts")
        nm.createNotificationChannel(
            NotificationChannel(CH_QUIET, "שעות שקט", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "האפליקציה לא מוגנת", NotificationManager.IMPORTANCE_HIGH).apply {
                // בינתיים: צליל ההתראות הרגיל של הטלפון (לא המנגינה של האפליקציה)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        nm.createNotificationChannel(
            NotificationChannel(CH_STAY, "יציאה מהמרחב המוגן", NotificationManager.IMPORTANCE_HIGH).apply {
                // בינתיים: צליל ההתראות הרגיל של הטלפון (לא המנגינה של האפליקציה)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "סיום אירוע", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
    }
}

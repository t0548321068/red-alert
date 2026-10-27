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
        const val ACTION_TEST_SEQ = "test_seq"   // בדיקת אירוע מלא: מקדימה, ירי, סיום
        const val ACTION_SILENCE = "silence"
        const val ACTION_VOICE = "voice"
        const val ACTION_PUSH = "push"
        const val ACTION_PUSH_ALERT = "push_alert"   // התרעה מהשרת (Firebase) כשהשירות לא רץ
        const val ACTION_WATCHDOG = "watchdog"
        private const val WATCHDOG_MS = 5 * 60 * 1000L
        /** השרת מחובר (סימן החיים מוכיח שהכול עובד) - השומר מעיר את הטלפון רק כל 15 דקות */
        private const val WATCHDOG_SERVER_MS = 15 * 60 * 1000L

        /** השרת הודיע שיש גרסה חדשה - בודקים מיד (במקום שהטלפון יבדוק בעצמו כל חצי שעה) */
        fun onServerUpdate() { instance?.onUpdatePing() }

        /** שומר: מעיר את השירות כל ~5 דקות גם כשהטלפון ישן */
        fun scheduleWatchdog(c: Context) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = PendingIntent.getBroadcast(c, 30, Intent(c, WatchdogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            try {
                am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + (if (serverOk(c)) WATCHDOG_SERVER_MS else WATCHDOG_MS), pi)
            } catch (_: Exception) { }
        }

        /** האם היה אירוע ב-10 הדקות האחרונות (אז בודקים את כל המקורות בתדירות גבוהה) */
        @Volatile var lastEventAt = 0L
        /** השירות הפעיל (לשומר) */
        @Volatile var instance: AlertService? = null
        fun inEvent() = System.currentTimeMillis() - lastEventAt < 10 * 60 * 1000L

        /**
         * השרת שלנו בודק את כל המקורות ושולח כל התרעה דרך Firebase, ופעם ב-10 דקות סימן חיים.
         * כל עוד סימן החיים מגיע - הטלפון לא בודק בעצמו (חוסך סוללה). 35 דקות בלי סימן חיים - חוזרים לבדוק לבד
         */
        private const val SERVER_STALE_MS = 35 * 60 * 1000L
        /** מתי הגיע סימן החיים האחרון מהשרת (0 = עוד לא) */
        fun serverHbAt(c: Context): Long = c.getSharedPreferences("server", Context.MODE_PRIVATE).getLong("hb", 0L)
        fun serverOk(c: Context): Boolean = System.currentTimeMillis() - serverHbAt(c) < SERVER_STALE_MS
        fun onServerHeartbeat(c: Context, up: List<String>) {
            val now = System.currentTimeMillis()
            c.getSharedPreferences("server", Context.MODE_PRIVATE).edit().putLong("hb", now).putString("up", up.joinToString(",")).apply()
            SourceHealth.viaServer(up, now)
            instance?.syncLocalSources()
        }

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
        private const val CH_NO_SOUND = "alerts_nosound"   // רטט בלי צליל
        private const val CH_NO_VIBE = "alerts_novibe"     // צליל בלי רטט
        private const val CH_SILENT = "alerts_silent"      // בלי צליל ובלי רטט
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
            // התרעה מקדימה: "בדקות הקרובות...", "איום מלבנון" וכד', "התקרבו למרחב המוגן"
            title.contains("בדקות הקרובות") || title.contains("צפויות") ||
                title.contains("מקדימה") || title.contains("איום") || title.contains("התקרבו") -> LEVEL_PRE
            else -> LEVEL_ALERT
        }

        /** הכותרת שמוצגת: כל סוגי הסיום = "האירוע הסתיים"; "איום מלבנון" = "התרעה מקדימה - איום מלבנון" */
        fun displayTitle(raw: String): String {
            val t = raw.trim()
            return when {
                levelOf(t) == LEVEL_END -> "האירוע הסתיים"
                t.contains("איום") && !t.startsWith("התרעה מקדימה") -> "התרעה מקדימה - $t"
                else -> t
            }
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
                .setContentText("צופר חירום כבוי – התראות לא יגיעו")
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

        /** בדיקת אירוע מלא: התרעה מקדימה, אחרי 15 שניות ירי, ובסוף "האירוע הסתיים" */
        fun startTestSequence(c: Context) {
            clearOff(c)
            c.startForegroundService(Intent(c, AlertService::class.java).putExtra("manual", true).setAction(ACTION_TEST_SEQ))
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
    /** השומר: כמה שניות ער - החיבורים מתחדשים ופיקוד העורף נבדק - ותזמון הבא */
    fun onWatchdog() {
        syncLocalSources()   // השרת הפסיק לשלוח סימן חיים - צופר חוזר לפעול בטלפון
        try { Shabbat.refresh(this) } catch (_: Exception) { }   // זמני שבת וחג מהשרת (לכל היותר פעם ב-12 שעות)
        if (!serverOk(this)) awake(6_000)   // מספיק לוודא שהחיבור לצופר חי ולבדיקה אחת של פיקוד העורף
        tzofar?.ensureConnected()
        // הטלפון כבר ער בשביל השומר - מנצלים את זה לבדיקת עדכון (לכל היותר פעם בחצי שעה, בקשה קטנה אחת)
        if (!serverOk(this)) checkUpdateIfDue(30 * 60 * 1000L)   // כשהשרת מחובר - הוא מודיע על גרסה חדשה
        scheduleWatchdog(this)
    }

    /** בדיקת גרסה חדשה רק אם עבר מספיק זמן מהבדיקה הקודמת (שעון אמיתי, כולל זמן שינה) */
    @Volatile private var lastUpdCheck = 0L
    private fun checkUpdateIfDue(maxAge: Long) {
        val now = System.currentTimeMillis()
        if (now - lastUpdCheck < maxAge) return
        lastUpdCheck = now
        Thread({ try { notifyIfNewVersion() } catch (_: Exception) { } }, "update-due").start()
    }

    /**
     * פתיחת נעילת המסך: אם לא נבדק ב-10 הדקות האחרונות - בודקים.
     * בנוסף, כל עוד המסך דלוק - מחוברים ל-ntfy, וגרסה חדשה מגיעה מיד כשהיא מתפרסמת.
     * כשהמסך כבוי - מתנתקים (בלי בזבוז סוללה), ונשארות הבדיקות בהתעוררות השומר
     */
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> if (!serverOk(c)) checkUpdateIfDue(10 * 60 * 1000L)
                Intent.ACTION_SCREEN_ON -> if (!serverOk(c)) livePush(true)
                Intent.ACTION_SCREEN_OFF -> livePush(false)
            }
        }
    }

    private fun livePush(on: Boolean) {
        if (on) {
            if (updatePush != null) return
            updatePush = UpdatePush(beta = { Prefs.betaUpdates(this) }, onPing = ::onUpdatePing).also { it.start() }
        } else {
            updatePush?.stop(); updatePush = null
        }
    }

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
            .setContentTitle("צופר חירום פעיל")
            .setContentText("מאזין להתראות פיקוד העורף")
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            // עם סוג "מיקום" השירות יכול לקרוא מיקום גם ברקע (ל"התרעות לפי מיקום").
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
        // מצב המקורות מסימן החיים האחרון של השרת - כדי שאחרי הפעלה מחדש לא יופיעו כמנותקים עד הסימן הבא
        getSharedPreferences("server", MODE_PRIVATE).let { sp ->
            // גרסה קודמת שמרה רק את זמן סימן החיים, בלי רשימת המקורות - השרת שולח סימן חיים רק כשהוא עובד
            val up = (sp.getString("up", null) ?: SourceHealth.NAMES.keys.joinToString(",")).split(',').filter { it.isNotEmpty() }
            SourceHealth.viaServer(up, sp.getLong("hb", 0L))
        }
        // טיימר שהייה שהיה פעיל לפני הפעלה מחדש של השירות
        val stayLeft = Prefs.stayUntil(this) - System.currentTimeMillis()
        if (stayLeft > 0) main.postDelayed(stayDone, stayLeft) else Prefs.setStayUntil(this, 0)
        speaker = Speaker(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // בלי להחזיק את המעבד ער כל הזמן: הטלפון ישן, והחיבור הקבוע לצופר מעיר אותו כשיש התראה.
        // שומר: מעיר את הטלפון כל כמה דקות כדי לוודא שהחיבורים חיים (ולבדוק את פיקוד העורף)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "redalert:poll").apply { setReferenceCounted(false) }
        instance = this
        scheduleWatchdog(this)
        try { ShabbatLocReceiver.schedule(this) } catch (_: Exception) { }   // מצב שבת אוטומטי: מיקום עדכני לפני הכניסה
        running = true
        Thread(::loop, "oref-poll").start()
        Thread(::historyLoop, "oref-history").start()
        Thread(::updateLoop, "update-check").start()
        PushService.subscribe(this)   // התראות מיידיות מהשרת
        try {
            registerReceiver(unlockReceiver, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        } catch (_: Exception) { }
        if ((getSystemService(POWER_SERVICE) as PowerManager).isInteractive && !serverOk(this)) livePush(true)
        Thread(::watchdogLoop, "watchdog").start()
        // בלי חיבור קבוע ל-ntfy (חיסכון בסוללה) - בדיקת עדכונים כל 6 שעות ובפתיחת האפליקציה
        Thread(::nearLoop, "near-me").start()
        syncLocalSources()
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
        serverOk(this) -> Long.MAX_VALUE / 4   // השרת בודק את טלגרם
        inEvent() -> 5_000L
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive -> 30_000L
        else -> 300_000L   // מסך כבוי - כל 5 דקות (גיבוי בלבד)
    }

    /** צופר בטלפון רק כשהשרת לא זמין (חיבור קבוע = התעוררויות של הרדיו) */
    @Synchronized
    fun syncLocalSources() {
        if (!running) return
        if (serverOk(this)) { tzofar?.stop(); tzofar = null }
        else if (tzofar == null)
            tzofar = TzofarSource(this) { title, areas -> handle(title, areas, source = "tzofar") }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // מנגינה רק כשההאזנה חזרה לבד (הדלקת הטלפון / עדכון) - לא בלחיצה ידנית
        // השירות רק עכשיו עלה, ולא בלחיצה ידנית - התראה "מאזין" עם המנגינה
        if (fresh) {
            fresh = false
            // בלי מנגינה ובלי התראה כשההאזנה חוזרת לבד (אחרי עדכון / הדלקת הטלפון)
        }
        if (intent?.action == ACTION_WATCHDOG) { onWatchdog(); return START_STICKY }
        if (intent?.action == ACTION_PUSH_ALERT) {
            val t = intent.getStringExtra("title"); val a = intent.getStringArrayExtra("areas")
            val at = intent.getLongExtra("at", System.currentTimeMillis()); val src = intent.getStringExtra("src") ?: "push"
            if (t != null && a != null) Thread { try { handle(t, a.toList(), at, src) } catch (_: Exception) { } }.start()
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
        if (intent?.action == ACTION_TEST_SEQ) {
            main.removeCallbacks(seqAlert); main.removeCallbacks(seqEnd)
            testFire("בדקות הקרובות צפויות להתקבל התרעות באזורך")
            main.postDelayed(seqAlert, 15_000)
            return START_STICKY
        }
        if (intent?.action == ACTION_TEST) {
            testFire(intent.getStringExtra("title") ?: "ירי רקטות וטילים")
        }
        return START_STICKY
    }

    /** שלבי בדיקת האירוע המלא */
    private val seqEnd = Runnable { testFire("האירוע הסתיים") }
    private val seqAlert = Runnable {
        val shelter = testFire("ירי רקטות וטילים")
        main.postDelayed(seqEnd, shelter * 1000L + 20_000)
    }

    /** התראת בדיקה (מחזירה את זמן ההגעה למרחב המוגן בשניות) */
    private fun testFire(title: String): Int {
        val mine = Prefs.cities(this) + Prefs.nearbyAreas(this)
        // זמן ההתגוננות בבדיקה - לפי המיקום שלי (ולא לפי כל האזורים הנוספים: עם "כל הארץ" הקצר ביותר הוא "מיידי",
        // ואז הבדיקה דילגה ישר ל"נשארים במרחב המוגן" ולא הראתה את אייקון סוג ההתרעה). לפחות 15 שניות
        val shelter = (AreaData.shelterSeconds(this, Prefs.nearbyAreas(this))
            ?: AreaData.shelterSeconds(this, mine) ?: 15).coerceAtLeast(15)
        fire(title, listOf("התראת בדיקה"), forceScreen = true, test = true,
            shelter = shelter, mapAreas = mine)
        return shelter
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        running = false
        speaker?.shutdown()
        tzofar?.stop()
        telegram.forEach { it.stop() }
        updatePush?.stop()
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) { }
        ringtone?.stop()
        main.removeCallbacks(stayDone)
        main.removeCallbacks(stayNotif)
        main.removeCallbacks(seqAlert); main.removeCallbacks(seqEnd)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun loop() {
        while (running) {
            try {
                if (!serverOk(this)) check()   // השרת בודק כל שנייה - הטלפון רק כגיבוי
            } catch (_: Exception) {
                // רשת נפלה / תשובה לא תקינה - מנסים שוב בסבב הבא
            }
            // מסך כבוי ואין אירוע - כל 10 שניות (צופר ממילא מתריע מיד); אחרת כל 2 שניות.
            // ממתינים בצעדים של 2 שניות - כשהמסך נדלק או מתחיל אירוע, חוזרים מיד לקצב המהיר
            for (step in 0 until 5) {
                Thread.sleep(POLL_MS)
                if (inEvent() || (getSystemService(POWER_SERVICE) as PowerManager).isInteractive) break
            }
        }
    }

    /**
     * חיבור קבוע לפיקוד העורף: אותו חיבור מאובטח משמש את כל הבדיקות (כל 2 שניות),
     * במקום לפתוח ולסגור חיבור חדש בכל בדיקה - חוסך הרבה סוללה, באותה מהירות
     */
    private val orefHttp = okhttp3.OkHttpClient.Builder()
        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(2, 5, java.util.concurrent.TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    private fun fetchOref(url: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        val req = okhttp3.Request.Builder().url(url + sep + "t=" + System.currentTimeMillis())
            .header("Referer", "https://www.oref.org.il/")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) RedAlert")
            .header("Cache-Control", "no-cache")
            .build()
        val bytes = orefHttp.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP " + r.code)
            r.body?.bytes() ?: ByteArray(0)
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
                        .setContentTitle("⚠️ צופר חירום – לא מוגן")
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
                        .setContentTitle("✅ צופר חירום חזר לפעול")
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
            if (!serverOk(this)) checkUpdateIfDue(14 * 60 * 1000L)
            // כל 15 דקות של זמן ער - לא מעיר את הטלפון (כשהוא ישן, הספירה עוצרת),
            // והבדיקה דרך האתר של GitHub, בלי מגבלת ה-API
            Thread.sleep(15 * 60 * 1000L)
        }
    }

    /** הודעה מ-ntfy: בודקים מול GitHub (עם כמה ניסיונות - הפרסום לפעמים מתעכב בכמה שניות) */
    fun onUpdatePing() {
        Thread {
            // GitHub לפעמים מציג את הגרסה החדשה רק אחרי כמה דקות - מנסים שוב במרווחים גדלים (עד ~8 דקות)
            for (wait in longArrayOf(0, 15_000, 30_000, 60_000, 120_000, 240_000)) {
                if (wait > 0) Thread.sleep(wait)
                if (notifyIfNewVersion()) return@Thread
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
                if (serverOk(this)) break   // השרת בודק את ההיסטוריה והארכיון
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
            // גיבוי: מסך דלוק או אירוע - כל 30 שניות; מסך כבוי - כל 2 דקות.
            // ממתינים בצעדים של 5 שניות - כשהמסך נדלק או מתחיל אירוע, הבדיקה הבאה מגיעה מיד
            val from = System.currentTimeMillis()
            while (running) {
                Thread.sleep(5_000)
                val every = if (inEvent() || (getSystemService(POWER_SERVICE) as PowerManager).isInteractive)
                    HISTORY_POLL_MS else 120_000L
                if (System.currentTimeMillis() - from >= every) break
            }
        }
    }

    /** נקודת כניסה משותפת לכל המקורות: סינון ערים + מניעת כפילות */
    @Synchronized
    fun handle(rawTitle: String, rawAreas: List<String>, eventTime: Long = System.currentTimeMillis(),
               source: String = "") {
        // תזמון הגנה: מחוץ לימים ולשעות שנבחרו - לא מקבלים התרעות
        if (Prefs.scheduleOffNow(this)) return
        awake(60_000)   // התראה הגיעה - הטלפון נשאר ער לזמן הטיפול בה
        lastEventAt = System.currentTimeMillis()
        val level = levelOf(rawTitle)
        // כל סוגי הסיום ("החשש הוסר" וכו') מוצגים כ"האירוע הסתיים"; "איום מלבנון" - "התרעה מקדימה - איום מלבנון"
        val title = displayTitle(rawTitle)
        val now = System.currentTimeMillis()
        // בלי כפילויות בתוך ההודעה עצמה
        // "ברחבי הארץ" מאוחד ל"כל הארץ" - בכל מקום שמוצג (פיד, התרעות אחרונות, מסך ההתרעה)
        val areas = rawAreas.map { it.trim() }.filter { it.isNotBlank() }.map { AreaData.unify(it) }
            .distinctBy { key(it, level) }

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
                manual.any { f -> AreaData.matches(this, a, f) } ||   // יישוב או אזור שלם
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
     * "התרעות לפי מיקום": כל 2 דקות לוקח מיקום עדכני (לא רק מה שהטלפון זוכר)
     * ומזהה באילו אזורים המכשיר נמצא עכשיו.
     */
    /**
     * "התרעות לפי מיקום": כל 5 דקות. קודם מיקום מהרשת (כמעט בלי סוללה);
     * GPS נדלק רק אם זזנו (מעל 300 מ׳) או שהמיקום מהרשת לא מספיק מדויק.
     */
    private var lastGps: android.location.Location? = null
    private fun nearLoop() {
        while (running) {
            try {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED) main.post {
                    awake(15_000)   // מספיק למיקום מהרשת; GPS (כשצריך) מחזיק את הטלפון ער בעצמו
                    Weather.networkLocation(this) { net ->
                        val prev = lastGps
                        // מיקום מהרשת שנופל באזור אחר מהמיקום המדויק הקודם (למשל שכונה סמוכה) - בודקים עם GPS
                        // לפני שמחליפים אזור. כך GPS נדלק רק כשבאמת יש ספק, לא כל 5 דקות
                        val nearBorder = net != null && prev != null && net.accuracy > 30f && try {
                            AreaData.areasAt(this, net.latitude, net.longitude, 0.0).firstOrNull() !=
                                AreaData.areasAt(this, prev.latitude, prev.longitude, 0.0).firstOrNull()
                        } catch (_: Exception) { false }
                        val needGps = net == null || prev == null || net.accuracy > 150f || net.distanceTo(prev) > 300f || nearBorder
                        if (needGps) Weather.freshLocation(this) { loc ->
                            if (loc != null) { val use = Weather.chooseLocation(loc); lastGps = use; useLocation(use) }
                        }
                        else useLocation(prev!!)   // לא זזנו - המיקום המדויק הקודם עדיין נכון
                    }
                }
            } catch (_: Exception) { }
            // מסך דלוק - כל 5 דקות; כבוי - כל 15 (כשהטלפון בכיס הוא בדרך כלל לא זז הרבה)
            Thread.sleep(if ((getSystemService(POWER_SERVICE) as PowerManager).isInteractive) 5 * 60 * 1000L else 15 * 60 * 1000L)
        }
    }

    private fun useLocation(loc: android.location.Location) = Thread {
        try {
            Prefs.addVisits(this, AreaData.areasAt(this, loc.latitude, loc.longitude, 0.0), System.currentTimeMillis())
            if (Prefs.nearMe(this)) {
                val list = AreaData.nearby(this, loc.latitude, loc.longitude)
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
        // סוג התצוגה מההגדרות (במצב שבת - תמיד מסך מלא)
        val style = if (shabbat) 0 else Prefs.alertStyle(this)
        val now0 = System.currentTimeMillis()
        // לכרטיס מעל השעון במסך הראשי (מוצג עד סוף האירוע, גם אחרי סגירת המסך)
        Prefs.setActiveAlert(this, org.json.JSONObject().put("title", title).put("body", body).put("level", level)
            .put("shelter", shelterSec ?: -1).put("firedAt", now0).put("source", srcName)
            .put("mapAreas", (mapAreas ?: areas).joinToString(", ")))   // למפה כשפותחים מהכרטיס
        val full = Intent(this, if (style == 1) AlertPopupActivity::class.java else AlertActivity::class.java)
            .putExtra("title", title).putExtra("body", body).putExtra("level", level)
            .putExtra("shelter", shelterSec ?: -1).putExtra("firedAt", now0)
            .putExtra("source", srcName)
            .putExtra("autoCloseMs", if (shabbat) 60_000L else 0L)
            .putExtra("mapAreas", (mapAreas ?: areas).joinToString(", "))   // בבדיקה: המפה מראה את האזורים שלי
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fullPi = PendingIntent.getActivity(
            this, 1, full, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // מתג צלילים / רטט כבוי - ערוץ בלי צליל ו/או בלי רטט (ערוץ אנדרואיד לא משתנה אחרי שנוצר)
        // מצב שבת עוקף הכל: שעות שקט, נא לא להפריע, מצב שקט, וגם מתגי הצלילים והרטט
        // השתקה זמנית: בלי צליל, בלי רטט ובלי הקראה - גובר על הכל (גם על מצב שבת וצליל חזק)
        val muted = Prefs.mutedNow(this)
        val snd = !muted && (shabbat || Prefs.soundsOn(this)); val vib = !muted && (shabbat || Prefs.vibesOn(this))
        val channel = when {
            quiet -> CH_QUIET
            !snd && !vib -> CH_SILENT
            !snd -> CH_NO_SOUND
            !vib -> CH_NO_VIBE
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
        // (בלי תצוגת טיימר מיוחדת - נראית כמו שאר ההתראות)
        val n = nb.build()
        if (!quiet && !muted && level != LEVEL_END) overrideDnd(force = shabbat)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ALERT, n)

        // מסך מלא גם כשהטלפון פתוח ובשימוש:
        // בבדיקה (האפליקציה בחזית) או עם הרשאת "הצגה מעל אפליקציות אחרות"
        if (!quiet) wakeScreen()
        val screenOff = !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val canShow = forceScreen || shabbat || screenOff || overlayOk()
        if (toMini) main.post { MiniOverlay.showEnd(body) }
        else if (!quiet && canShow) {
            when {
                // מסך כבוי/נעול: חלון ממוזער וכרטיס לא נראים שם - מסך מלא
                screenOff || style <= 1 -> launchOver(full)
                // כרטיס מעל השעון: פותחים את המסך הראשי מעל האפליקציה הפתוחה
                style == 2 -> launchOver(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                // ממוזער: החלון הקטן למעלה (בלי הרשאה - מסך מלא)
                else -> if (overlayOk()) main.post { MiniOverlay.show(this, full) }
                        else launchOver(full)
            }
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

        // עקיפת מצב שקט: הטלפון על שקט/רטט - הטלפון לא ישמיע, אז האפליקציה משמיעה בעצמה.
        // כמו בצופר: מעבירים את הטלפון לרגיל לזמן ההתרעה ומשמיעים (צליל והקראה) בעוצמת ההתראות, ואז מחזירים.
        // אם אי אפשר (אין הרשאת נא לא להפריע / עוצמת ההתראות על 0) - בעוצמת השעון המעורר, כמו קודם
        val speakNow = !quiet && !muted && (Prefs.speakAlerts(this) || shabbat)
        val ringerNow = (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).ringerMode
        val bypass = PHONE_DEFAULTS && (shabbat || Prefs.bypassSilent(this)) && !quiet && !muted &&
            ringerNow != android.media.AudioManager.RINGER_MODE_NORMAL
        val viaNotif = bypass && !Prefs.loudSound(this) && (snd || speakNow) &&
            ringerToNormal(if (level == LEVEL_ALERT) 30_000L else 15_000L)
        speaker?.usage = if (viaNotif) AudioAttributes.USAGE_NOTIFICATION else AudioAttributes.USAGE_ALARM

        val playSound = {
            // צליל חזק: כשמופעל - תמיד, בכל ההתרעות (מקדימה, ירי, סיום), גם בשעות שקט ובמצב שקט.
            // עוצמת השעון המעורר למקסימום למשך הצליל, ואחר כך חוזרת למה שהיה
            val loud = !muted && Prefs.loudSound(this)
            val loudMs = if (level == LEVEL_ALERT) 15000L else 4000L
            if (loud) { boostAlarmVolume(loudMs); playAlarm(level, RingtoneManager.TYPE_ALARM, loudMs) }
            else if (bypass && snd) playAlarm(level, RingtoneManager.TYPE_ALARM, if (level == LEVEL_ALERT) 15000 else 3000,
                if (viaNotif) AudioAttributes.USAGE_NOTIFICATION else AudioAttributes.USAGE_ALARM)
            else if (PHONE_DEFAULTS) Unit   // הטלפון משמיע את צליל ההתראה בעצמו
            else when {
                quiet || !snd -> main.post { ringtone?.stop() }
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
        if (speakNow) {
            val started = java.util.concurrent.atomic.AtomicBoolean(false)
            val once = { if (started.compareAndSet(false, true)) main.post { playSound() } }
            speaker?.speakAlert(title, areas, shelterSec) { once() } ?: once()
            main.postDelayed({ once() }, 8000)
        } else {
            playSound()
        }

        if (!quiet && !PHONE_DEFAULTS && vib) vibrate(level)
        AlertWidget.updateAll(this)
    }

    /** ההתראה בשורת ההתראות: ספירת שהייה במרחב המוגן, לחיצה פותחת את מסך הספירה */
    private var stayNotifPi: PendingIntent? = null
    private var stayTitle = ""; private var stayBody = ""; private var stayEnd = 0L
    private val stayNotif = Runnable {
        if (System.currentTimeMillis() >= stayEnd) return@Runnable
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setColor(0xFFD50000.toInt())
            .setContentTitle("⏳ נשארים במרחב המוגן")
            .setContentText("$stayTitle · $stayBody")
            .setOnlyAlertOnce(true)
            .setContentIntent(stayNotifPi)
            .setAutoCancel(true)
            .setTimeoutAfter(stayEnd - System.currentTimeMillis())
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
    private fun overrideDnd(force: Boolean = false) {
        if (!force && !Prefs.dndOverride(this)) return
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
    /** הרשאת "הצגה מעל אפליקציות אחרות" (canDrawOverlays לפעמים טועה - בודקים גם ב-AppOps) */
    private fun overlayOk(): Boolean {
        if (android.provider.Settings.canDrawOverlays(this)) return true
        return try {
            val ops = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    /**
     * פתיחת מסך ההתרעה מעל אפליקציה אחרת.
     * מאנדרואיד 15 מותר לפתוח מסך מהרקע רק כשיש לאפליקציה חלון צף גלוי -
     * אז מוסיפים חלון שקוף בגודל פיקסל, פותחים את המסך, ומסירים אותו אחרי כמה שניות.
     */
    private fun launchOver(i: Intent) = main.post {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        var v: android.view.View? = null
        if (overlayOk()) try {
            v = android.view.View(this)
            wm.addView(v, android.view.WindowManager.LayoutParams(1, 1,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START })
        } catch (_: Exception) { v = null }
        try { startActivity(i) } catch (_: Exception) { }
        main.postDelayed({ try { v?.let { wm.removeView(it) } } catch (_: Exception) { } }, 4000)
    }

    private fun wakeScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        try {
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "redalert:screen").acquire(20_000)
        } catch (_: Exception) { }
    }

    /** התראה "צופר חירום פעיל – מאזין" עם מנגינת האפליקציה (נעלמת לבד אחרי 15 שניות) */
    private fun announceListening() {
        val n = Notification.Builder(this, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setColor(0xFF2E7D32.toInt())
            .setContentTitle("צופר חירום פעיל")
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
    /** עוצמת ערוץ השעון המעורר למקסימום לזמן מוגבל, ואז מחזירים את העוצמה הקודמת */
    private var savedAlarmVol = -1
    private fun boostAlarmVolume(ms: Long) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val s = android.media.AudioManager.STREAM_ALARM
            if (savedAlarmVol < 0) savedAlarmVol = am.getStreamVolume(s)
            am.setStreamVolume(s, am.getStreamMaxVolume(s), 0)
            main.removeCallbacks(restoreAlarmVol)
            main.postDelayed(restoreAlarmVol, ms + 500)
        } catch (_: Exception) { }
    }
    private val restoreAlarmVol = Runnable {
        try {
            if (savedAlarmVol >= 0) (getSystemService(AUDIO_SERVICE) as android.media.AudioManager)
                .setStreamVolume(android.media.AudioManager.STREAM_ALARM, savedAlarmVol, 0)
        } catch (_: Exception) { }
        savedAlarmVol = -1
    }

    /**
     * הטלפון על שקט/רטט: מעבירים לרגיל לזמן ההתרעה (כדי להשמיע בעוצמת ההתראות), ומחזירים אחרי ms.
     * מחזיר false אם אי אפשר - אז משמיעים בערוץ השעון המעורר
     */
    private var ringerSaved = -1
    private fun ringerToNormal(ms: Long): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        return try {
            if (ringerSaved == -1) ringerSaved = am.ringerMode
            am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
            if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL ||
                am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION) == 0) { restoreRinger.run(); return false }
            main.removeCallbacks(restoreRinger)
            main.postDelayed(restoreRinger, ms)
            true
        } catch (_: Exception) { restoreRinger.run(); false }
    }
    private val restoreRinger = Runnable {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        // מחזירים רק אם המשתמש לא שינה בינתיים בעצמו
        if (ringerSaved != -1 && am.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL)
            try { am.ringerMode = ringerSaved } catch (_: Exception) { }
        ringerSaved = -1
    }

    private fun playAlarm(level: Int, type: Int, durationMs: Long, usage: Int = AudioAttributes.USAGE_ALARM) {
        val uri = Sounds.uri(this, Sounds.effective(this, level), level) ?: return
        main.post {
            ringtone?.stop()
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(usage)
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
            NotificationChannel(CH_ALERT, "התראות צופר חירום", NotificationManager.IMPORTANCE_HIGH).apply {
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
            NotificationChannel(CH_NO_SOUND, "התראות – רטט בלי צליל", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(true)
                setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        nm.createNotificationChannel(
            NotificationChannel(CH_NO_VIBE, "התראות – צליל בלי רטט", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(false); vibrationPattern = longArrayOf(0)
                setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        nm.createNotificationChannel(
            NotificationChannel(CH_SILENT, "התראות – בלי צליל ורטט", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null); enableVibration(false); vibrationPattern = longArrayOf(0)
                setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "סיום אירוע", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
    }
}

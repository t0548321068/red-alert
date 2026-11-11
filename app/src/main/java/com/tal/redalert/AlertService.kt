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
        /** חלון ההצטרפות לאירוע קיים: ירי / מקדימה - דקה וחצי; סיום - 5 דקות */
        const val JOIN_MS = 90 * 1000L
        /**
         * האם יישוב חדש מצטרף לאירוע הקודם (מעדכן את המסך הקיים) או אירוע חדש (מסך חדש).
         * אותו סוג, לא מטח חדש, אותו אזור בארץ, ובתוך החלון. בלי טלפון - נבדק בבדיקות
         */
        fun joins(lastLevel: Int, lastAt: Long, level: Int, newBarrage: Boolean, sameRegion: Boolean, now: Long): Boolean =
            lastLevel == level && !newBarrage && sameRegion &&
                (now - lastAt < JOIN_MS || (level == LEVEL_END && now - lastAt < Prefs.DUP_MS))

        internal const val URL_ALERTS = "https://www.oref.org.il/warningMessages/alert/Alerts.json"   // הכתובת החדשה (נובמבר 2026) - הישנה מחזירה 404
        internal const val URL_HISTORY = "https://www.oref.org.il/warningMessages/alert/History/AlertsHistory.json"
        internal const val URL_ARCHIVE = OrefArchive.BASE + "lastDay"   // הארכיון החדש (נובמבר 2026)
        internal const val POLL_MS = 2000L
        internal const val HISTORY_POLL_MS = 30000L   // גיבוי בלבד
        internal const val HISTORY_WINDOW_MS = 90000L
        internal const val CH_SERVICE = "service"
        internal const val CH_ALERT = "alerts_v3"   // בינתיים: צליל ורטט ברירת מחדל של הטלפון
        /** בינתיים כל הצלילים והרטטים - של הטלפון (לא של האפליקציה) */
        const val PHONE_DEFAULTS = true
        internal val HEADSETS = setOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)
        /** הרמקול של הטלפון */
        fun builtinSpeaker(c: Context): android.media.AudioDeviceInfo? = try {
            (c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } catch (_: Exception) { null }
        internal const val ID_SERVICE = 1
        internal const val ID_ALERT = 2
        const val ACTION_TEST = "test"
        const val ACTION_TEST_SEQ = "test_seq"   // בדיקת אירוע מלא: מקדימה, ירי, סיום
        const val ACTION_SILENCE = "silence"
        const val ACTION_PUSH = "push"
        const val ACTION_PUSH_ALERT = "push_alert"   // התרעה מהשרת (Firebase) כשהשירות לא רץ
        const val ACTION_WATCHDOG = "watchdog"
        // זמני השומר - מההגדרות בשרת (config.json): בלי שרת כל 5 דקות, עם שרת כל 15

        /** השרת הודיע שיש גרסה חדשה - בודקים מיד (במקום שהטלפון יבדוק בעצמו כל חצי שעה) */
        fun onServerUpdate() { instance?.onUpdatePing() }

        /** שומר: מעיר את השירות כל ~5 דקות גם כשהטלפון ישן */
        fun scheduleWatchdog(c: Context) {
            val am = c.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = PendingIntent.getBroadcast(c, 30, Intent(c, WatchdogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val delay = if (serverOk(c)) {
                // השרת שותק - מתעוררים בדיוק כשסימן החיים מתיישן, ועוברים לבדוק לבד (ולא עד 15 דקות אחר כך)
                val staleIn = serverHbAt(c) + Config.serverStaleMs - System.currentTimeMillis() + 5_000
                minOf(Config.watchdogServerMs, staleIn.coerceAtLeast(60_000))
            } else Config.watchdogMs
            // מדויקת כשמותר - רק אזעקה מדויקת מאפשרת להחזיר שירות שנסגר (אנדרואיד 12+)
            val at = android.os.SystemClock.elapsedRealtime() + delay
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                    am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (_: Exception) {
                try { am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) } catch (_: Exception) { }
            }
        }

        /** האם היה אירוע ב-10 הדקות האחרונות (אז בודקים את כל המקורות בתדירות גבוהה) */
        @Volatile var lastEventAt = 0L
        /** השירות הפעיל (לשומר) */
        @Volatile var instance: AlertService? = null
        fun inEvent() = System.currentTimeMillis() - lastEventAt < 10 * 60 * 1000L

        /** היישובים מתוך ההתרעה שהם שלי: לפי המיקום + האזורים שהוספתי. אף פעם לא "כל הארץ" */
        fun mine(c: Context, areas: List<String>): List<String> =
            mineOf(areas, Prefs.cities(c), if (Prefs.nearMe(c)) Prefs.nearbyAreas(c) else emptyList()) { a, f -> AreaData.matches(c, a, f) }

        /** אותו דבר בלי טלפון (נבדק בבדיקות): picks - הבחירות שלי, near - האזורים סביב המיקום שלי */
        fun mineOf(areas: List<String>, picks: List<String>, near: List<String>, matches: (String, String) -> Boolean): List<String> =
            areas.filter { a ->
                picks.any { f -> matches(a, f) } ||   // יישוב או אזור שלם
                    near.any { n -> n == a || n.startsWith("$a -") || a.startsWith("$n -") }
            }

        /**
         * גיבוי: התרעה מהשרת הגיעה כשהשירות לא רץ, ואנדרואיד לא הרשה להפעיל אותו -
         * מציגים לפחות התראה רגילה (עם הצליל של הטלפון), כדי שההתרעה לא תלך לאיבוד
         */
        internal val fallbackSeen = HashMap<String, Long>()
        @Synchronized
        fun fallbackNotify(c: Context, rawTitle: String, areas: List<String>) {
            if (!Prefs.enabled(c) || Prefs.scheduleOffNow(c)) return
            val now = System.currentTimeMillis()
            fallbackSeen.entries.removeAll { now - it.value > Prefs.DUP_MS }
            // השרת שולח כל התרעה פעמיים (וכל הארץ - בכמה חלקים): מציגים כל יישוב פעם אחת
            val my = mine(c, areas).filter { fallbackSeen.put(rawTitle + "|" + it, now) == null }.ifEmpty { return }
            val nm = c.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            // מושתק / שעות שקט (לא בירי) - ערוץ בלי צליל ורטט
            val silent = Prefs.mutedNow(c) || (levelOf(rawTitle) != LEVEL_ALERT && Prefs.isQuietNow(c))
            val ch = if (silent) CH_SILENT else CH_ALERT
            if (nm.getNotificationChannel(ch) == null) nm.createNotificationChannel(
                if (silent) NotificationChannel(CH_SILENT, "התראות – בלי צליל ורטט", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null); enableVibration(false); vibrationPattern = longArrayOf(0)
                    setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC }
                else NotificationChannel(CH_ALERT, "התראות צופר חירום", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true); setBypassDnd(true); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
            val title = displayTitle(rawTitle)
            val body = my.joinToString(", ")
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(ID_ALERT, Notification.Builder(c, ch)
                .setSmallIcon(R.drawable.ic_stat_siren).setColor(0xFFD50000.toInt())
                .setLargeIcon(android.graphics.drawable.Icon.createWithResource(c, R.drawable.notif_large))   // אייקון האפליקציה בצבע, בתוך ההתראה
                .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_ALARM).setContentIntent(open).setFullScreenIntent(open, true)
                .setAutoCancel(true).build())
        }

        /**
         * השרת שלנו בודק את כל המקורות ושולח כל התרעה דרך Firebase, ופעם ב-10 דקות סימן חיים.
         * כל עוד סימן החיים מגיע - הטלפון לא בודק בעצמו (חוסך סוללה). 35 דקות בלי סימן חיים - חוזרים לבדוק לבד
         */
        /** מתי הגיע סימן החיים האחרון מהשרת (0 = עוד לא) */
        fun serverHbAt(c: Context): Long = c.getSharedPreferences("server", Context.MODE_PRIVATE).getLong("hb", 0L)
        /** השרת עלה לאחרונה והמקורות שלו עוד מתחברים (לפי סימן החיים האחרון, לכל היותר 11 דקות) */
        /** שרשרת הגיבוי מסימן החיים האחרון: (מי שלח, מצב המשני, מצב הממסר) */
        fun chain(c: Context): Triple<String, String, String> {
            val sp = c.getSharedPreferences("server", Context.MODE_PRIVATE)
            return Triple(sp.getString("by", "primary") ?: "primary", sp.getString("sec", "") ?: "", sp.getString("rel", "") ?: "")
        }
        /** מאיפה כל מקור מחובר אצל השרת: "oref" -> "cf" / "relay" (מה שלא מופיע - ישירות) */
        fun viaMap(c: Context): Map<String, String> =
            (c.getSharedPreferences("server", Context.MODE_PRIVATE).getString("vi", "") ?: "").split(',')
                .mapNotNull { it.split('=').takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()
        fun serverSourcesConnecting(c: Context): Boolean {
            val sp = c.getSharedPreferences("server", Context.MODE_PRIVATE)
            return sp.getBoolean("cn", false) && System.currentTimeMillis() - sp.getLong("hb", 0L) < 11 * 60 * 1000L
        }
        fun serverOk(c: Context): Boolean = System.currentTimeMillis() - serverHbAt(c) < Config.serverStaleMs
        fun onServerHeartbeat(c: Context, up: List<String>, connecting: Boolean = false, by: String = "primary", sec: String = "", rel: String = "", vi: String = "") {
            val now = System.currentTimeMillis()
            // שרשרת הגיבוי: מי שלח (ראשי / משני / ממסר) ומצב המשני והממסר - לעמוד המקורות
            c.getSharedPreferences("server", Context.MODE_PRIVATE).edit().putLong("hb", now).putString("up", up.joinToString(","))
                .putBoolean("cn", connecting).putString("by", by).putString("vi", vi)
                .apply { if (sec.isNotEmpty()) putString("sec", sec); if (rel.isNotEmpty()) putString("rel", rel) }.apply()
            SourceHealth.viaServer(up, now)
            instance?.syncLocalSources()
        }

        /** בטא הופעלה/כובתה - התחברות מחדש להתראות העדכון */
        fun reloadPush(c: Context) {
            c.startService(Intent(c, AlertService::class.java).setAction(ACTION_PUSH))
        }

        /** עוצר צליל ורטט של ההתראה הנוכחית (ההתראה עצמה נשארת) */
        fun silence(c: Context) {
            c.startService(Intent(c, AlertService::class.java).setAction(ACTION_SILENCE))
        }

        internal const val CH_INFO = "info_v2"
        internal const val CH_NO_SOUND = "alerts_nosound"   // רטט בלי צליל
        internal const val CH_NO_VIBE = "alerts_novibe"     // צליל בלי רטט
        internal const val CH_SILENT = "alerts_silent"      // בלי צליל ובלי רטט
        internal const val CH_QUIET = "quiet"
        internal const val CH_WATCH = "watchdog_v4"
        internal const val ID_WATCH = 4
        internal const val UNPROTECTED_AFTER_MS = 2 * 60 * 1000L
        internal const val CH_STAY = "stay_v4"   // ספירת השהייה - בלי צליל (לא מצלצל שוב אחרי ההתרעה)
        internal const val CH_UPDATE = "updates_v5"  // ערוץ חדש - הצליל לפי שם הקובץ (לא לפי מספר שמשתנה בין גרסאות)
        internal const val ID_UPDATE = 3

        const val LEVEL_ALERT = 0
        const val LEVEL_PRE = 1
        const val LEVEL_END = 2

        /** הקובץ החי של פיקוד העורף: (מזהה, כותרת, יישובים), או null אם אין התרעה. נבדק בבדיקות */
        fun orefLive(text: String): Triple<String, String, List<String>>? {
            if (text.isEmpty()) return null
            val json = JSONObject(text)
            val id = json.optString("id")
            if (id.isEmpty()) return null
            val arr = json.optJSONArray("data") ?: return null
            return Triple(id, json.optString("title", "התרעה"), (0 until arr.length()).map { arr.getString(it) })
        }

        /**
         * ההיסטוריה / הארכיון של פיקוד העורף: התרעות מהדקה וחצי האחרונות, מקובצות לפי סוג + זמן.
         * (כותרת, זמן, יישובים). נבדק בבדיקות
         */
        fun orefHistory(text: String, now: Long, windowMs: Long = HISTORY_WINDOW_MS): List<Triple<String, Long, List<String>>> {
            if (!text.startsWith("[")) return emptyList()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Jerusalem")
            }
            val arr = org.json.JSONArray(text)
            // לפי סוג + זמן ההתראה המקורי (כדי שהתראה ישנה לא תיחשב חדשה)
            val byTitle = LinkedHashMap<Pair<String, Long>, MutableList<String>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val date = o.optString("alertDate").replace('T', ' ').take(19)
                val t = try { fmt.parse(date)?.time } catch (_: Exception) { null } ?: continue
                if (now - t > windowMs) continue
                val title = o.optString("title").ifEmpty { o.optString("category_desc", "התרעה") }
                byTitle.getOrPut(title to t) { mutableListOf() }.add(o.optString("data"))
            }
            return byTitle.map { (k, areas) -> Triple(k.first, k.second, areas) }
        }

        /** פיקוד העורף מחזיר לפעמים UTF-8 עם BOM ולפעמים UTF-16 */
        fun decodeOref(b: ByteArray): String = when {
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() ->
                String(b, 2, b.size - 2, Charsets.UTF_16LE)
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
                String(b, 3, b.size - 3, Charsets.UTF_8)
            else -> String(b, Charsets.UTF_8)
        }

        /** סיווג לפי נוסח ההודעה של פיקוד העורף - המילים בהגדרות מהשרת (config.json) */
        fun levelOf(title: String): Int = when {
            Config.endWords.any { title.contains(it) } || AlertUi.update(title) -> LEVEL_END
            // התרעה מקדימה: "בדקות הקרובות...", "איום מלבנון" וכד', "התקרבו למרחב המוגן"
            Config.preWords.any { title.contains(it) } -> LEVEL_PRE
            else -> LEVEL_ALERT
        }

        /** הכותרת שמוצגת: כל סוגי הסיום = "האירוע הסתיים"; "איום מלבנון" = "התרעה מקדימה - איום מלבנון" */
        fun displayTitle(raw: String): String {
            val t = raw.trim()
            return if (levelOf(t) == LEVEL_END && !AlertUi.update(t)) Config.endDisplay else Config.prePrefix(t) ?: t
        }

        /**
         * manual = הופעל בלחיצה (על "מוגן", בדיקה, שינוי הגדרה) - בלי התראת "מאזין".
         * בכל הפעלה אחרת (הדלקת הטלפון, אחרי עדכון, חזרה אחרי שנסגר) - התראה עם המנגינה.
         */
        internal const val ID_OFF = 8
        internal const val ID_COUNT = 9                 // ספירה לאחור למרחב המוגן - התראה נוספת
        internal const val CH_COUNT = "countdown"       // בלי צליל ורטט

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
                .setLargeIcon(android.graphics.drawable.Icon.createWithResource(c, R.drawable.notif_large))   // אייקון האפליקציה בצבע, בתוך ההתראה
                .setColor(0xFFD50000.toInt())
                .setContentTitle("⚠️ לא מוגן")
                .setContentText("צופר חירום כבוי – התרעות לא יגיעו")
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "הפעלה", enable).build())
                .setOngoing(true)
                .build())
        }

        fun clearOff(c: Context) =
            (c.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_OFF)

        fun start(c: Context, testTitle: String? = null, manual: Boolean = false, testBody: String? = null) {
            clearOff(c)
            val i = Intent(c, AlertService::class.java).putExtra("manual", manual || testTitle != null)
            if (testTitle != null) {
                i.action = ACTION_TEST
                i.putExtra("title", testTitle)
                testBody?.let { i.putExtra("body", it) }
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

    @Volatile internal var running = false
    internal var lastId = ""
    internal var tzofar: TzofarSource? = null
    internal var telegram: List<TelegramSource> = emptyList()
    internal var updatePush: UpdatePush? = null
    internal var speaker: Speaker? = null

    /** כתיבה להיסטוריה ולפיד - ברקע ולפי הסדר, אחרי הצלצול (שלא תעכב אותו) */
    internal val bg = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** ברקע; אם השירות כבר נסגר - מיד, כאן (ההתרעה לא נזרקת בגלל זה) */
    internal fun later(r: () -> Unit) {
        try { bg.execute(r) } catch (_: java.util.concurrent.RejectedExecutionException) { try { r() } catch (_: Exception) { } }
    }
    /** סוג וזמן ההתרעה האחרונה שהוקפצה (לאיחוד מקורות לאותה שורה) */
    @Volatile internal var lastFired: Pair<Int, Long>? = null

    /** מניעת כפילות בין מקורות (נבדק בבדיקות האוטומטיות) */
    internal val dedup = AlertDedup()
    internal val DEDUP_MS = Prefs.DUP_MS
    /** התראות מאותו סוג בתוך דקה וחצי = אותו אירוע -> שורה אחת ברשימה */
    internal val MERGE_MS = JOIN_MS
    /** מזהה האירוע של פיקוד העורף של ההתרעה האחרונה שצלצלה - להבחין בין כפילות למטח חדש */
    internal var lastFiredEid = ""
    /** האזורים של ההתרעה האחרונה שצלצלה (כולל מה שהצטרף אליה) */
    @Volatile internal var lastFiredAreas: MutableSet<String> = mutableSetOf()

    /**
     * אותו אירוע מכמה מקורות מגיע בחלקים - מצרפים לשורה הקודמת רק אזורים באותו מחוז.
     * מחוז אחר (למשל שדרות ואז תל אביב) = התרעה נפרדת עם צליל ומסך. מחוז לא ידוע - כמו קודם (מצרפים)
     */
    internal fun sameRegion(fresh: List<String>): Boolean {
        val prev = lastFiredAreas.ifEmpty {
            Prefs.activeAlert(this)?.optString("mapAreas")?.split(", ")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        }
        if (prev.isEmpty()) return true
        val dm = AreaData.districtMap(this)
        val pd = prev.mapNotNull { dm[it] }.toSet()
        val fd = fresh.mapNotNull { dm[it] }
        if (pd.isEmpty() || fd.size < fresh.size) return true
        return fd.any { it in pd }
    }

    /** אזורים שהצטרפו להתרעה הפעילה (בלי צליל) נכנסים גם לרשימה שלה */
    internal fun addActiveAreas(level: Int, add: List<String>) {
        val a = Prefs.activeAlert(this)?.takeIf { it.optInt("level") == level } ?: return
        fun list(k: String) = a.optString(k).split(", ").map { it.trim() }.filter { it.isNotEmpty() }
        val map = (list("mapAreas") + add).distinct()
        val body = (list("body") + add).distinct()
        Prefs.setActiveAlert(this, org.json.JSONObject(a.toString())
            .put("mapAreas", map.joinToString(", ")).put("body", body.joinToString(", ")))
    }

    /**
     * יישובים שהצטרפו לאותו אירוע (שניות אחרי - "זרעית" ואז "שומרה"): בלי צליל ומסך חדשים,
     * אבל מוצגים בכל מקום שההתרעה מוצגת - מסך ההתרעה (תגיות + מפה), החלון הממוזער, הכרטיס וההתראה
     */
    internal fun showJoined(level: Int, add: List<String>) {
        val a = Prefs.activeAlert(this)?.takeIf { it.optInt("level") == level } ?: return
        val body = a.optString("body")
        // מסך ההתרעה פתוח - מעדכנים אותו (התגיות והסיכות של היישובים החדשים מתווספות)
        if (AlertActivity.open > 0) try {
            val st = if (Shabbat.active(this)) 0 else Prefs.alertStyleFor(this, level)
            startActivity(Intent(this, if (st == 1) AlertPopupActivity::class.java else AlertActivity::class.java)
                .putExtra("title", a.optString("title")).putExtra("level", level)
                // ירי: המסך מוסיף את החדשים לקיימים (נופלות סיכות). שאר הסוגים נבנים מחדש - עם כל היישובים
                .putExtra("body", if (level == LEVEL_ALERT) add.joinToString(", ") else body)
                .putExtra("mapAreas", if (level == LEVEL_ALERT) add.joinToString(", ") else a.optString("mapAreas"))
                .putExtra("shelter", a.optInt("shelter", -1)).putExtra("firedAt", a.optLong("firedAt"))
                .putExtra("source", a.optString("source"))
                .putExtra("autoCloseMs", AlertUi.keepMs(level)?.let { k -> (a.optLong("firedAt") + k - System.currentTimeMillis()).coerceAtLeast(1000L) } ?: 0L)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) { }
        // החלון הממוזער נבנה מחדש - כל היישובים, והזמנים לפי העדכון האחרון
        val miniInMain = MainActivity.shown?.let { MiniOverlay.hostedIn(it.miniHost) } == true
        if (MiniOverlay.isShowing() || miniInMain) MiniOverlay.show(this, Intent()
            .putExtra("title", a.optString("title")).putExtra("body", body).putExtra("level", level)
            .putExtra("shelter", a.optInt("shelter", -1)).putExtra("firedAt", a.optLong("firedAt"))
            .putExtra("source", a.optString("source")).putExtra("mapAreas", a.optString("mapAreas")))
        // ההתראה: אותה התראה עם כל היישובים, בלי צליל נוסף
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val old = nm.activeNotifications.firstOrNull { it.id == ID_ALERT }?.notification ?: return
            val sh = a.optInt("shelter", -1)
            val text = if (sh >= 0 && level == LEVEL_ALERT) "$body · זמן למרחב מוגן: ${AreaData.shelterText(sh)}" else body
            val nb = Notification.Builder.recoverBuilder(this, old)
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setOnlyAlertOnce(true)
            nm.notify(ID_ALERT, nb.build())
        } catch (_: Exception) { }
    }
    /** דור הצליל - ראו fire() */
    @Volatile internal var soundGen = 0

    internal var wakeLock: PowerManager.WakeLock? = null
    /** השומר: כמה שניות ער - החיבורים מתחדשים ופיקוד העורף נבדק - ותזמון הבא */
    fun onWatchdog() {
        syncLocalSources()   // השרת הפסיק לשלוח סימן חיים - צופר חוזר לפעול בטלפון
        try { Shabbat.refresh(this) } catch (_: Exception) { }   // זמני שבת וחג מהשרת (לכל היותר פעם בשעה)
        Prefs.refreshBeta(this)   // מכשירי הבטא - הרשימה בשרת
        DataFiles.sync(this)      // הגדרות, יישובים והקלטות מהשרת (לכל היותר פעם בשעה)
        if (!serverOk(this)) awake(6_000)   // מספיק לוודא שהחיבור לצופר חי ולבדיקה אחת של פיקוד העורף
        tzofar?.ensureConnected()
        // הטלפון כבר ער בשביל השומר - מנצלים את זה לבדיקת עדכון (לכל היותר פעם בחצי שעה, בקשה קטנה אחת)
        if (!serverOk(this)) checkUpdateIfDue(30 * 60 * 1000L)   // כשהשרת מחובר - הוא מודיע על גרסה חדשה
        scheduleWatchdog(this)
    }

    /** בדיקת גרסה חדשה רק אם עבר מספיק זמן מהבדיקה הקודמת (שעון אמיתי, כולל זמן שינה) */
    @Volatile internal var lastUpdCheck = 0L
    internal fun checkUpdateIfDue(maxAge: Long) {
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
    internal val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> if (!serverOk(c)) checkUpdateIfDue(10 * 60 * 1000L)
                Intent.ACTION_SCREEN_ON -> if (!serverOk(c)) livePush(true)
                Intent.ACTION_SCREEN_OFF -> livePush(false)
            }
        }
    }

    internal fun livePush(on: Boolean) {
        if (on) {
            if (updatePush != null) return
            updatePush = UpdatePush(beta = { Prefs.betaUpdates(this) }, onPing = ::onUpdatePing).also { it.start() }
        } else {
            updatePush?.stop(); updatePush = null
        }
    }

    /** מחזיק את הטלפון ער לזמן קצוב (לא כל הזמן) */
    internal fun awake(ms: Long) { try { wakeLock?.acquire(ms) } catch (_: Exception) { } }
    internal var ringtone: Ringtone? = null
    internal val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        Prefs.dropOldVisits(this)
        val n = Notification.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setColor(0xFFD50000.toInt())   // צבע האפליקציה בהתראה
            .setContentTitle("צופר חירום פעיל")
            .setContentText("מאזין להתרעות פיקוד העורף")
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
        try { restorePending() } catch (_: Exception) { }
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

    /** צופר בטלפון רק כשהשרת לא זמין (חיבור קבוע = התעוררויות של הרדיו) */
    @Synchronized
    fun syncLocalSources() {
        if (!running) return
        if (serverOk(this)) { tzofar?.stop(); tzofar = null }
        else if (tzofar == null)
            tzofar = TzofarSource(this) { title, areas -> handle(title, areas, source = "tzofar") }.also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_WATCHDOG) { onWatchdog(); return START_STICKY }
        if (intent?.action == ACTION_PUSH_ALERT) {
            val t = intent.getStringExtra("title"); val a = intent.getStringArrayExtra("areas")
            val at = intent.getLongExtra("at", System.currentTimeMillis()); val src = intent.getStringExtra("src") ?: "push"
            if (t != null && a != null) Thread { try { handle(t, a.toList(), at, src, intent.getStringExtra("eid") ?: "") } catch (_: Exception) { } }.start()
            return START_STICKY
        }
        if (intent?.action == ACTION_PUSH) {
            updatePush?.reconnect()
            return START_STICKY
        }
        if (intent?.action == ACTION_SILENCE) {
            soundGen++   // לפני עצירת ההקראה - סוף ההקראה לא יפעיל את הצופר
            speaker?.stop()
            main.post { ringtone?.stop(); stopSpeaker(); main.removeCallbacks(releaseFocus); releaseFocus.run() }
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
            testFire(intent.getStringExtra("title") ?: "ירי רקטות וטילים", intent.getStringExtra("body") ?: "התרעת בדיקה")
        }
        return START_STICKY
    }

    /** שלבי בדיקת האירוע המלא */
    internal val seqEnd = Runnable { testFire("האירוע הסתיים") }
    internal val seqAlert = Runnable {
        val shelter = testFire("ירי רקטות וטילים")
        main.postDelayed(seqEnd, shelter * 1000L + 20_000)
    }

    /** התראת בדיקה (מחזירה את זמן ההגעה למרחב המוגן בשניות) */
    internal fun testFire(title: String, body: String = "התרעת בדיקה"): Int {
        val mine = Prefs.cities(this) + Prefs.nearbyAreas(this)
        // זמן ההתגוננות בבדיקה - לפי המיקום שלי (ולא לפי כל האזורים הנוספים: עם "כל הארץ" הקצר ביותר הוא "מיידי",
        // ואז הבדיקה דילגה ישר ל"נשארים במרחב המוגן" ולא הראתה את אייקון סוג ההתרעה). לפחות 15 שניות
        val shelter = (AreaData.shelterSeconds(this, Prefs.nearbyAreas(this))
            ?: AreaData.shelterSeconds(this, mine) ?: 15).coerceAtLeast(15)
        fire(title, listOf(body), forceScreen = true, test = true,
            shelter = shelter, mapAreas = mine)
        return shelter
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        running = false
        soundGen++
        speaker?.shutdown()
        tzofar?.stop()
        telegram.forEach { it.stop() }
        updatePush?.stop()
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) { }
        ringtone?.stop(); stopSpeaker()
        main.removeCallbacks(stayDone)
        main.removeCallbacks(stayNotif); main.removeCallbacks(countNotif)
        main.removeCallbacks(seqAlert); main.removeCallbacks(seqEnd); main.removeCallbacks(updateLater)
        wakeLock?.let { if (it.isHeld) it.release() }
        bg.shutdown()   // מה שכבר בתור נכתב עד הסוף
        super.onDestroy()
    }

    /**
     * חיבור קבוע לפיקוד העורף: אותו חיבור מאובטח משמש את כל הבדיקות (כל 2 שניות),
     * במקום לפתוח ולסגור חיבור חדש בכל בדיקה - חוסך הרבה סוללה, באותה מהירות
     */
    internal val orefHttp = okhttp3.OkHttpClient.Builder()
        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(2, 5, java.util.concurrent.TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

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

    /** עדכון שנדחה בגלל אירוע - ניסיון חוזר */
    internal val updateLater = Runnable { Thread({ try { notifyIfNewVersion() } catch (_: Exception) { } }, "update-later").start() }
    // מנעול נפרד: בדיקת הגרסה (רשת, עד עשרות שניות) לא תעכב את handle() של התרעות
    internal val updLock = Any()
    /** נקודת כניסה משותפת לכל המקורות: סינון ערים + מניעת כפילות */
    @Synchronized
    fun handle(rawTitle: String, rawAreas: List<String>, eventTime: Long = TimeSync.now(this),   // שעון השרת - כמו בהתרעות מהשרת
               source: String = "", eid: String = "") {
        // תזמון הגנה: מחוץ לימים ולשעות שנבחרו - לא מקבלים התרעות
        if (Prefs.scheduleOffNow(this)) return
        lastEventAt = System.currentTimeMillis()   // אירוע בארץ - בבדיקה עצמית בודקים בקצב מהיר
        val level = levelOf(rawTitle)
        // כל סוגי הסיום ("החשש הוסר" וכו') מוצגים כ"האירוע הסתיים"; "איום מלבנון" - "התרעה מקדימה - איום מלבנון"
        val title = displayTitle(rawTitle)
        val now = System.currentTimeMillis()
        // בלי כפילויות בתוך ההודעה עצמה
        // "ברחבי הארץ" מאוחד ל"כל הארץ" - בכל מקום שמוצג (פיד, התרעות אחרונות, מסך ההתרעה)
        val areas = rawAreas.map { it.trim() }.filter { it.isNotBlank() }.map { AreaData.unify(it) }
            .distinct()   // לפי השם המלא - "ירושלים - מרכז" ו"ירושלים - צפון" נשארים שניהם

        // פיד ארצי: כל ההתראות, לפני הסינון לפי אזורים (בלי צליל)
        val feedNew = dedup.feedNew(areas, level, eventTime)
        if (feedNew.isNotEmpty()) {
            val e = Prefs.Entry("", title, feedNew.joinToString(", "), level, now)
            later { try { Prefs.addFeed(this, e, MERGE_MS) } catch (_: Exception) { } }
        }

        // רק היישובים שלי, ובלי כפילות בין מקורות (מטח חדש - כן)
        val fresh = dedup.fresh(mine(this, areas), level, eventTime, eid)
        if (fresh.isEmpty()) return
        awake(60_000)   // התרעה באזור שלי - הטלפון נשאר ער לזמן הטיפול בה

        // המקור הכי מהיר כבר התריע על האירוע הזה (לפני פחות מדקה וחצי) -
        // היישובים הנוספים מצטרפים לאותה שורה, בלי צליל ומסך נוספים.
        // ההתרעה האחרונה נשמרת בזיכרון - בלי לקרוא את כל ההיסטוריה לפני הצלצול
        val last = lastFired ?: Prefs.history(this).firstOrNull()?.let { it.level to it.ts }
        val newBarrage = eid.isNotEmpty() && lastFiredEid.isNotEmpty() && eid != lastFiredEid   // מטח חדש - לא מצרפים לקודם
        if (last != null && joins(last.first, last.second, level, newBarrage, level == LEVEL_END || sameRegion(fresh), now)) {
            lastFiredAreas += fresh
            later { try { Prefs.mergeFirstHistory(this, fresh); AlertWidget.updateAll(this) } catch (_: Exception) { } }
            if (level == LEVEL_END) {
                // סיום: מצטרף בשקט (פיקוד העורף שולח סיום ארצי בכמה חלקים) - "האירוע הסתיים" לאזור שהצטרף יסגור את מסך הירי
                addActiveAreas(level, fresh)
                main.post { showJoined(level, fresh) }
            } else fire(title, fresh, source = source, join = true)   // מצלצל ומתריע, אבל מעדכן את המסך הקיים
            return
        }
        // ירי פעיל (עד סוף השהייה במרחב המוגן): מקדימה, או סיום שלא כולל את אזורי הירי -
        // נרשמים בהיסטוריה בלי להחליף את מסך הירי ואת ספירת השהייה
        if (level != LEVEL_ALERT && Prefs.stayUntil(this) > now) {
            val act = Prefs.activeAlert(this)?.takeIf { it.optInt("level") == LEVEL_ALERT }
            val fired = act?.optString("mapAreas")?.split(", ")?.map { it.trim() }?.toSet() ?: emptySet()
            val endsMine = level == LEVEL_END && fresh.any { it in fired }
            if (act != null && !endsMine) {
                val e = Prefs.Entry(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
                    title, fresh.joinToString(", "), level, now, if (source.isEmpty()) "" else SourceHealth.name(source))
                later { try { Prefs.addHistory(this, e); AlertWidget.updateAll(this) } catch (_: Exception) { } }
                return
            }
        }
        lastFired = level to now
        lastFiredAreas = fresh.toMutableSet()
        if (eid.isNotEmpty()) lastFiredEid = eid
        fire(title, fresh, source = source)
    }


    /**
     * "התרעות לפי מיקום": כל 2 דקות לוקח מיקום עדכני (לא רק מה שהטלפון זוכר)
     * ומזהה באילו אזורים המכשיר נמצא עכשיו.
     */
    /**
     * "התרעות לפי מיקום": כל 5 דקות. קודם מיקום מהרשת (כמעט בלי סוללה);
     * GPS נדלק רק אם זזנו (מעל 300 מ׳) או שהמיקום מהרשת לא מספיק מדויק.
     */
    internal var lastGps: android.location.Location? = null
    internal fun fire(title: String, areas: List<String>, forceScreen: Boolean = false, shelter: Int? = null,
                     test: Boolean = false, source: String = "", mapAreas: List<String>? = null,
                     join: Boolean = false) {
        val srcName = if (test) "בדיקה" else if (source.isEmpty()) "" else SourceHealth.name(source)
        val body = areas.joinToString(", ")
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        // בדיקה - לא נשמרת בהיסטוריה, במפה ובווידג'ט. נכתב ברקע, אחרי ההתראה (שלא יעכב את הצלצול)
        val entry = Prefs.Entry(time, title, body, levelOf(title), System.currentTimeMillis(), srcName)

        val level = levelOf(title)
        // מצב שבת: כל התראה במסך מלא לדקה אחת, עם הקראה (גם אם כבויה), בלי שעות שקט
        val shabbat = Shabbat.active(this, cached = false)   // בהתרעה - תמיד בדיקה עדכנית
        val quiet = !shabbat && level != LEVEL_ALERT && Prefs.isQuietNow(this)
        val shelterSec = if (AlertUi.noShelter(title)) null else shelter ?: if (level == LEVEL_ALERT) AreaData.shelterSeconds(this, areas) else null
        // סוג התצוגה מההגדרות (במצב שבת - תמיד מסך מלא)
        val style = if (shabbat) 0 else Prefs.alertStyleFor(this, level)
        val now0 = System.currentTimeMillis()
        // לכרטיס מעל השעון במסך הראשי (מוצג עד סוף האירוע, גם אחרי סגירת המסך).
        // יישוב שמצטרף לאירוע (join) - מתווסף לקיים, באותו זמן התחלה
        // הזמנים - לפי העדכון האחרון (היישוב שהצטרף)
        if (join) {
            addActiveAreas(level, areas)
            Prefs.activeAlert(this)?.let { Prefs.setActiveAlert(this, it.put("firedAt", now0).put("shelter", shelterSec ?: -1)) }
        } else Prefs.setActiveAlert(this, org.json.JSONObject().put("title", title).put("body", body).put("level", level)
            .put("shelter", shelterSec ?: -1).put("firedAt", now0).put("source", srcName)
            .put("mapAreas", (mapAreas ?: areas).joinToString(", ")))   // למפה כשפותחים מהכרטיס
        val full = Intent(this, if (style == 1) AlertPopupActivity::class.java else AlertActivity::class.java)
            .putExtra("title", title).putExtra("body", body).putExtra("level", level)
            .putExtra("shelter", shelterSec ?: -1).putExtra("firedAt", now0)
            .putExtra("source", srcName)
            // מקדימה נסגרת אחרי 2 דקות, סיום אחרי דקה, ירי נשאר עד הסיום. בשבת - כל התרעה נסגרת אחרי 2 דקות
            .putExtra("autoCloseMs", if (shabbat) 2 * 60_000L else AlertUi.keepMs(level) ?: 0L)
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
        // בהצטרפות - בהתראה כל היישובים של האירוע
        val shown = if (join) Prefs.activeAlert(this)?.optString("body")?.takeIf { it.isNotEmpty() } ?: body else body
        val text = if (shelterSec != null && level == LEVEL_ALERT)
            "$shown · זמן למרחב מוגן: ${AreaData.shelterText(shelterSec)}" else shown
        val nb = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setLargeIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.notif_large))   // אייקון האפליקציה בצבע, בתוך ההתראה
            .setColor(0xFFD50000.toInt())   // צבע האפליקציה בהתראה
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(fullPi)
            .setAutoCancel(true)
        // סיום אירוע כשהחלון הקטן פתוח - מוצג בו (ירוק), בלי מסך מלא
        val toMini = level == LEVEL_END && MiniOverlay.isShowing()
        // כרטיס / ממוזער: גם כשהמערכת פותחת את ההתראה על מסך נעול - רק הרצועה / הכרטיס, לא מסך מלא
        val lockIntent = Intent(full).setClass(this, LockAlertActivity::class.java).putExtra("style", style)
        // מצב הטלפון לפני שמדליקים את המסך: כבוי? נעול (עם נעילת מסך)?
        val wasOff = !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        val locked = (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
        // המסך הראשי (בלי מסכים מעליו): כרטיס - בתוכו; ממוזער - החלון בתוכו, במקום הכרטיס
        val mainIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        fun openMain() {
            AlertActivity.shownFiredAt = now0   // אין מסך התרעה לחכות לו - הכרטיס / הצבע מיד
            if (style == 3) MiniOverlay.pendingMain = full
            launchOver(mainIntent)
        }
        val screenPi = when {
            locked -> if (style >= 2) PendingIntent.getActivity(this, 2, lockIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else fullPi
            wasOff || style >= 2 -> PendingIntent.getActivity(this, 3, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            else -> fullPi
        }
        if (!quiet && !toMini) nb.setFullScreenIntent(screenPi, true)
        // ספירה לאחור ישר בשורת ההתראות
        // ספירה לאחור גדולה בתוך ההתראה הקופצת
        // (בלי תצוגת טיימר מיוחדת - נראית כמו שאר ההתראות)
        val n = nb.build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ALERT, n)

        // מסך מלא גם כשהטלפון פתוח ובשימוש:
        // בבדיקה (האפליקציה בחזית) או עם הרשאת "הצגה מעל אפליקציות אחרות"
        if (!quiet) wakeScreen()
        val canShow = forceScreen || shabbat || wasOff || locked || overlayOk()
        if (join) main.post { showJoined(level, areas) }   // המסך הקיים מתעדכן - לא נפתח מסך חדש
        else if (toMini) main.post { MiniOverlay.showEnd(body, title) }
        else if (!quiet && canShow) {
            when {
                // נעול (עם נעילת מסך): כרטיס / ממוזער - בראש מסך הנעילה; מסך מלא / פופ-אפ - מעל מסך הנעילה
                locked -> launchOver(if (style >= 2) lockIntent else full)
                // טלפון בלי נעילה שהמסך שלו היה כבוי: רק האפליקציה, בלי מסכים מעליה
                wasOff -> openMain()
                // הטלפון פתוח ובשימוש: לפי הסגנון - מסך מלא / פופ-אפ, או האפליקציה עם הכרטיס / הממוזער (בלי חלון צף)
                style <= 1 -> launchOver(full)
                else -> openMain()
            }
        }

        // ספירה לאחור בווידג'ט הגדול
        // אחרי שנגמר הזמן להגעה - ההתראה עוברת לספירת השהייה (10 דקות)
        if (level == LEVEL_ALERT && !test) {
            main.removeCallbacks(stayNotif)
            if (!AlertUi.noShelter(title)) {
            stayNotifPi = fullPi; stayTitle = title; stayBody = shown; stayEnd = System.currentTimeMillis() + (shelterSec ?: 0).coerceAtLeast(0) * 1000L + Prefs.STAY_MS
            main.postDelayed(stayNotif, ((shelterSec ?: 0).coerceAtLeast(0) * 1000L) + 500)
            }
        } else if (level == LEVEL_END) main.removeCallbacks(stayNotif)
        // התראה נוספת עם ספירה לאחור: זמן התגוננות -> נשארים במרחב המוגן -> ממתינים לסיום
        if (level == LEVEL_ALERT) {
            countTitle = title; countBody = shown; countPi = fullPi; countTest = test
            countEnter = System.currentTimeMillis() + (shelterSec ?: 0).coerceAtLeast(0) * 1000L
            countStayEnd = countEnter + AlertUi.stayMs(title)
            main.removeCallbacks(countNotif); main.post(countNotif)
        } else if (level == LEVEL_END) cancelCount()

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
            Prefs.setStayUntil(this, System.currentTimeMillis() + enterMs + AlertUi.stayMs(title))
            main.postDelayed(stayDone, enterMs + AlertUi.stayMs(title))
        } else if (level == LEVEL_END) {
            Prefs.setStayUntil(this, 0)
        }

        // עקיפת מצב שקט: הטלפון על שקט/רטט - הטלפון לא ישמיע, אז האפליקציה משמיעה בעצמה.
        // כמו בצופר: מעבירים את הטלפון לרגיל לזמן ההתרעה ומשמיעים (צליל והקראה) בעוצמת ההתראות, ואז מחזירים.
        // אם אי אפשר (אין הרשאת נא לא להפריע / עוצמת ההתראות על 0) - בעוצמת השעון המעורר, כמו קודם
        // דור הצליל של ההתרעה הזו: "השתק", סגירת השירות או התרעה חדשה מעלים אותו - וצליל שעוד לא התחיל לא יתחיל
        val gen = ++soundGen
        val speakNow = !quiet && !muted && (Prefs.speakAlerts(this) || shabbat) &&
            !(test && !Prefs.testSpeak(this))   // בבדיקה - אפשר לבטל את ההקראה
        val am0 = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val inCall = am0.mode == android.media.AudioManager.MODE_IN_CALL || am0.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        val ringerNow = am0.ringerMode
        val bypass = PHONE_DEFAULTS && (shabbat || Prefs.bypassSilent(this)) && !quiet && !muted &&
            ringerNow != android.media.AudioManager.RINGER_MODE_NORMAL
        // "נא לא להפריע" פעיל: לא נוגעים בו ולא במצב הצלצול - עוקפים אותו בהשמעה בערוץ השעון המעורר
        // (נשמע גם כש"נא לא להפריע" פעיל), בלי להדליק או לכבות שום דבר בטלפון
        val dndOn = try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter !=
            NotificationManager.INTERRUPTION_FILTER_ALL } catch (_: Exception) { false }
        val viaNotif = !inCall && bypass && !dndOn && !Prefs.loudSound(this) && (snd || speakNow) &&
            ringerToNormal(if (level == LEVEL_ALERT) 30_000L else 15_000L)
        speaker?.usage = if (viaNotif) AudioAttributes.USAGE_NOTIFICATION else AudioAttributes.USAGE_ALARM

        // בשיחה (רגילה או וואטסאפ): הטלפון משתיק את צליל ההתראה - משמיעים צפצופים והקראה באוזן של השיחה.
        // אוזניות מחוברות: הצופר וההקראה יוצאים גם מהרמקול של הטלפון (אם האוזניות לא באוזן - עדיין שומעים)
        // אוזניות: רק כשהטלפון היה משמיע בכל מקרה (רגיל / עקיפת שקט / צליל חזק) - לא מעקפים מצב שקט בגלל אוזניות
        val headset = !inCall && (ringerNow == android.media.AudioManager.RINGER_MODE_NORMAL || bypass || Prefs.loudSound(this)) &&
            Prefs.speakerWithHeadset(this) && headsetOn(am0)
        speaker?.preferSpeaker = headset
        if (inCall) speaker?.usage = AudioAttributes.USAGE_VOICE_COMMUNICATION

        val playSound = playSound@{
            if (gen != soundGen) return@playSound
            if (inCall) { if (snd && !quiet) callBeeps(level); return@playSound }
            if (headset && snd && !quiet) {
                if (!muted && Prefs.loudSound(this)) boostAlarmVolume(if (level == LEVEL_ALERT) 15000L else 4000L)
                playOnSpeaker(level, if (level == LEVEL_ALERT) 15000L else 3000L,
                    if (viaNotif) AudioAttributes.USAGE_NOTIFICATION else AudioAttributes.USAGE_ALARM)
                return@playSound
            }
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
        // מוזיקה / וידאו / ניווט מדבר: נעצרים (או מונמכים) לזמן הצופר וההקראה, וחוזרים אחרי
        if (!inCall && !quiet && !muted && (snd || speakNow)) holdFocus(if (level == LEVEL_ALERT) 25_000L else 10_000L)

        if (speakNow && inCall) {
            // בשיחה: קודם הצפצופים, ואז ההקראה באוזן
            if (snd && !quiet) callBeeps(level)
            main.postDelayed({ if (gen == soundGen) speaker?.speakAlert(title, areas, shelterSec) { } }, if (snd && !quiet) 2600L else 0L)
        } else if (speakNow) {
            val started = java.util.concurrent.atomic.AtomicBoolean(false)
            val once = { if (started.compareAndSet(false, true)) main.post { playSound() } }
            speaker?.speakAlert(title, areas, shelterSec) { once() } ?: once()
            main.postDelayed({ once() }, 8000)
        } else {
            playSound()
        }

        later {
            try { if (!test && !join) Prefs.addHistory(this, entry); AlertWidget.updateAll(this) } catch (_: Exception) { }
        }
    }

    /** ההתראה בשורת ההתראות: ספירת שהייה במרחב המוגן, לחיצה פותחת את מסך הספירה */
    internal var stayNotifPi: PendingIntent? = null
    internal var stayTitle = ""; internal var stayBody = ""; internal var stayEnd = 0L
    internal val stayNotif = Runnable {
        if (System.currentTimeMillis() >= stayEnd) return@Runnable
        val n = Notification.Builder(this, CH_STAY)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setLargeIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.notif_large))   // אייקון האפליקציה בצבע, בתוך ההתראה
            .setColor(0xFFD50000.toInt())
            .setContentTitle("⏳ נשארים במרחב המוגן")
            .setContentText("$stayTitle · $stayBody")
            .setOnlyAlertOnce(true)
            .setContentIntent(stayNotifPi)
            .setAutoCancel(true)
            .setTimeoutAfter(stayEnd - System.currentTimeMillis())
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_ALERT, n.build())
    }

    /** התראת הספירה לאחור (נוספת, בלי צליל): מתעדכנת בכל מעבר שלב, נעלמת בסיום האירוע */
    internal var countTitle = ""; internal var countBody = ""; internal var countPi: PendingIntent? = null
    internal var countEnter = 0L; internal var countStayEnd = 0L; internal var countTest = false
    internal val countNotif: Runnable = Runnable {
        val now = System.currentTimeMillis()
        val (label, until) = when {
            now < countEnter -> "זמן התגוננות" to countEnter
            now < countStayEnd -> "נשארים במרחב המוגן" to countStayEnd
            else -> "ממתינים להודעת סיום אירוע" to 0L
        }
        // בדיקה: נעלמת בסוף השהייה; אמיתית - בהודעת הסיום (רשת ביטחון: שעה)
        val gone = if (countTest) countStayEnd else countEnter + 60 * 60_000L
        if (now >= gone) { cancelCount(); return@Runnable }
        val n = Notification.Builder(this, CH_COUNT)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setColor(0xFFD50000.toInt())
            .setContentTitle(label)
            .setContentText("$countTitle · $countBody")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(countPi)
            .setTimeoutAfter(gone - now)
            .setShowWhen(until > 0)
        if (until > 0) n.setWhen(until).setUsesChronometer(true).setChronometerCountDown(true)
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID_COUNT, n.build()) } catch (_: Exception) { }
        main.postDelayed(countNotif, if (until > 0) until - now + 300 else gone - now + 300)
    }
    internal fun cancelCount() {
        main.removeCallbacks(countNotif)
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_COUNT) } catch (_: Exception) { }
    }

    /** עברו 10 הדקות - רק מעדכנים תצוגה. "ניתן לצאת" רק בהודעת סיום אירוע */
    internal val stayDone = Runnable {
        Prefs.setStayUntil(this, 0)
        AlertWidget.updateAll(this)
    }

    /** נעילה אחת לשינויים בצליל (מצב צלצול / עוצמה) - ההתרעה רצה ברקע וההחזרה בתהליך הראשי */
    internal val audioLock = Any()

    /** מדליק את המסך כשהוא כבוי - שההתראה תופיע מיד גם על מסך הנעילה */
    @Suppress("DEPRECATION")
    /** צליל בערוץ "שעון מעורר" - נשמע גם במצב שקט */
    /** עוצמת ערוץ השעון המעורר למקסימום לזמן מוגבל, ואז מחזירים את העוצמה הקודמת */
    internal var savedAlarmVol = -1
    internal val restoreAlarmVol = Runnable { synchronized(audioLock) {
        try {
            if (savedAlarmVol >= 0) (getSystemService(AUDIO_SERVICE) as android.media.AudioManager)
                .setStreamVolume(android.media.AudioManager.STREAM_ALARM, savedAlarmVol, 0)
        } catch (_: Exception) { }
        savedAlarmVol = -1
        restoreSp().edit().remove("alarmVol").apply()
    } }

    /**
     * הטלפון על שקט/רטט: מעבירים לרגיל לזמן ההתרעה (כדי להשמיע בעוצמת ההתראות), ומחזירים אחרי ms.
     * מחזיר false אם אי אפשר - אז משמיעים בערוץ השעון המעורר
     */
    internal var ringerSaved = -1
    internal val restoreRinger = Runnable { synchronized(audioLock) {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        // מחזירים רק אם המשתמש לא שינה בינתיים בעצמו
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val dndBefore = try { nm.currentInterruptionFilter } catch (_: Exception) { -1 }
        if (ringerSaved != -1 && am.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL)
            try { am.ringerMode = ringerSaved } catch (_: Exception) { }
        // בחלק מהטלפונים (סמסונג) החזרת מצב הצלצול מדליקה "נא לא להפריע" - אם הוא היה כבוי, מכבים אותו מיד
        // (המערכת משנה את זה אחרי רגע - בודקים מיד ושוב אחרי שנייה וחצי)
        if (dndBefore == NotificationManager.INTERRUPTION_FILTER_ALL && nm.isNotificationPolicyAccessGranted) {
            val fix = Runnable {
                try {
                    if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                } catch (_: Exception) { }
            }
            fix.run(); main.postDelayed(fix, 1500)
        }
        ringerSaved = -1
        restoreSp().edit().remove("ringer").apply()
    } }

    /** עצירת השמעה של אפליקציות אחרות לזמן ההתרעה (Audio Focus) */
    internal var focusReq: android.media.AudioFocusRequest? = null
    internal val releaseFocus = Runnable { synchronized(audioLock) {
        focusReq?.let { try { (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).abandonAudioFocusRequest(it) } catch (_: Exception) { } }
        focusReq = null
    } }
    /** הצליל מהרמקול של הטלפון בכוח - גם כשמחוברות אוזניות */
    internal var spk: android.media.MediaPlayer? = null
    internal fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

}

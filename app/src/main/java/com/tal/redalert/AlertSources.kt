package com.tal.redalert

import com.tal.redalert.AlertService.Companion.CH_UPDATE
import com.tal.redalert.AlertService.Companion.CH_WATCH
import com.tal.redalert.AlertService.Companion.HISTORY_POLL_MS
import com.tal.redalert.AlertService.Companion.ID_UPDATE
import com.tal.redalert.AlertService.Companion.ID_WATCH
import com.tal.redalert.AlertService.Companion.POLL_MS
import com.tal.redalert.AlertService.Companion.UNPROTECTED_AFTER_MS
import com.tal.redalert.AlertService.Companion.URL_ALERTS
import com.tal.redalert.AlertService.Companion.URL_ARCHIVE
import com.tal.redalert.AlertService.Companion.URL_HISTORY
import com.tal.redalert.AlertService.Companion.decodeOref
import com.tal.redalert.AlertService.Companion.inEvent
import com.tal.redalert.AlertService.Companion.orefHistory
import com.tal.redalert.AlertService.Companion.orefLive
import com.tal.redalert.AlertService.Companion.serverOk
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import org.json.JSONObject

// שירות ההתרעות: בדיקת המקורות בטלפון (כשהשרתים לא זמינים), שומר "לא מוגן", עדכונים ומיקום

/** טלגרם: 5 שניות באירוע, 30 שניות כשהמסך דלוק, 2 דקות כשהוא כבוי */
internal fun AlertService.telegramInterval(): Long = when {
    serverOk(this) -> Long.MAX_VALUE / 4   // השרת בודק את טלגרם
    inEvent() -> 5_000L
    (getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isInteractive -> 30_000L
    else -> 300_000L   // מסך כבוי - כל 5 דקות (גיבוי בלבד)
}

internal fun AlertService.loop() {
    while (running) {
        try {
            // השרת בודק כל שנייה - הטלפון רק כגיבוי. אם השרת חסום אצל פיקוד העורף והאפשרות מופעלת בשרת - גם הטלפון בודק אותו
            if (!serverOk(this) || (Config.phoneOrefWhenServerBlocked && !SourceHealth.serverHas("oref"))) check()
        } catch (_: Exception) {
            // רשת נפלה / תשובה לא תקינה - מנסים שוב בסבב הבא
        }
        // מסך כבוי ואין אירוע - כל 10 שניות (צופר ממילא מתריע מיד); אחרת כל 2 שניות.
        // ממתינים בצעדים של 2 שניות - כשהמסך נדלק או מתחיל אירוע, חוזרים מיד לקצב המהיר
        for (step in 0 until 5) {
            Thread.sleep(POLL_MS)
            if (inEvent() || (getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isInteractive) break
        }
    }
}

internal fun AlertService.fetchOref(url: String): String {
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
    return decodeOref(bytes).trim()
}

/** מקור 1: הקובץ החי של פיקוד העורף */
internal fun AlertService.check() {
    val text = fetchOref(URL_ALERTS)
    SourceHealth.ok("oref")   // תשובה תקינה (גם ריקה = אין התראות)
    if (text.isEmpty()) return

    val json = JSONObject(text)
    val id = json.optString("id")
    if (id.isEmpty() || id == lastId) return
    lastId = id

    val (_, title, areas) = orefLive(text) ?: return
    handle(title, areas, source = "oref", eid = id)
}

/**
 * שומר: אם אין אינטרנט או שאף מקור התרעות לא מחובר יותר מ-2 דקות -
 * התראה "לא מוגן". כשהחיבור חוזר - ההתראה מתחלפת ב"חזר לפעול".
 */
internal fun AlertService.watchdogLoop() {
    var badSince = 0L
    var alerted = false
    val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
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
                    .setLargeIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.notif_large))   // אייקון האפליקציה בצבע, בתוך ההתראה
        .setColor(0xFFD50000.toInt())   // צבע האפליקציה בהתראה
                    .setContentTitle("⚠️ צופר חירום – לא מוגן")
                    .setContentText("$reason – התרעות לא יגיעו כרגע")
                    .setContentIntent(openApp())
                    .setOngoing(true)
                    .build())
            }
        } else {
            if (alerted) {
                nm.notify(ID_WATCH, Notification.Builder(this, CH_WATCH)
                    .setSmallIcon(R.drawable.ic_stat_siren)
                    .setLargeIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.notif_large))   // אייקון האפליקציה בצבע, בתוך ההתראה
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

internal fun AlertService.unprotectedReason(): String? {
    val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
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
internal fun AlertService.updateLoop() {
    Thread.sleep(60_000)
    while (running) {
        if (!serverOk(this)) checkUpdateIfDue(14 * 60 * 1000L)
        // כל 15 דקות של זמן ער - לא מעיר את הטלפון (כשהוא ישן, הספירה עוצרת),
        // והבדיקה דרך האתר של GitHub, בלי מגבלת ה-API
        Thread.sleep(15 * 60 * 1000L)
    }
}

internal fun AlertService.notifyIfNewVersion(): Boolean {
    val v = Updater.pendingVersion(this) ?: return false
    val auto = Prefs.autoUpdate(this) && packageManager.canRequestPackageInstalls()
    // התראה כשהגרסה מתפרסמת - תמיד (גם בעדכון אוטומטי), פעם אחת לכל גרסה
    val first = synchronized(updLock) {
        (Prefs.notifiedVersion(this) != v).also { if (it) Prefs.setNotifiedVersion(this, v) }
    }
    if (first) postUpdateNotice(v, auto)
    // עדכון אוטומטי ברקע - אבל לא באמצע אירוע (ההתקנה סוגרת את האפליקציה לכמה שניות): מחכים שיסתיים
    if (auto) {
        if (inEvent() || Prefs.stayUntil(this) > System.currentTimeMillis()) {
            main.removeCallbacks(updateLater); main.postDelayed(updateLater, 10 * 60 * 1000L)
            return true
        }
        Updater.autoInstall(this)
    }
    return true
}

/** התראת "גרסה חדשה": בעדכון אוטומטי - שהיא מותקנת לבד; אחרת - לחיצה להתקנה */
private fun AlertService.postUpdateNotice(v: String, auto: Boolean) {
    val n = Notification.Builder(this, CH_UPDATE)
        .setSmallIcon(R.drawable.ic_stat_siren)
        .setLargeIcon(android.graphics.drawable.Icon.createWithResource(this, R.drawable.notif_large))   // אייקון האפליקציה בצבע, בתוך ההתראה
        .setColor(0xFFD50000.toInt())   // צבע האפליקציה בהתראה
        .setContentTitle("🆕 גרסה חדשה זמינה – $v")
        .setContentText(if (auto) "מותקנת אוטומטית" else "לחץ כדי להתקין")
        .setCategory(Notification.CATEGORY_RECOMMENDATION)
        .setContentIntent(openApp())
        .setAutoCancel(true)
        .build()
    (getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_UPDATE, n)
}

/**
 * מקור 2: היסטוריית פיקוד העורף (title / alertDate)
 * מקור 3: ארכיון פיקוד העורף (category_desc / alertDate עם T)
 * שניהם גיבוי להתראות שנפלו בין בדיקות.
 */
internal fun AlertService.historyLoop() {
    while (running) {
        for (url in listOf(URL_HISTORY, URL_ARCHIVE)) {
            if (serverOk(this)) break   // השרת בודק את ההיסטוריה והארכיון
            try {
                val raw = fetchOref(url)
                // הארכיון - רק תשובה בפורמט שלו נחשבת "עונה" (לא דף אתר)
                if (url == URL_ARCHIVE && !raw.startsWith("{")) continue
                val text = OrefArchive.convert(raw)
                SourceHealth.ok(if (url == URL_HISTORY) "history" else "archive")
                if (!text.startsWith("[")) continue
                val src = if (url == URL_HISTORY) "history" else "archive"
                orefHistory(text, System.currentTimeMillis()).forEach { (title, t, areas) -> handle(title, areas, t, src) }
            } catch (_: Exception) { }
        }
        // גיבוי: מסך דלוק או אירוע - כל 30 שניות; מסך כבוי - כל 2 דקות.
        // ממתינים בצעדים של 5 שניות - כשהמסך נדלק או מתחיל אירוע, הבדיקה הבאה מגיעה מיד
        val from = System.currentTimeMillis()
        while (running) {
            Thread.sleep(5_000)
            val every = if (inEvent() || (getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isInteractive)
                HISTORY_POLL_MS else 120_000L
            if (System.currentTimeMillis() - from >= every) break
        }
    }
}

internal fun AlertService.nearLoop() {
    while (running) {
        try {
            // רק כשההתרעות לפי מיקום מופעלות - אחרת בלי מיקום ברקע בכלל
            if (Prefs.nearMe(this) && checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
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
                    // לאזור ההתרעה מספיק דיוק של 30 מטר - ה-GPS נכבה מיד כשמגיעים אליו (חוסך סוללה בנסיעה)
                    if (needGps) Weather.freshLocation(this, target = 30f) { loc ->
                        if (loc != null) { val use = Weather.chooseLocation(loc); lastGps = use; useLocation(use) }
                    }
                    else useLocation(prev!!)   // לא זזנו - המיקום המדויק הקודם עדיין נכון
                }
            }
        } catch (_: Exception) { }
        // מסך דלוק - כל 5 דקות; כבוי - כל 15 (כשהטלפון בכיס הוא בדרך כלל לא זז הרבה)
        Thread.sleep(if ((getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isInteractive) 5 * 60 * 1000L else 15 * 60 * 1000L)
    }
}

internal fun AlertService.useLocation(loc: android.location.Location) = Thread {
    try {
        if (Prefs.nearMe(this)) {
            val list = AreaData.nearby(this, loc.latitude, loc.longitude)
            if (list.isNotEmpty() && list != Prefs.nearbyAreas(this)) Prefs.setNearbyAreas(this, list)
        }
    } catch (_: Exception) { }
}.start()

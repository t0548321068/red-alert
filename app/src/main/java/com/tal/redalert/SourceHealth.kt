package com.tal.redalert

import java.util.concurrent.ConcurrentHashMap

/**
 * מצב החיבור לכל מקור התרעות.
 * כל מקור מדווח כשהצליח (קריאה תקינה / חיבור פתוח), והמסך הראשי מציג כמה מקורות מחוברים.
 */
object SourceHealth {

    /** שם לתצוגה לכל מקור, לפי הסדר */
    val NAMES = linkedMapOf(
        "oref" to "פיקוד העורף – חי",
        "history" to "פיקוד העורף – היסטוריה",
        "archive" to "פיקוד העורף – ארכיון",
        "tzofar" to "צופר (push)",
        "tg:PikudHaOref_all" to "טלגרם – פיקוד העורף",
        "tg:tzevaadomm" to "טלגרם – צופר",
        "tg:CumtaAlertsChannel" to "טלגרם – כומתה",
        "tg:Radar_Alerts" to "טלגרם – רדאר"
    )

    private val lastOk = ConcurrentHashMap<String, Long>()
    /** מקורות עם חיבור פתוח קבוע (push) - מחוברים כל עוד החיבור פתוח */
    private val open = ConcurrentHashMap<String, Boolean>()

    /** מתי ההאזנה התחילה - בחצי הדקה הראשונה מקור שעוד לא ענה הוא "מתחבר", לא "מנותק" */
    @Volatile private var startedAt = 0L
    fun reset() { startedAt = System.currentTimeMillis() }
    fun connecting(key: String) = !isUp(key) && System.currentTimeMillis() - startedAt < 30_000
    fun anyConnecting() = NAMES.keys.any { connecting(it) }

    fun ok(key: String) { lastOk[key] = System.currentTimeMillis() }
    fun setOpen(key: String, v: Boolean) { open[key] = v; if (v) ok(key) }

    /** מקור נחשב מחובר אם יש חיבור פתוח, או שהצליח בדקה וחצי האחרונות */
    fun isUp(key: String): Boolean =
        open[key] == true || System.currentTimeMillis() - (lastOk[key] ?: 0L) < 90_000

    /** מתי התקבלה תשובה אחרונה מכל מקור שהוא */
    /** מתי התקבל מידע בפועל מאחד המקורות (בלי לספור חיבור פתוח כ"עכשיו") */
    fun lastData(): Long = lastOk.values.maxOrNull() ?: 0L
    fun lastAny(): Long = (NAMES.keys.map { k -> if (open[k] == true) System.currentTimeMillis() else lastOk[k] ?: 0L }.maxOrNull() ?: 0L)

    fun name(key: String) = NAMES[key] ?: key

    fun upCount() = NAMES.keys.count { isUp(it) }
    fun total() = NAMES.size
}

package com.tal.redalert

/**
 * מניעת כפילויות: אותה התרעה מגיעה מכמה מקורות (השרת שולח פעמיים, פיקוד העורף, צופר, טלגרם) -
 * מצלצלים פעם אחת לכל יישוב. מטח חדש (מזהה אחר של פיקוד העורף, או אחרי 2 דקות בירי) - כן מצלצל.
 * בלי טלפון - נבדק בבדיקות האוטומטיות. לא מסונכרן בעצמו (handle() של השירות כבר מסונכרן)
 */
class AlertDedup {
    companion object {
        const val DEDUP_MS = Prefs.DUP_MS
        const val ALERT_DUP_MS = 2 * 60 * 1000L

        /** מפתח ליישוב - אותו יישוב בכל המקורות ("תל אביב - מרכז העיר" = "תל אביב" = "תל-אביב") */
        fun key(area: String, level: Int) = Prefs.areaKey(area) + "|" + level
    }

    /** "יישוב|סוג" -> זמן האירוע */
    private val seen = HashMap<String, Long>()
    private val seenFeed = HashMap<String, Long>()
    /** מזהה האירוע של פיקוד העורף לכל יישוב - להבחין בין כפילות למטח חדש */
    private val seenEid = HashMap<String, String>()

    /** פיד ארצי: היישובים שעוד לא נרשמו (כל ההתראות בארץ, לפני הסינון לפי אזורים) */
    fun feedNew(areas: List<String>, level: Int, eventTime: Long): List<String> {
        val out = areas.filter { a ->
            val prev = seenFeed[key(a, level)]
            prev == null || eventTime - prev > DEDUP_MS
        }
        out.forEach { seenFeed[key(it, level)] = eventTime }
        return out
    }

    /** היישובים שלי שצריכים לצלצל עכשיו (ומסמן אותם) */
    fun fresh(mine: List<String>, level: Int, eventTime: Long, eid: String): List<String> {
        // ירי: אותו יישוב שוב אחרי יותר מ-2 דקות = מטח חדש (כמו בשרת). בשאר הסוגים - 5 דקות
        val dup = if (level == AlertService.LEVEL_ALERT) ALERT_DUP_MS else DEDUP_MS
        val out = mine.filter { a ->
            val k = key(a, level)
            val prev = seen[k]
            if (prev == null || eventTime - prev > dup) return@filter true
            // אותו יישוב בתוך החלון: מטח חדש רק אם זה אירוע אחר של פיקוד העורף (מזהה שונה).
            // כבר התריע ממקור בלי מזהה (צופר / טלגרם) - זה אותו אירוע; זוכרים את המזהה שלו למטח הבא
            if (eid.isEmpty()) return@filter false
            val pe = seenEid[k] ?: run { seenEid[k] = eid; return@filter false }
            pe != eid
        }
        // מזהה ישן לא נשאר למטח הבא
        out.forEach { val k = key(it, level); seen[k] = eventTime; if (eid.isNotEmpty()) seenEid[k] = eid else seenEid.remove(k) }
        return out
    }
}

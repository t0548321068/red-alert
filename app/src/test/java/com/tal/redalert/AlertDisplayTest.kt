package com.tal.redalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * בדיקות אוטומטיות לתצוגת ההתרעה: סגנון לכל נושא, יישוב שמצטרף לאירוע,
 * מתי מוצג הכרטיס במסך הראשי (והצבע), ומה קורה בסגירת מסך מלא / פופ-אפ
 */
class AlertDisplayTest {
    private val A = AlertService.LEVEL_ALERT
    private val PRE = AlertService.LEVEL_PRE
    private val END = AlertService.LEVEL_END
    private val FULL = 0; private val POPUP = 1; private val CARD = 2; private val MINI = 3
    private val t0 = 1_762_437_600_000L
    private val sec = 1000L
    private val min = 60_000L

    // ---- סגנון התרעה: כללי / התאמה אישית ----
    @Test fun styleGeneralWhenCustomOff() {
        assertEquals(FULL, Prefs.resolveStyle(custom = false, topic = CARD, general = FULL))   // ההתאמה כבויה - הבחירה נשמרת אבל לא חלה
        assertEquals(MINI, Prefs.resolveStyle(custom = false, topic = -1, general = MINI))
    }

    @Test fun styleTopicWhenCustomOn() {
        assertEquals(CARD, Prefs.resolveStyle(custom = true, topic = CARD, general = FULL))
        assertEquals(POPUP, Prefs.resolveStyle(custom = true, topic = -1, general = POPUP))    // נושא שלא נבחר לו - הכללי
        assertEquals(FULL, Prefs.resolveStyle(custom = true, topic = 9, general = FULL))       // ערך לא תקין - הכללי
    }

    // ---- יישוב שמצטרף לאירוע (זרעית ואז שומרה) ----
    @Test fun joinsSameEventSeconds() {
        assertTrue(AlertService.joins(A, t0, A, newBarrage = false, sameRegion = true, now = t0 + 2 * sec))
        assertTrue(AlertService.joins(PRE, t0, PRE, newBarrage = false, sameRegion = true, now = t0 + 80 * sec))
    }

    @Test fun newEventNotJoined() {
        assertFalse(AlertService.joins(A, t0, PRE, false, true, t0 + 2 * sec))          // סוג אחר
        assertFalse(AlertService.joins(A, t0, A, true, true, t0 + 2 * sec))             // מטח חדש (מזהה אחר של פיקוד העורף)
        assertFalse(AlertService.joins(A, t0, A, false, false, t0 + 2 * sec))           // אזור אחר בארץ
        assertFalse(AlertService.joins(A, t0, A, false, true, t0 + 2 * min))            // אחרי דקה וחצי - אירוע חדש
    }

    @Test fun endJoinsLonger() {
        // "האירוע הסתיים" מגיע בכמה חלקים - מצטרף עד 5 דקות
        assertTrue(AlertService.joins(END, t0, END, false, true, t0 + 4 * min))
        assertFalse(AlertService.joins(END, t0, END, false, true, t0 + 6 * min))
    }

    // ---- הכרטיס במסך הראשי: (כרטיס, צבע) ----
    private fun card(active: Boolean = true, mini: Boolean = false, open: Int = 0, shown: Boolean = false,
                     cardStyle: Boolean = false, preview: Boolean = false, age: Long = 10 * sec) =
        AlertUi.cardView(true, active, mini, open, shown, cardStyle, preview, age)

    @Test fun cardStyleShowsAtOnce() {
        assertEquals(true to true, card(cardStyle = true, age = 0))
    }

    @Test fun fullScreenOpeningHidesEverything() {
        // מסך מלא שעוד לא עלה - בלי כרטיס ובלי צבע (לא רואים את המסך הראשי לפני ההתרעה)
        assertEquals(false to false, card(age = 1 * sec))
    }

    @Test fun fullScreenOpenTintsOnly() {
        assertEquals(false to true, card(open = 1, age = 1 * sec))   // מאחורי פופ-אפ - צבוע, בלי כרטיס
    }

    @Test fun afterCloseCardAtOnce() {
        // המסך כבר הוצג ונסגר - כרטיס וצבע מיד, גם בשניות הראשונות
        assertEquals(true to true, card(shown = true, age = 2 * sec))
        assertEquals(true to true, card(age = 5 * sec))   // לא עלה בכלל (למשל בלי הרשאה) - אחרי 4 שניות מופיע
    }

    @Test fun miniOpenTintsOnly() {
        assertEquals(false to true, card(mini = true, age = 1 * sec))
    }

    @Test fun dismissedOrEndedNothing() {
        assertEquals(false to false, card(active = false))
    }

    @Test fun previewShowsAtOnce() {
        assertEquals(true to true, card(preview = true, age = 0))
    }

    // ---- סגירת מסך מלא / פופ-אפ ----
    @Test fun closeToMiniOnlyWhenChosen() {
        assertTrue(AlertUi.closeToMini(afterClose = 1, canShow = true, preview = false, expired = false))
        assertFalse(AlertUi.closeToMini(0, true, false, false))    // אחרי סגירה: כרטיס
        assertFalse(AlertUi.closeToMini(1, false, false, false))   // בלי הרשאה - כרטיס
        assertFalse(AlertUi.closeToMini(1, true, true, false))     // תצוגה מקדימה - חוזרים להגדרות
        assertFalse(AlertUi.closeToMini(1, true, false, true))     // ההתרעה כבר נגמרה
    }

    // ---- רעידת אדמה: בלי זמן התגוננות ובלי "נשארים במרחב המוגן" ----
    @Test fun quakeHasNoShelterStages() {
        val q = "רעידת אדמה"
        assertEquals("צאו לשטח פתוח", AlertUi.head(q, AlertService.LEVEL_ALERT).second)
        assertEquals("" to "ממתינים להודעת סיום אירוע", AlertUi.timer(AlertService.LEVEL_ALERT, -1, 1000L, 1000L, q))
        assertEquals("התרחקו מחוף הים", AlertUi.head("חשש לצונאמי", AlertService.LEVEL_ALERT).second)
        assertEquals("" to "ממתינים להודעת סיום אירוע", AlertUi.timer(AlertService.LEVEL_ALERT, -1, 1000L, 1000L, "חשש לצונאמי"))
        assertEquals("היכנסו למרחב המוגן", AlertUi.head("ירי רקטות וטילים", AlertService.LEVEL_ALERT).second)
        assertEquals("נשארים במרחב המוגן", AlertUi.timer(AlertService.LEVEL_ALERT, 0, 1000L, 1000L, "ירי רקטות וטילים")?.first)
    }

    // ---- עדכון "הנחיות חזרה למבנים": ירוק, עם הנוסח המלא (לפי ההגדרות שבאפליקציה) ----
    @Test fun returnToBuildingsIsUpdate() {
        Config.load(org.json.JSONObject(java.io.File("src/main/assets/config.json").readText()))
        try {
            val t = "רעידת אדמה - הנחיות חזרה למבנים"
            assertEquals(AlertService.LEVEL_END, AlertService.levelOf(t))
            assertEquals(t, AlertService.displayTitle(t))
            assertEquals("עדכון פיקוד העורף" to t, AlertUi.head(t, AlertService.LEVEL_END))
            assertEquals("ניתן לחזור למבנים", Config.guidance(t).first())
            assertEquals("האירוע הסתיים", AlertService.displayTitle("החשש הוסר"))
            assertEquals("עדכון פיקוד העורף" to "האירוע הסתיים", AlertUi.head("האירוע הסתיים", AlertService.LEVEL_END))
        } finally { Config.reset() }
    }

    // ---- שמות יישובים: וריאציות כתיב הן אותו יישוב, אבל "אילת" ו"אילות" שניים ----
    @Test fun areaKeysKeepDistinctTowns() {
        assertEquals(Prefs.areaKey("קרית שמונה"), Prefs.areaKey("קריית שמונה"))
        assertEquals(Prefs.areaKey("תל-אביב"), Prefs.areaKey("תל אביב - מרכז"))
        assertTrue(Prefs.areaKey("אילת") != Prefs.areaKey("אילות"))
        assertTrue(Prefs.nameKey("ירושלים - צפון") != Prefs.nameKey("ירושלים - מרכז"))
    }

    // ---- "הכול": סדר היישובים כמו בהתרעה שהגיעה, ויישובים שהגיעו רק בזמן אמת מצטרפים ----
    @Test fun allListFollowsArrivalOrder() {
        val t = 1_000_000_000L
        val all = listOf(Prefs.Entry("", "רעידת אדמה", "בחלק מהאזורים בארץ", 0, t))
        val mine = listOf(Prefs.Entry("", "רעידת אדמה", "בחלק מהאזורים בארץ, באר אורה, אילת, אילות", 0, t + 4000))
        assertEquals("בחלק מהאזורים בארץ, באר אורה, אילת, אילות", Prefs.withMine(all, mine)[0].body)   // מה שחסר בשרת - נוסף
        val all2 = listOf(Prefs.Entry("", "עדכון", "אילות, אילת, באר אורה", 2, t))
        val mine2 = listOf(Prefs.Entry("", "עדכון", "אילת, באר אורה", 2, t))
        assertEquals("אילות, אילת, באר אורה", Prefs.withMine(all2, mine2)[0].body)   // הסדר - של השרת
    }

    // ---- מסך הפירוט: קבוצה לכל שעת הגעה, ובתוכה לפי אזור ----
    @Test fun sheetGroupsByTimeAndDistrict() {
        val t = 1_794_389_135_000L
        val areas = listOf("בחלק מהאזורים בארץ", "באר אורה", "אליפז ומכרות תמנע", "אילת", "אילות", "אזור תעשייה שחורת")
        val times = mapOf("בחלק מהאזורים בארץ" to t) + areas.drop(1).associateWith { t + 2000 }
        val d = mapOf("באר אורה" to "ערבה", "אליפז ומכרות תמנע" to "ערבה", "אילת" to "אילת", "אילות" to "אילת", "אזור תעשייה שחורת" to "אילת")
        val g = AlertSheet.groups(areas, times, t, { d[it] })
        assertEquals(2, g.size)
        assertEquals(listOf("בחלק מהאזורים בארץ" to emptyList<String>()), g[0].districts)
        assertEquals(listOf("ערבה" to listOf("באר אורה", "אליפז ומכרות תמנע"), "אילת" to listOf("אילת", "אילות", "אזור תעשייה שחורת")), g[1].districts)
        assertEquals("בחלק מהאזורים בארץ, ערבה, אילת", AlertSheet.summary(g))
        assertEquals(1, AlertSheet.groups(listOf("אילת", "אילות"), emptyMap(), t, { d[it] }).size)   // בלי זמנים - כרטיס אחד
    }

    @Test fun collapseKeepsAreaTimes() {
        val t = 1_000_000_000L
        val a = Prefs.Entry("", "רעידת אדמה", "אילת", 0, t + 2000)
        val b = Prefs.Entry("", "רעידת אדמה", "בחלק מהאזורים בארץ", 0, t)
        val m = Prefs.collapse(listOf(a, b))
        assertEquals(1, m.size)
        assertEquals(t + 2000, Prefs.areaTimes(m[0])["אילת"])
        assertEquals(t, Prefs.areaTimes(m[0])["בחלק מהאזורים בארץ"])
    }

    // ---- התראה מהפיד הארצי: רק לפי ההגדרה ----
    @Test fun feedNotifyOnlyBySetting() {
        assertTrue(AlertService.feedNotifies(true))
        assertFalse(AlertService.feedNotifies(false))
    }

    // ---- "שלי" לפי השרת: אותם יישובים, סדר ושעות - רק היישובים שלי ----
    @Test fun mineFollowsServer() {
        val t = 1_794_389_133_000L
        val srvAreas = listOf("אילת", "אזור תעשייה שחורת", "אילות", "אליפז ומכרות תמנע", "באר אורה", "בחלק מהאזורים בארץ")
        val all = listOf(Prefs.Entry("", "רעידת אדמה", srvAreas.joinToString(", "), 0, t,
            times = srvAreas.associateWith { if (it == "בחלק מהאזורים בארץ") t + 2000 else t + 4000 }))
        val mine = listOf(Prefs.Entry("", "רעידת אדמה", "באר אורה, אליפז ומכרות תמנע, אילת, אזור תעשייה שחורת", 0, t + 5000))
        val r = Prefs.mineFromServer(mine, all) { it != "בחלק מהאזורים בארץ" }[0]
        assertEquals("אילת, אזור תעשייה שחורת, אילות, אליפז ומכרות תמנע, באר אורה", r.body)
        assertEquals(1, AlertSheet.groups(r.body.split(", "), r.times, r.ts, { null }).size)   // כרטיס אחד: 11:25:37
        assertEquals(t + 4000, r.times["אילות"])
        assertEquals(t, r.ts)   // השעה של השורה - של השרת
        // בלי שורה בשרת - כמו שנשמר בטלפון
        assertEquals(mine[0], Prefs.mineFromServer(mine, emptyList()) { true }[0])
    }

    // ---- השעה בשורה: של היישוב הראשון שהגיע (לא "בחלק מהאזורים בארץ") ----
    @Test fun rowTimeIsFirstTown() {
        val t = 1_794_389_133_000L
        val e = Prefs.Entry("", "רעידת אדמה", "אילת, אילות, בחלק מהאזורים בארץ", 0, t,
            times = mapOf("אילת" to t + 4000, "אילות" to t + 4000, "בחלק מהאזורים בארץ" to t + 2000))
        assertEquals(t + 4000, Prefs.rowTime(e) { it != "בחלק מהאזורים בארץ" })
        assertEquals(t, Prefs.rowTime(Prefs.Entry("", "x", "בחלק מהאזורים בארץ", 0, t)) { false })   // אין יישובים - השעה של השורה
    }
}

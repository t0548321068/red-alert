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
        assertEquals("היכנסו למרחב המוגן", AlertUi.head("ירי רקטות וטילים", AlertService.LEVEL_ALERT).second)
        assertEquals("נשארים במרחב המוגן", AlertUi.timer(AlertService.LEVEL_ALERT, 0, 1000L, 1000L, "ירי רקטות וטילים")?.first)
    }
}

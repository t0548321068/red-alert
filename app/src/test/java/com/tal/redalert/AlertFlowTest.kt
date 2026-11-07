package com.tal.redalert

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * בדיקות אוטומטיות ללב של שירות ההתרעות:
 * האם ההתרעה שלי (הבחירות שלי והמיקום שלי), ומניעת צלצול כפול כשאותה התרעה מגיעה מכמה מקורות
 */
class AlertFlowTest {
    private val A = AlertService.LEVEL_ALERT
    private val PRE = AlertService.LEVEL_PRE
    private val END = AlertService.LEVEL_END
    private val t0 = 1_762_437_600_000L
    private val sec = 1000L
    private val min = 60_000L

    private val districts = JSONObject(File("src/main/assets/districts.json").readText(Charsets.UTF_8))
    private val match: (String, String) -> Boolean = { a, f -> AreaData.matchesPick(a, f) { districts.optString(it, "").ifEmpty { null } } }

    // ---- האם ההתרעה שלי ----
    @Test fun mineByPicks() {
        val got = AlertService.mineOf(listOf("ירושלים - צפון", "שדרות, איבים", "גן יבנה"), listOf("ירושלים", "יבנה"), emptyList(), match)
        assertEquals(listOf("ירושלים - צפון"), got)   // "יבנה" לא תופס את "גן יבנה"
    }

    @Test fun mineByDistrictAndCountry() {
        assertEquals(listOf("שדרות, איבים"),
            AlertService.mineOf(listOf("ירושלים - צפון", "שדרות, איבים"), listOf("מחוז עוטף עזה"), emptyList(), match))
        assertEquals(listOf("כל הארץ"), AlertService.mineOf(listOf("כל הארץ"), listOf("כל הארץ"), emptyList(), match))
        // בלי שום בחירה - אף התרעה לא שלי (אף פעם לא כל הארץ כברירת מחדל)
        assertTrue(AlertService.mineOf(listOf("ירושלים - צפון", "כל הארץ"), emptyList(), emptyList(), match).isEmpty())
    }

    @Test fun mineByLocation() {
        // המיקום שלי: האזור המדויק, או שם העיר כשהמקור שולח בלי החלק
        assertEquals(listOf("ירושלים - צפון"), AlertService.mineOf(listOf("ירושלים - צפון", "ירושלים - דרום"), emptyList(), listOf("ירושלים - צפון"), match))
        assertEquals(listOf("ירושלים"), AlertService.mineOf(listOf("ירושלים", "נירים"), emptyList(), listOf("ירושלים - צפון"), match))
    }

    // ---- כפילויות ----
    @Test fun sameAlertFromManySourcesRingsOnce() {
        val d = AlertDedup()
        assertEquals(listOf("נירים"), d.fresh(listOf("נירים"), A, t0, "133"))          // השרת
        assertTrue(d.fresh(listOf("נירים"), A, t0 + 2 * sec, "133").isEmpty())          // השרת שוב (נשלח פעמיים)
        assertTrue(d.fresh(listOf("נירים"), A, t0 + 3 * sec, "").isEmpty())             // צופר / טלגרם בלי מזהה
    }

    @Test fun differentSpellingSameArea() {
        val d = AlertDedup()
        assertEquals(listOf("תל אביב - מרכז העיר"), d.fresh(listOf("תל אביב - מרכז העיר"), A, t0, ""))
        assertTrue(d.fresh(listOf("תל-אביב"), A, t0 + sec, "").isEmpty())
    }

    @Test fun newBarrageByNewId() {
        val d = AlertDedup()
        d.fresh(listOf("נירים"), A, t0, "133")
        // תוך דקה, מזהה אחר של פיקוד העורף = מטח חדש
        assertEquals(listOf("נירים"), d.fresh(listOf("נירים"), A, t0 + 40 * sec, "134"))
    }

    @Test fun newBarrageAfterTwoMinutes() {
        val d = AlertDedup()
        d.fresh(listOf("נירים"), A, t0, "")
        assertTrue(d.fresh(listOf("נירים"), A, t0 + 90 * sec, "").isEmpty())
        assertEquals(listOf("נירים"), d.fresh(listOf("נירים"), A, t0 + 2 * min + sec, ""))
    }

    @Test fun firstWithoutIdThenWithIdIsSameEvent() {
        val d = AlertDedup()
        d.fresh(listOf("נירים"), A, t0, "")                                            // צופר היה הכי מהיר
        assertTrue(d.fresh(listOf("נירים"), A, t0 + 5 * sec, "133").isEmpty())          // פיקוד העורף - אותו אירוע
        assertEquals(listOf("נירים"), d.fresh(listOf("נירים"), A, t0 + 50 * sec, "134")) // מטח הבא
    }

    @Test fun otherTypesFiveMinutes() {
        val d = AlertDedup()
        assertEquals(listOf("נירים"), d.fresh(listOf("נירים"), PRE, t0, ""))
        assertTrue(d.fresh(listOf("נירים"), PRE, t0 + 4 * min, "").isEmpty())
        assertEquals(listOf("נירים"), d.fresh(listOf("נירים"), PRE, t0 + 5 * min + sec, ""))
        // סוג אחר לאותו יישוב - לא כפילות
        assertEquals(listOf("נירים"), d.fresh(listOf("נירים"), END, t0 + sec, ""))
    }

    @Test fun onlyNewAreasInMixedMessage() {
        val d = AlertDedup()
        d.fresh(listOf("נירים"), A, t0, "")
        assertEquals(listOf("כיסופים"), d.fresh(listOf("נירים", "כיסופים"), A, t0 + 10 * sec, ""))
    }

    @Test fun feedOncePerArea() {
        val d = AlertDedup()
        assertEquals(listOf("נירים", "כיסופים"), d.feedNew(listOf("נירים", "כיסופים"), A, t0))
        assertEquals(listOf("עין השלושה"), d.feedNew(listOf("נירים", "עין השלושה"), A, t0 + 10 * sec))
        assertEquals(listOf("נירים"), d.feedNew(listOf("נירים"), A, t0 + 5 * min + sec))
    }
}

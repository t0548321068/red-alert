package com.tal.redalert

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * בדיקות אוטומטיות - רצות בכל בנייה, לפני שגרסה נוצרת.
 * אם שינוי שובר את זיהוי ההתרעות (סוג, כותרת, יישובים, אזורים) - הבנייה נכשלת ולא מגיעה לטלפון
 */
class AlertParsingTest {
    companion object {
        private lateinit var areas: JSONObject
        private lateinit var districts: JSONObject

        private fun asset(name: String) = File("src/main/assets/$name").readText(Charsets.UTF_8)

        @BeforeClass @JvmStatic fun setup() {
            // אותן הגדרות ואותם קבצים שבתוך האפליקציה
            Config.load(JSONObject(asset("config.json")))
            areas = JSONObject(asset("areas.js").removePrefix("var ALL_AREAS=").trimEnd().removeSuffix(";"))
            districts = JSONObject(asset("districts.json"))
        }
    }

    // ---- סוג ההתרעה ----
    @Test fun alertLevels() {
        assertEquals(AlertService.LEVEL_ALERT, AlertService.levelOf("ירי רקטות וטילים"))
        assertEquals(AlertService.LEVEL_ALERT, AlertService.levelOf("חדירת כלי טיס עוין"))
        assertEquals(AlertService.LEVEL_ALERT, AlertService.levelOf("חדירת מחבלים"))
        assertEquals(AlertService.LEVEL_ALERT, AlertService.levelOf("רעידת אדמה"))
    }

    @Test fun preAlertLevels() {
        assertEquals(AlertService.LEVEL_PRE, AlertService.levelOf("בדקות הקרובות צפויות להתקבל התרעות באזורך"))
        assertEquals(AlertService.LEVEL_PRE, AlertService.levelOf("איום מלבנון"))
        assertEquals(AlertService.LEVEL_PRE, AlertService.levelOf("התרעה מקדימה"))
        assertEquals(AlertService.LEVEL_PRE, AlertService.levelOf("יש להתקרב למרחב המוגן - התקרבו"))
    }

    @Test fun endLevels() {
        assertEquals(AlertService.LEVEL_END, AlertService.levelOf("האירוע הסתיים"))
        assertEquals(AlertService.LEVEL_END, AlertService.levelOf("החשש הוסר"))
        assertEquals(AlertService.LEVEL_END, AlertService.levelOf("ניתן לצאת מהמרחב המוגן אך יש להישאר בקרבתו"))
    }

    // ---- הכותרת שמוצגת ----
    @Test fun displayTitles() {
        assertEquals("התרעה מקדימה - איום מלבנון", AlertService.displayTitle("איום מלבנון"))
        // כבר עם הקידומת - לא מוסיפים פעמיים
        assertEquals("התרעה מקדימה - איום מלבנון", AlertService.displayTitle("התרעה מקדימה - איום מלבנון"))
        assertEquals("האירוע הסתיים", AlertService.displayTitle("ניתן לצאת מהמרחב המוגן"))
        assertEquals("האירוע הסתיים", AlertService.displayTitle("החשש הוסר"))
        assertEquals("ירי רקטות וטילים", AlertService.displayTitle("  ירי רקטות וטילים "))
    }

    @Test fun guidanceForEveryType() {
        for (t in listOf("ירי רקטות וטילים", "האירוע הסתיים", "בדקות הקרובות צפויות להתקבל התרעות באזורך", "חדירת מחבלים", "רעידת אדמה"))
            assertTrue("אין הנחיות ל-$t", Config.guidance(t).isNotEmpty())
    }

    // ---- "כל הארץ" ----
    @Test fun wholeCountry() {
        assertEquals("כל הארץ", AreaData.unify("ברחבי הארץ"))
        assertEquals("שדרות, איבים", AreaData.unify("שדרות, איבים"))
        assertTrue(AreaData.isNational("ברחבי הארץ"))
        assertTrue(AreaData.isNational("כל הארץ"))
        assertFalse(AreaData.isNational("ירושלים - צפון"))
        assertTrue(AreaData.matchesPick("ברחבי הארץ", "כל הארץ") { null })
        assertTrue(AreaData.matchesPick("כל הארץ", "כל הארץ") { null })
        assertFalse(AreaData.matchesPick("ירושלים - צפון", "כל הארץ") { null })
    }

    // ---- התאמת יישוב לבחירה שלי ----
    @Test fun areaPicks() {
        val districtOf: (String) -> String? = { districts.optString(it, "").ifEmpty { null } }
        assertTrue(AreaData.matchesPick("ירושלים - צפון", "ירושלים - צפון", districtOf))
        assertTrue(AreaData.matchesPick("ירושלים - צפון", "ירושלים", districtOf))
        // "יבנה" לא תופס את "גן יבנה"
        assertFalse(AreaData.matchesPick("גן יבנה", "יבנה", districtOf))
        assertTrue(AreaData.matchesPick("שדרות, איבים", "מחוז עוטף עזה", districtOf))
        assertFalse(AreaData.matchesPick("ירושלים - צפון", "מחוז עוטף עזה", districtOf))
        assertTrue(AreaData.matchesPick("ירושלים - צפון", "מחוז כל הארץ", districtOf))
    }

    // ---- שם מהמקור -> השם המלא במפה ----
    @Test fun areaNames() {
        assertEquals(listOf("שדרות, איבים"), AreaData.matchIn(areas, "שדרות"))
        assertEquals(listOf("גן יבנה"), AreaData.matchIn(areas, "גן יבנה"))
        assertEquals(listOf("יבנה"), AreaData.matchIn(areas, "יבנה"))
        assertTrue(AreaData.matchIn(areas, "ירושלים").contains("ירושלים - צפון"))
        assertTrue(AreaData.matchIn(areas, "ירושלים").all { it.startsWith("ירושלים -") })
        assertEquals(emptyList<String>(), AreaData.matchIn(areas, "מקום שלא קיים"))
    }

    // ---- הודעות טלגרם ----
    @Test fun telegramPikudHaOref() {
        val (title, list) = TelegramSource.parseText(
            "🚨 ירי רקטות וטילים (6/11/2026) 14:02\nאזור עוטף עזה\nשדרות, איבים, ניר עם (15 שניות)\nהיכנסו למרחב המוגן ושהו בו 10 דקות")!!
        assertEquals("ירי רקטות וטילים", title)
        assertEquals(listOf("שדרות", "איבים", "ניר עם"), list)
    }

    @Test fun telegramTzofar() {
        val (title, list) = TelegramSource.parseText("צבע אדום\n05:23: • עוטף עזה: כיסופים (15 שניות)")!!
        assertEquals("ירי רקטות וטילים", title)
        assertEquals(listOf("כיסופים"), list)
    }

    @Test fun telegramEnded() {
        val (title, list) = TelegramSource.parseText("האירוע הסתיים בכפר יובל, דפנה")!!
        assertEquals("האירוע הסתיים", title)
        assertEquals(listOf("כפר יובל", "דפנה"), list)
    }

    @Test fun telegramCumta() {
        val (title, list) = TelegramSource.parseText("🔴 צבע אדום\n• עוטף עזה 231 - נירים, עין השלושה")!!
        assertEquals("ירי רקטות וטילים", title)
        assertEquals(listOf("נירים", "עין השלושה"), list)
    }

    @Test fun telegramRadar() {
        val (title, list) = TelegramSource.parseText("רדאר 📡\nירי רקטות\n• קו העימות\nשלומי, מטולה")!!
        assertEquals("ירי רקטות וטילים", title)
        assertEquals(listOf("שלומי", "מטולה"), list)
    }

    @Test fun telegramAircraftAndPre() {
        assertEquals("חדירת כלי טיס עוין", TelegramSource.parseText("חדירת כלי טיס עוין\nקריית שמונה")!!.first)
        assertEquals("בדקות הקרובות צפויות להתקבל התרעות באזורך",
            TelegramSource.parseText("בדקות הקרובות צפויות להתקבל התרעות באזורך\nשדרות, איבים")!!.first)
    }

    @Test fun telegramNoAreas() {
        assertNull(TelegramSource.parseText(""))
        assertNull(TelegramSource.parseText("צבע אדום"))
    }

    // ---- הודעות צופר ----
    private fun tz(type: String, data: JSONObject) = JSONObject().put("type", type).put("data", data)

    @Test fun tzofarAlert() {
        val (title, list) = TzofarSource.messageOf(tz("ALERT", JSONObject().put("threat", 0)
            .put("cities", JSONArray().put("שדרות").put("איבים")))) { null }!!
        assertEquals(AlertService.LEVEL_ALERT, AlertService.levelOf(title))
        assertEquals(listOf("שדרות", "איבים"), list)
    }

    @Test fun tzofarDrillIgnored() {
        assertNull(TzofarSource.messageOf(tz("ALERT", JSONObject().put("threat", 0).put("isDrill", true)
            .put("cities", JSONArray().put("שדרות")))) { null })
    }

    @Test fun tzofarSystemMessages() {
        val cities = mapOf(1 to "שדרות, איבים", 2 to "נירים")
        val pre = TzofarSource.messageOf(tz("SYSTEM_MESSAGE", JSONObject().put("instructionType", 0)
            .put("citiesIds", JSONArray().put(1).put(2)))) { cities[it] }!!
        assertEquals(AlertService.LEVEL_PRE, AlertService.levelOf(pre.first))
        assertEquals(listOf("שדרות, איבים", "נירים"), pre.second)
        val end = TzofarSource.messageOf(tz("SYSTEM_MESSAGE", JSONObject().put("instructionType", 1)
            .put("citiesIds", JSONArray().put(2)))) { cities[it] }!!
        assertEquals(AlertService.LEVEL_END, AlertService.levelOf(end.first))
        // סוג הודעה לא מוכר - לא התרעה
        assertNull(TzofarSource.messageOf(tz("SYSTEM_MESSAGE", JSONObject().put("instructionType", 7)
            .put("citiesIds", JSONArray().put(2)))) { cities[it] })
    }
}

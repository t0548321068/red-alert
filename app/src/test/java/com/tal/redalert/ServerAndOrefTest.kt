package com.tal.redalert

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * בדיקות אוטומטיות לדרך העיקרית שבה ההתרעות מגיעות (הודעות מהשרת דרך Firebase),
 * ולפיקוד העורף ישירות (כשהשרתים נופלים והטלפון בודק בעצמו)
 */
class ServerAndOrefTest {
    companion object {
        @BeforeClass @JvmStatic fun setup() {
            Config.load(JSONObject(File("src/main/assets/config.json").readText(Charsets.UTF_8)))
        }
        private val known = setOf("שדרות, איבים", "נירים", "כיסופים")
    }

    // ---- הודעה מהשרת ----
    @Test fun serverAlert() {
        val a = PushService.pushAlertOf(mapOf("type" to "alert", "title" to "ירי רקטות וטילים",
            "areas" to "שדרות, איבים|נירים", "at" to "1762437600000", "src" to "oref", "eid" to "133")) { false }!!
        assertEquals("ירי רקטות וטילים", a.title)
        assertEquals(listOf("שדרות, איבים", "נירים"), a.areas)
        assertEquals(1762437600000L, a.at)
        assertEquals("oref", a.src)
        assertEquals("133", a.eid)
        assertEquals(AlertService.LEVEL_ALERT, AlertService.levelOf(a.title))
    }

    @Test fun serverAlertTrimsAndSkipsEmpty() {
        val a = PushService.pushAlertOf(mapOf("type" to "alert", "title" to "האירוע הסתיים",
            "areas" to " שדרות, איבים || נירים |")) { false }!!
        assertEquals(listOf("שדרות, איבים", "נירים"), a.areas)
        assertNull(a.at)   // בלי זמן - עכשיו
        assertEquals(AlertService.LEVEL_END, AlertService.levelOf(a.title))
    }

    @Test fun serverTelegramOnlyRealPlaces() {
        // טלגרם: שורות שאינן יישובים (כותרת הערוץ, תאריך) מסוננות
        val a = PushService.pushAlertOf(mapOf("type" to "alert", "title" to "ירי רקטות וטילים",
            "areas" to "צופר - צבע אדום|כיסופים|06/11/2026", "src" to "tg:tzevaadomm")) { it in known }!!
        assertEquals(listOf("כיסופים"), a.areas)
        // בלי אף יישוב אמיתי - לא התרעה
        assertNull(PushService.pushAlertOf(mapOf("type" to "alert", "title" to "ירי רקטות וטילים",
            "areas" to "צופר - צבע אדום", "src" to "tg:tzevaadomm")) { it in known })
    }

    @Test fun serverNotAlert() {
        assertNull(PushService.pushAlertOf(mapOf("type" to "hb", "up" to "oref,tzofar")) { true })
        assertNull(PushService.pushAlertOf(mapOf("type" to "alert", "areas" to "נירים")) { true })        // בלי כותרת
        assertNull(PushService.pushAlertOf(mapOf("type" to "alert", "title" to "ירי רקטות וטילים")) { true })   // בלי יישובים
        assertNull(PushService.pushAlertOf(emptyMap()) { true })
    }

    // ---- פיקוד העורף: הקובץ החי ----
    @Test fun orefLive() {
        val text = JSONObject().put("id", "133456789").put("cat", "1").put("title", "ירי רקטות וטילים")
            .put("data", JSONArray().put("שדרות, איבים").put("נירים")).put("desc", "היכנסו למרחב המוגן").toString()
        val (id, title, areas) = AlertService.orefLive(text)!!
        assertEquals("133456789", id)
        assertEquals("ירי רקטות וטילים", title)
        assertEquals(listOf("שדרות, איבים", "נירים"), areas)
    }

    @Test fun orefLiveEmpty() {
        assertNull(AlertService.orefLive(""))
        assertNull(AlertService.orefLive(JSONObject().put("title", "ירי").toString()))   // בלי מזהה
    }

    @Test fun orefDecoding() {
        val s = "{\"title\":\"ירי רקטות וטילים\"}"
        // UTF-8 עם BOM, UTF-16 עם BOM, ו-UTF-8 רגיל - אותו טקסט
        assertEquals(s, AlertService.decodeOref(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.toByteArray(Charsets.UTF_8)))
        assertEquals(s, AlertService.decodeOref(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)))
        assertEquals(s, AlertService.decodeOref(s.toByteArray(Charsets.UTF_8)))
    }

    // ---- פיקוד העורף: היסטוריה וארכיון ----
    private fun il(date: String): Long = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Jerusalem") }.parse(date)!!.time

    @Test fun orefHistoryGroupsAndWindow() {
        val arr = JSONArray()
            .put(JSONObject().put("alertDate", "2026-11-06 14:02:10").put("title", "ירי רקטות וטילים").put("data", "שדרות, איבים"))
            .put(JSONObject().put("alertDate", "2026-11-06 14:02:10").put("title", "ירי רקטות וטילים").put("data", "נירים"))
            .put(JSONObject().put("alertDate", "2026-11-06T14:02:40").put("category_desc", "חדירת כלי טיס עוין").put("data", "כיסופים"))
            .put(JSONObject().put("alertDate", "2026-11-06 13:00:00").put("title", "ירי רקטות וטילים").put("data", "ישן"))
        val now = il("2026-11-06 14:03:00")
        val ev = AlertService.orefHistory(arr.toString(), now)
        assertEquals(2, ev.size)
        assertEquals("ירי רקטות וטילים", ev[0].first)
        assertEquals(il("2026-11-06 14:02:10"), ev[0].second)
        assertEquals(listOf("שדרות, איבים", "נירים"), ev[0].third)
        // בלי כותרת - לפי category_desc; תאריך עם T
        assertEquals("חדירת כלי טיס עוין", ev[1].first)
        assertEquals(listOf("כיסופים"), ev[1].third)
    }

    @Test fun orefHistoryNotJson() {
        assertTrue(AlertService.orefHistory("<html>", 0).isEmpty())
        assertTrue(AlertService.orefHistory("[]", 0).isEmpty())
    }

    @Test fun orefArchiveFormat() {
        // הארכיון החדש (מקובץ לפי ימים) מומר לפורמט של ההיסטוריה, ומשם לאותו פענוח
        val archive = JSONObject().put("alarmsGroupedByDate", JSONArray().put(JSONObject().put("value", JSONArray()
            .put(JSONObject().put("date", "06.11.2026").put("time", "14:02:10").put("data", "נירים").put("category_Desc", "ירי רקטות וטילים")))))
        val text = OrefArchive.convert(archive.toString())
        val ev = AlertService.orefHistory(text, il("2026-11-06 14:03:00"))
        assertEquals(1, ev.size)
        assertEquals("ירי רקטות וטילים", ev[0].first)
        assertEquals(listOf("נירים"), ev[0].third)
        assertEquals(il("2026-11-06 14:02:10"), ev[0].second)
    }
}

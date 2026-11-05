package com.tal.tzofarrelay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * הממסר - הרמה השלישית בשרשרת הגיבוי: שרת ראשי (Oracle) ← שרת משני (Cloudflare) ← ממסר ← הטלפונים בודקים בעצמם.
 * בהמתנה: פעם בדקה בודק שאחד השרתים עובד, ולא עושה כלום מעבר לזה.
 * רק כששני השרתים לא עונים - בודק את המקורות מהאינטרנט הביתי ושולח לטלפונים בעצמו (Firebase),
 * כך הטלפונים ממשיכים לקבל התרעות וסימני חיים ולא צורכים סוללה.
 */
object Relay {
    const val PRIMARY = "https://tzofar-app.duckdns.org"
    const val SECONDARY = "https://oref-proxy.t0548321068.workers.dev"

    /** המקורות שהממסר בודק כשהוא פעיל: מפתח, שם, כתובת, כל כמה זמן */
    class Src(val key: String, val name: String, val url: String, val everyMs: Long) {
        @Volatile var lastOk = 0L
        @Volatile var lastTry = 0L
        @Volatile var error = ""
    }
    val sources = listOf(
        Src("oref", "פיקוד העורף – חי", "https://www.oref.org.il/warningMessages/alert/Alerts.json", 3_000),
        Src("history", "פיקוד העורף – היסטוריה", "https://www.oref.org.il/warningMessages/alert/History/AlertsHistory.json", 10_000),
        Src("tzofar", "צופר", "https://api.tzevaadom.co.il/notifications", 5_000),
    )

    // מצב השרשרת
    @Volatile var primaryOk = 0L
    @Volatile var secondaryOk = 0L
    @Volatile var secondaryActive = false
    @Volatile var lastCheckAt = 0L
    @Volatile var active = false        // הממסר שולח לטלפונים (שני השרתים לא עונים)
    @Volatile var activeSince = 0L
    @Volatile var startedAt = 0L
    @Volatile var fcmError = ""

    private fun sp(c: Context) = c.getSharedPreferences("relay", Context.MODE_PRIVATE)
    fun key(c: Context) = sp(c).getString("key", "") ?: ""
    fun setKey(c: Context, k: String) = sp(c).edit().putString("key", k).apply()
    fun enabled(c: Context) = sp(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("on", on).apply()

    /** מפתח Firebase (מהשרת הראשי, נשמר בטלפון - בשביל השליחה כשהשרתים לא עונים) */
    fun fcmKey(c: Context) = sp(c).getString("sa", "") ?: ""
    fun fcmKeyAt(c: Context) = sp(c).getLong("saAt", 0L)
    private fun setFcmKey(c: Context, j: String) = sp(c).edit().putString("sa", j).putLong("saAt", System.currentTimeMillis()).apply()

    /** כפתור "בדוק עכשיו": מתי ומה יצא (נשמר) */
    fun lastCheck(c: Context): Pair<Long, String> = sp(c).getLong("chkAt", 0L) to (sp(c).getString("chkRes", "") ?: "")
    fun setLastCheck(c: Context, res: String) = sp(c).edit().putLong("chkAt", System.currentTimeMillis()).putString("chkRes", res).apply()

    /** התרעות שנשלחו היום (מתאפס בחצות) */
    fun forwardedToday(c: Context): Int {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        return if (sp(c).getString("fwdDay", "") == today) sp(c).getInt("fwd", 0) else 0
    }
    fun addForwarded(c: Context, n: Int) {
        if (n <= 0) return
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        sp(c).edit().putString("fwdDay", today).putInt("fwd", forwardedToday(c) + n).apply()
    }

    // ---- רשת ----
    fun http(url: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap(), timeout: Int = 8000): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = timeout; conn.useCaches = false
        conn.requestMethod = method
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (body != null) { conn.doOutput = true; conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val code = conn.responseCode
            val b = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            val txt = if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) String(b, Charsets.UTF_16LE) else String(b, Charsets.UTF_8)
            return code to txt.trimStart('﻿').replace("\u0000", "").trim()
        } finally { conn.disconnect() }
    }
    private val OREF_H = mapOf(
        "Referer" to "https://www.oref.org.il/", "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json, text/plain, */*",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 8.1.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36")
    fun fetchSrc(s: Src): Pair<Int, String> {
        val sep = if (s.url.contains("?")) "&" else "?"
        val h = if (s.key == "tzofar") mapOf("Origin" to "https://www.tzevaadom.co.il") else OREF_H
        return http(s.url + (if (s.key == "tzofar") "" else sep + "t=" + System.currentTimeMillis()), headers = h)
    }

    /** השרת הראשי: בדיקה עם המפתח (גם מוודא שהמפתח נכון). קוד התשובה (-1 = אין חיבור) */
    fun pingPrimary(key: String): Int = try { http("$PRIMARY/relay/ping", "POST", "", mapOf("X-Relay-Key" to key)).first } catch (_: Exception) { -1 }

    /** בדיקת שני השרתים, ומפתח Firebase מהשרת הראשי פעם ביום. נקרא פעם בדקה */
    fun checkServers(c: Context) {
        lastCheckAt = System.currentTimeMillis()
        val key = key(c)
        if (pingPrimary(key) == 200) {
            primaryOk = System.currentTimeMillis()
            if (fcmKey(c).isEmpty() || System.currentTimeMillis() - fcmKeyAt(c) > 24 * 3600_000L) try {
                val (code, sa) = http("$PRIMARY/relay/fcm", "POST", "", mapOf("X-Relay-Key" to key))
                if (code == 200 && sa.startsWith("{")) setFcmKey(c, sa)
            } catch (_: Exception) { }
        }
        try {
            val (code, t) = http("$SECONDARY/health")
            if (code == 200) { secondaryOk = System.currentTimeMillis(); secondaryActive = JSONObject(t).optBoolean("active") }
        } catch (_: Exception) { }
    }

    // ---- שליחה לטלפונים (Firebase) - אותה הודעה כמו מהשרתים ----
    private var token = ""; private var tokenExp = 0L
    private fun b64u(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    private fun accessToken(sa: JSONObject): String {
        if (token.isNotEmpty() && System.currentTimeMillis() < tokenExp) return token
        val now = System.currentTimeMillis() / 1000
        val head = b64u("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val body = b64u(JSONObject().put("iss", sa.getString("client_email")).put("scope", "https://www.googleapis.com/auth/firebase.messaging")
            .put("aud", "https://oauth2.googleapis.com/token").put("iat", now).put("exp", now + 3600).toString().toByteArray())
        val pem = sa.getString("private_key").replace(Regex("-----[^-]+-----"), "").replace(Regex("\\s+"), "")
        val key = java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(android.util.Base64.decode(pem, android.util.Base64.DEFAULT)))
        val sig = java.security.Signature.getInstance("SHA256withRSA").apply { initSign(key); update("$head.$body".toByteArray()) }.sign()
        val (code, t) = http("https://oauth2.googleapis.com/token", "POST",
            "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$head.$body.${b64u(sig)}",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        if (code != 200) throw Exception("token $code")
        val j = JSONObject(t)
        token = j.getString("access_token"); tokenExp = System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 300) * 1000
        return token
    }
    fun fcm(c: Context, message: JSONObject): Boolean = try {
        val saTxt = fcmKey(c)
        if (saTxt.isEmpty()) { fcmError = "אין מפתח Firebase"; false } else {
            val sa = JSONObject(saTxt)
            val (code, _) = http("https://fcm.googleapis.com/v1/projects/${sa.getString("project_id")}/messages:send", "POST",
                JSONObject().put("message", message).toString(),
                mapOf("Authorization" to "Bearer ${accessToken(sa)}", "Content-Type" to "application/json"))
            fcmError = if (code == 200) "" else "שגיאה $code"
            code == 200
        }
    } catch (e: Exception) { fcmError = "אין חיבור ל‑Firebase"; false }

    private fun android(ttl: String) = JSONObject().put("priority", "HIGH").put("ttl", ttl)
    fun admin(c: Context, text: String) = fcm(c, JSONObject().put("topic", "admin")
        .put("data", JSONObject().put("type", "admin").put("text", text)).put("android", android("3600s")))
    fun heartbeat(c: Context) {
        val up = sources.filter { System.currentTimeMillis() - it.lastOk < 5 * 60_000 }.joinToString(",") { it.key }
        fcm(c, JSONObject().put("topic", "alerts").put("data", JSONObject().put("type", "hb").put("up", up)
            .put("at", System.currentTimeMillis().toString()).put("dv", "").put("cn", "0").put("by", "relay").put("sec", "down").put("rel", "active")).put("android", android("300s")))
    }

    // אותו יישוב + סוג ב-2 דקות - פעם אחת (כמו בשרתים)
    private val sent = HashMap<String, Long>()
    @Synchronized fun send(c: Context, title: String, areasIn: List<String>, src: String, at: Long = System.currentTimeMillis(), eid: String = "") {
        val now = System.currentTimeMillis()
        sent.entries.removeAll { now - it.value > 120_000 }
        val areas = areasIn.map { it.trim() }.filter { it.isNotEmpty() }.distinct().filter { "$title|$it" !in sent }
        if (areas.isEmpty()) return
        areas.forEach { sent["$title|$it"] = now }
        val chunks = ArrayList<List<String>>(); var cur = ArrayList<String>()
        for (a in areas) { if (JSONArray(cur + a).toString().length > 1500) { chunks += cur; cur = ArrayList() }; cur += a }
        if (cur.isNotEmpty()) chunks += cur
        chunks.forEach { part ->
            fcm(c, JSONObject().put("topic", "alerts").put("data", JSONObject().put("type", "alert").put("title", title)
                .put("areas", part.joinToString("|")).put("src", src).put("at", at.toString()).put("eid", eid)).put("android", android("60s")))
        }
        addForwarded(c, 1)
    }

    // ---- עיבוד המקורות ----
    private val THREATS = mapOf(0 to "ירי רקטות וטילים", 1 to "אירוע חומרים מסוכנים", 2 to "חדירת מחבלים", 3 to "רעידת אדמה",
        4 to "חשש לצונאמי", 5 to "חדירת כלי טיס עוין", 6 to "חשש לאירוע רדיולוגי", 7 to "חשש לאירוע כימי", 8 to "התרעות פיקוד העורף")
    private var orefId = ""
    private val tzofarSeen = LinkedHashSet<String>()
    private val ilFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Jerusalem") }

    fun handle(c: Context, s: Src, txt: String) {
        when (s.key) {
            "oref" -> {
                if (txt.isEmpty()) return
                val j = JSONObject(txt)
                val id = j.optString("id")
                if (id.isEmpty() || id == orefId) return
                orefId = id
                val d = j.optJSONArray("data") ?: return
                send(c, j.optString("title", "התראה"), (0 until d.length()).map { d.getString(it) }, "oref", System.currentTimeMillis(), id)
            }
            "history" -> {
                if (!txt.startsWith("[")) return
                val a = JSONArray(txt)
                val by = LinkedHashMap<Pair<String, Long>, MutableList<String>>()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val at = try { ilFmt.parse(o.optString("alertDate").replace('T', ' ').take(19))?.time } catch (_: Exception) { null } ?: continue
                    if (System.currentTimeMillis() - at > 90_000) continue
                    by.getOrPut(o.optString("title", "התראה") to at) { mutableListOf() }.add(o.optString("data"))
                }
                by.forEach { (k, areas) -> send(c, k.first, areas, "history", k.second) }
            }
            "tzofar" -> {
                if (!txt.startsWith("[")) return
                val a = JSONArray(txt)
                for (i in 0 until a.length()) {
                    val d = a.getJSONObject(i)
                    val id = d.optString("notificationId", d.optString("time"))
                    if (!tzofarSeen.add(id)) continue
                    if (tzofarSeen.size > 200) tzofarSeen.remove(tzofarSeen.first())
                    val title = THREATS[d.optInt("threat", 0)] ?: continue
                    val cities = d.optJSONArray("cities") ?: continue
                    if (d.optBoolean("isDrill")) continue
                    send(c, title, (0 until cities.length()).map { cities.getString(it) }, "tzofar", d.optLong("time", 0) * 1000L)
                }
            }
        }
    }

    /** בדיקה מלאה עכשיו (כפתור): שני השרתים, המקורות ו-Firebase. (תקין?, תיאור) */
    fun checkNow(c: Context): Pair<Boolean, String> {
        checkServers(c)
        val bad = ArrayList<String>()
        val now = System.currentTimeMillis()
        val srvOk = now - primaryOk < 15_000 || now - secondaryOk < 15_000
        if (!srvOk) bad += "השרתים לא עונים"
        for (s in sources) {
            try { val (code, _) = fetchSrc(s); if (code == 200) s.lastOk = System.currentTimeMillis() else bad += s.name.substringAfter("– ") + " $code" }
            catch (_: Exception) { bad += s.name + " לא עונה" }
        }
        if (fcmKey(c).isEmpty()) bad += "אין מפתח Firebase"
        else try { accessToken(JSONObject(fcmKey(c))) } catch (_: Exception) { bad += "Firebase לא עונה" }
        val res = if (bad.isEmpty()) "הכול תקין" else bad.joinToString(" · ")
        setLastCheck(c, res)
        return bad.isEmpty() to res
    }
}

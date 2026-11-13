package com.tal.redalert.editor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** שינוי ברכיב אחד: הערך המקורי (base) ומה ששונה. מזהה = המסך + המסלול לרכיב בתוך המסך */
class Edit(val screen: String, val path: String, val cls: String, var label: String, val kind: String, val base: JSONObject) {
    val key get() = "$screen/$path"
    var color: String? = null
    var size: Float? = null
    var radius: Float? = null
    var text: String? = null
    var dx = 0f
    var dy = 0f
    var note = ""
    var shot: String? = null
    var theme = ""

    fun changed() = color != null || size != null || radius != null || text != null || dx != 0f || dy != 0f || note.isNotBlank()
    fun copy() = fromJson(toJson())

    fun toJson(): JSONObject = JSONObject().put("screen", screen).put("path", path).put("cls", cls).put("label", label)
        .put("kind", kind).put("base", base).put("color", color).put("size", size?.toDouble()).put("radius", radius?.toDouble())
        .put("text", text).put("dx", dx.toDouble()).put("dy", dy.toDouble()).put("note", note).put("shot", shot).put("theme", theme)

    /** מה שנשלח לשרת */
    fun toSend(): JSONObject {
        val c = JSONObject()
        fun ft(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
        color?.let { c.put("color", JSONObject().put("from", base.optString("color")).put("to", it)) }
        size?.let { c.put("size", JSONObject().put("from", base.optString("size")).put("to", ft(it))) }
        radius?.let { c.put("radius", JSONObject().put("from", base.optString("radius")).put("to", ft(it))) }
        text?.let { c.put("text", JSONObject().put("from", base.optString("text")).put("to", it)) }
        if (dx != 0f || dy != 0f) c.put("move", JSONObject().put("dx", dx.toDouble()).put("dy", dy.toDouble()))
        if (note.isNotBlank()) c.put("note", note.trim())
        return JSONObject().put("screen", screen).put("screenName", screen.substringAfter('|')).put("el", path + " · " + cls)
            .put("label", label).put("kind", kind).put("theme", theme).put("changes", c).also { o -> shot?.let { o.put("shot", it) } }
    }

    companion object {
        fun fromJson(o: JSONObject) = Edit(o.getString("screen"), o.getString("path"), o.optString("cls"), o.optString("label"),
            o.optString("kind"), o.optJSONObject("base") ?: JSONObject()).apply {
            color = o.optString("color").takeIf { it.isNotEmpty() && it != "null" }
            size = if (o.isNull("size")) null else o.optDouble("size").toFloat()
            radius = if (o.isNull("radius")) null else o.optDouble("radius").toFloat()
            text = if (o.isNull("text")) null else o.optString("text")
            dx = o.optDouble("dx", 0.0).toFloat(); dy = o.optDouble("dy", 0.0).toFloat()
            note = o.optString("note"); shot = o.optString("shot").takeIf { it.isNotEmpty() && it != "null" }
            theme = o.optString("theme")
        }
    }
}

object Store {
    private const val SITE = "https://tzofar-app.duckdns.org"
    private fun sp(c: Context) = c.getSharedPreferences("editor", Context.MODE_PRIVATE)

    /** כל השינויים, לפי המזהה */
    val edits = LinkedHashMap<String, Edit>()
    private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        try {
            val a = JSONArray(sp(c).getString("edits", "[]"))
            for (i in 0 until a.length()) Edit.fromJson(a.getJSONObject(i)).let { edits[it.key] = it }
        } catch (_: Exception) { }
    }
    fun save(c: Context) {
        val a = JSONArray(); edits.values.filter { it.changed() }.forEach { a.put(it.toJson()) }
        sp(c).edit().putString("edits", a.toString()).apply()
    }
    fun count() = edits.values.count { it.changed() }

    // ---- קריסה: נשמרת ונשלחת עם השליחה הבאה ----
    fun saveCrash(c: Context, s: String) { sp(c).edit().putString("crash", s.take(6000)).commit() }
    fun crash(c: Context) = sp(c).getString("crash", "") ?: ""
    fun clearCrash(c: Context) { sp(c).edit().remove("crash").apply() }

    // ---- שרת ----
    fun loggedIn(c: Context) = !sp(c).getString("token", "").isNullOrEmpty()

    /** כניסה עם קוד האתר. מחזיר הודעת שגיאה, או null אם הצליח */
    fun login(c: Context, code: String): String? {
        val conn = URL("$SITE/api/login").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000; conn.readTimeout = 10000; conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
            when (conn.responseCode) {
                200 -> {
                    val tok = conn.headerFields["Set-Cookie"].orEmpty().firstNotNullOfOrNull { Regex("tz=([^;]+)").find(it)?.groupValues?.get(1) }
                    if (tok.isNullOrEmpty()) "הכניסה נכשלה" else { sp(c).edit().putString("token", tok).apply(); null }
                }
                401 -> "קוד שגוי"
                429 -> "יותר מדי ניסיונות - נסה שוב בעוד דקה"
                else -> "השרת לא ענה"
            }
        } catch (_: Exception) { "אין חיבור לשרת" } finally { conn.disconnect() }
    }

    /** GET (body=null) או שליחה. מחזיר JSON, "login" אם צריך להיכנס שוב, או null אם נכשל */
    fun call(c: Context, body: JSONObject?): Any? {
        val tok = sp(c).getString("token", "") ?: return "login"
        val conn = URL("$SITE/api/design").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000; conn.readTimeout = 30000; conn.useCaches = false
            conn.setRequestProperty("Cookie", "tz=$tok")
            if (body != null) {
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            when (conn.responseCode) {
                200 -> JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                401 -> { sp(c).edit().remove("token").apply(); "login" }
                else -> null
            }
        } catch (_: Exception) { null } finally { conn.disconnect() }
    }

    /** שליחת כל השינויים (וקריסה, אם הייתה) */
    fun send(c: Context): Any? {
        val items = JSONArray()
        edits.values.filter { it.changed() }.forEach { items.put(it.toSend()) }
        crash(c).takeIf { it.isNotEmpty() }?.let {
            items.put(JSONObject().put("screen", "crash").put("screenName", "קריסה").put("el", "").put("label", "קריסה בעריכת צופר")
                .put("kind", "").put("changes", JSONObject().put("note", it)))
        }
        if (items.length() == 0) return null
        val r = call(c, JSONObject().put("op", "send").put("items", items))
        if (r is JSONObject) { edits.clear(); save(c); clearCrash(c) }
        return r
    }
}

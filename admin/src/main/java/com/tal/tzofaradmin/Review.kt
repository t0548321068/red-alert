package com.tal.tzofaradmin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * הסימונים (תקין / לתיקון), ההערות והצעות השינוי - נשמרים בשרת (דרך האתר, עם קוד הכניסה של האתר),
 * כך שאפשר לראות אותם גם במחשב ולהכניס את התיקונים. הצעת שינוי לא מגיעה לטלפונים עד שמאשרים אותה.
 */
object Review {
    private const val SITE = "https://tzofar-app.duckdns.org"
    class Edit(val id: String, val key: String, val field: String, val from: String, val to: String, val at: Long)

    @Volatile var status: Map<String, String> = emptyMap(); private set
    @Volatile var notes: Map<String, String> = emptyMap(); private set
    @Volatile var edits: List<Edit> = emptyList(); private set
    /** הקלטת הגייה שלך: מתי הוקלטה, ומצב: pending ממתין · ready מוכן לאישור · approved אושר · rejected נדחה */
    class Rec(val at: Long, val state: String)
    @Volatile var recs: Map<String, Rec> = emptyMap(); private set
    /** השליחה האחרונה לטיפול: sent (ממתין לטיפול) / done (טופל) */
    class Batch(val at: Long, val state: String, val count: Int, val doneAt: Long, val summary: String)
    @Volatile var lastBatch: Batch? = null; private set
    /** כמה דברים מחכים לטיפול: הקלטות שממתינות, הצעות שינוי, ומה שסומן לתיקון */
    fun waiting() = recs.values.count { it.state == "pending" || it.state == "rejected" } + edits.size + status.values.count { it == "fix" } + tasks.size
    /** דברים שהוספת ידנית לטיפול */
    class Task(val id: String, val text: String, val at: Long)
    @Volatile var tasks: List<Task> = emptyList(); private set
    @Volatile var online = false; private set

    private fun sp(c: Context) = c.getSharedPreferences("admin", Context.MODE_PRIVATE)
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

    private fun call(c: Context, body: JSONObject?): JSONObject? {
        val tok = sp(c).getString("token", "") ?: return null
        val conn = URL("$SITE/api/review").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000; conn.readTimeout = 15000; conn.useCaches = false
            conn.setRequestProperty("Cookie", "tz=$tok")
            if (body != null) {
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            when (conn.responseCode) {
                200 -> JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                401 -> { sp(c).edit().remove("token").apply(); null }   // פג תוקף - נכנסים שוב
                else -> null
            }
        } catch (_: Exception) { null } finally { conn.disconnect() }
    }

    private fun read(o: JSONObject) {
        fun map(j: JSONObject?) = j?.keys()?.asSequence()?.associateWith { j.getString(it) } ?: emptyMap()
        status = map(o.optJSONObject("status")); notes = map(o.optJSONObject("notes"))
        val a = o.optJSONArray("edits") ?: JSONArray()
        edits = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("state") == "pending" }.map {
            Edit(it.getString("id"), it.getString("key"), it.getString("field"), it.optString("from"), it.optString("to"), it.optLong("at"))
        }.sortedByDescending { it.at }
        recs = o.optJSONObject("recs")?.let { j -> j.keys().asSequence().associateWith {
            val r = j.getJSONObject(it); Rec(r.optLong("at"), r.optString("state", "pending")) } } ?: emptyMap()
        tasks = o.optJSONArray("tasks")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Task(it.getString("id"), it.optString("text"), it.optLong("at")) } } ?: emptyList()
        lastBatch = o.optJSONArray("batches")?.let { a -> if (a.length() == 0) null else a.getJSONObject(a.length() - 1) }?.let {
            Batch(it.optLong("at"), it.optString("state"), (it.optJSONArray("recs")?.length() ?: 0) + (it.optJSONArray("edits")?.length() ?: 0) +
                (it.optJSONArray("fix")?.length() ?: 0), it.optLong("doneAt"), it.optString("summary"))
        }
        online = true
    }

    fun candUrl(key: String) = "$SITE/api/review/cand?key=" + java.net.URLEncoder.encode(key, "UTF-8")
    fun recUrl(key: String) = "$SITE/api/review/rec?key=" + java.net.URLEncoder.encode(key, "UTF-8")
    fun cookie(c: Context) = mapOf("Cookie" to "tz=" + (sp(c).getString("token", "") ?: ""))

    /** העלאת הקלטת הגייה (ברקע). מחזיר true אם נשמרה בשרת */
    fun upload(c: Context, key: String, f: java.io.File): Boolean {
        val tok = sp(c).getString("token", "") ?: return false
        val conn = URL(recUrl(key)).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000; conn.readTimeout = 30000; conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Cookie", "tz=$tok"); conn.setRequestProperty("Content-Type", "audio/mp4")
            conn.setFixedLengthStreamingMode(f.length())
            conn.outputStream.use { out -> f.inputStream().use { it.copyTo(out) } }
            if (conn.responseCode == 200) { read(JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })); true } else false
        } catch (_: Exception) { false } finally { conn.disconnect() }
    }

    /** טעינה מהשרת (ברקע). בפעם הראשונה - מעלה את הדגלים שהיו רק בטלפון (גרסה 1.0.1) */
    fun load(c: Context) {
        val old = sp(c).getStringSet("flags", null)
        if (!old.isNullOrEmpty()) call(c, JSONObject().put("op", "import").put("fix", JSONArray(old.toList())))
            ?.let { sp(c).edit().remove("flags").apply() }
        call(c, null)?.let { read(it) } ?: run { online = false }
    }

    /** פעולה אחת (ברקע). מחזיר true אם נשמרה בשרת */
    fun send(c: Context, op: JSONObject): Boolean = call(c, op)?.let { read(it); true } ?: false

    fun op(name: String, key: String) = JSONObject().put("op", name).put("key", key)
}

package com.tal.redalert

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * התראה מיידית על גרסה חדשה.
 * GitHub שולח הודעה ל-ntfy.sh ברגע שהגרסה מתפרסמת, והאפליקציה מחוברת ל-ntfy כל הזמן.
 * לא סומכים על תוכן ההודעה: כל הודעה רק מפעילה בדיקה מול GitHub עצמו.
 */
class UpdatePush(private val beta: () -> Boolean, private val onPing: () -> Unit) {

    companion object {
        /** חייב להיות זהה לנושא ב-.github/workflows/build.yml */
        const val TOPIC = "redalert-tal-updates-7f3k9q"
        /** גרסאות בטא - רק מי שהפעיל בטא מחובר לנושא הזה */
        const val BETA_TOPIC = "redalert-tal-beta-7f3k9q"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // חיבור פתוח לאורך זמן
        .build()

    @Volatile private var running = false
    @Volatile private var call: okhttp3.Call? = null

    /** התחברות מחדש מיד (למשל אחרי הפעלה/כיבוי של בטא) */
    fun reconnect() { quick = true; call?.cancel() }
    @Volatile private var quick = false

    fun start() {
        running = true
        Thread(::loop, "update-push").start()
    }

    fun stop() { running = false }

    private fun loop() {
        while (running) {
            try { listen() } catch (_: Exception) { }
            if (running) { if (quick) quick = false else Thread.sleep(10_000) }
        }
    }

    private fun listen() {
        val topics = if (beta()) "$TOPIC,$BETA_TOPIC" else TOPIC
        val req = Request.Builder().url("https://ntfy.sh/$topics/json").build()
        val c = client.newCall(req).also { call = it }
        c.execute().use { resp ->
            if (!resp.isSuccessful) return
            val src = resp.body?.source() ?: return
            while (running) {
                val line = src.readUtf8Line() ?: break
                val ev = try { JSONObject(line).optString("event") } catch (_: Exception) { "" }
                if (ev == "message") onPing()
            }
        }
    }
}

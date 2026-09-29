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
class UpdatePush(private val onPing: () -> Unit) {

    companion object {
        /** חייב להיות זהה לנושא ב-.github/workflows/build.yml */
        const val TOPIC = "redalert-tal-updates-7f3k9q"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // חיבור פתוח לאורך זמן
        .build()

    @Volatile private var running = false

    fun start() {
        running = true
        Thread(::loop, "update-push").start()
    }

    fun stop() { running = false }

    private fun loop() {
        while (running) {
            try { listen() } catch (_: Exception) { }
            if (running) Thread.sleep(10_000)
        }
    }

    private fun listen() {
        val req = Request.Builder().url("https://ntfy.sh/$TOPIC/json").build()
        client.newCall(req).execute().use { resp ->
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

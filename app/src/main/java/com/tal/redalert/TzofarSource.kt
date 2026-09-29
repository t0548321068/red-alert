package com.tal.redalert

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** מקור 3: צופר (tzevaadom.co.il) - חיבור push בזמן אמת */
class TzofarSource(
    private val ctx: Context,
    private val onAlert: (title: String, areas: List<String>) -> Unit
) {
    companion object {
        private const val WS_URL = "wss://ws.tzevaadom.co.il/socket?platform=ANDROID"
        private const val ORIGIN = "https://www.tzevaadom.co.il"

        private val THREATS = mapOf(
            0 to "ירי רקטות וטילים",
            1 to "אירוע חומרים מסוכנים",
            2 to "חדירת מחבלים",
            3 to "רעידת אדמה",
            4 to "חשש לצונאמי",
            5 to "חדירת כלי טיס עוין",
            6 to "חשש לאירוע רדיולוגי",
            7 to "חשש לאירוע כימי",
            8 to "התרעות פיקוד העורף"
        )
        private const val PRE_TITLE = "בדקות הקרובות צפויות להתקבל התרעות באזורך"
        private const val END_TITLE = "האירוע הסתיים"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** מזהה עיר של צופר -> שם אזור של פיקוד העורף */
    private val cityIds: Map<Int, String> by lazy {
        ctx.assets.open("tzofar_ids.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { l ->
                val p = l.split('\t')
                if (p.size == 2) p[0].toIntOrNull()?.let { it to p[1] } else null
            }.toMap()
        }
    }

    @Volatile private var running = false
    private var ws: WebSocket? = null
    private val seenIds = LinkedHashSet<String>()

    fun start() {
        running = true
        connect()
    }

    fun stop() {
        running = false
        ws?.close(1000, null)
    }

    private fun connect() {
        if (!running) return
        val req = Request.Builder()
            .url(WS_URL)
            .header("Origin", ORIGIN)
            .header("Referer", ORIGIN)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) RedAlert")
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = SourceHealth.setOpen("tzofar", true)
            override fun onMessage(webSocket: WebSocket, text: String) {
                SourceHealth.ok("tzofar")
                try { parse(JSONObject(text)) } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                SourceHealth.setOpen("tzofar", false); retry()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                SourceHealth.setOpen("tzofar", false); retry()
            }
        })
    }

    private fun retry() {
        if (!running) return
        Thread {
            Thread.sleep(5000)
            connect()
        }.start()
    }

    private fun parse(msg: JSONObject) {
        val data = msg.optJSONObject("data") ?: return
        val type = msg.optString("type")
        val id = type + "_" + data.optString("notificationId")
        synchronized(seenIds) {
            if (!seenIds.add(id)) return
            if (seenIds.size > 200) seenIds.remove(seenIds.first())
        }

        when (type) {
            "ALERT" -> {
                if (data.optBoolean("isDrill")) return
                val title = THREATS[data.optInt("threat", 0)] ?: return
                val arr = data.optJSONArray("cities") ?: return
                onAlert(title, (0 until arr.length()).map { arr.getString(it) })
            }
            "SYSTEM_MESSAGE" -> {
                val title = when (data.optInt("instructionType", -1)) {
                    0 -> PRE_TITLE
                    1 -> END_TITLE
                    else -> return
                }
                val arr = data.optJSONArray("citiesIds") ?: return
                val areas = (0 until arr.length()).mapNotNull { cityIds[arr.optInt(it)] }
                if (areas.isNotEmpty()) onAlert(title, areas)
            }
        }
    }
}

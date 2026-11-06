package com.tal.redalert

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * המפות מהר יותר:
 * - הרכיב שמציג עברית על המפה (RTL) - מתוך האפליקציה, בלי הורדה מהאינטרנט בכל פתיחה
 * - רחובות שמורים בטלפון: כל אריח / גופן של מפת הרחובות (OpenFreeMap) שהורד נשמר,
 *   ובפעם הבאה מוצג מיד מהטלפון (גם בלי רשת). עד 150MB - הישנים נמחקים
 */
object MapCache {
    private const val RTL_URL = "https://cdn.jsdelivr.net/npm/@mapbox/mapbox-gl-rtl-text@0.3.0/"
    private const val TILES = "https://tiles.openfreemap.org/"
    private const val MAX_BYTES = 150L * 1024 * 1024
    private val CORS = mapOf("Access-Control-Allow-Origin" to "*")
    @Volatile private var prunedAt = 0L

    fun web(c: Context, url: String): WebResourceResponse? = try {
        when {
            url.startsWith(RTL_URL) -> WebResourceResponse("application/javascript", "utf-8", 200, "OK", CORS,
                c.assets.open("maplibre/mapbox-gl-rtl-text.js"))
            url.startsWith(TILES) -> tile(c.applicationContext, url)
            else -> null
        }
    } catch (_: Exception) { null }

    private fun dir(c: Context) = File(c.cacheDir, "maptiles").apply { mkdirs() }

    private fun key(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun mime(url: String) = when {
        url.endsWith(".pbf") -> "application/x-protobuf"
        url.endsWith(".png") -> "image/png"
        else -> "application/json"
    }

    private fun tile(c: Context, url: String): WebResourceResponse? {
        val f = File(dir(c), key(url))
        // תיאור המפה (planet) מצביע על הגרסה העדכנית של האריחים - מתעדכן כל יום; אריח / גופן - עד חודש
        val maxAge = if (url.endsWith(".pbf") || url.endsWith(".png")) 30L * 86_400_000 else 86_400_000L
        val now = System.currentTimeMillis()
        if (f.isFile && now - f.lastModified() < maxAge) return serve(url, f.readBytes())
        val fresh = try { download(url) } catch (_: Exception) { null }
        if (fresh != null) {
            try {
                val tmp = File(f.path + ".tmp"); tmp.writeBytes(fresh); tmp.renameTo(f)
            } catch (_: Exception) { }
            prune(c)
            return serve(url, fresh)
        }
        // אין רשת - מה ששמור, גם אם ישן
        return if (f.isFile) serve(url, f.readBytes()) else null
    }

    private fun serve(url: String, b: ByteArray) =
        WebResourceResponse(mime(url), null, 200, "OK", CORS, ByteArrayInputStream(b))

    private fun download(url: String): ByteArray? {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 8000; con.readTimeout = 15000
        return try {
            if (con.responseCode == 200) con.inputStream.use { it.readBytes() }
            else if (con.responseCode == 204) ByteArray(0)   // אריח ריק (ים)
            else null
        } finally { con.disconnect() }
    }

    /** לכל היותר פעם בשעה: מעל 150MB - מוחקים את הישנים */
    private fun prune(c: Context) {
        val now = System.currentTimeMillis()
        if (now - prunedAt < 3_600_000L) return
        prunedAt = now
        Thread {
            try {
                val files = dir(c).listFiles()?.sortedBy { it.lastModified() } ?: return@Thread
                var total = files.sumOf { it.length() }
                for (x in files) { if (total <= MAX_BYTES) break; total -= x.length(); x.delete() }
            } catch (_: Exception) { }
        }.start()
    }
}

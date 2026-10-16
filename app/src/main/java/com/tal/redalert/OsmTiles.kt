package com.tal.redalert

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * רחובות במפה מ-OpenStreetMap.
 * השרת שלהם חוסם בקשות בלי הזדהות (מקובץ מקומי אין Referer) - אז האפליקציה מורידה את האריחים בעצמה
 * עם שם האפליקציה (כמו שהמדיניות שלהם דורשת), ושומרת במטמון כדי לא להעמיס עליהם.
 * אריח שלא ירד - מוחזרת שגיאה, והמפה נשארת כמו קודם.
 */
object OsmTiles {
    private const val HOST = "tile.openstreetmap.org"
    @Volatile private var client: OkHttpClient? = null

    private fun client(c: Context): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .cache(Cache(File(c.cacheDir, "osm-tiles"), 60L * 1024 * 1024))
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .build().also { client = it }
    }

    /** לקרוא מ-shouldInterceptRequest של ה-WebView. null = לא אריח של OSM */
    fun intercept(c: Context, req: WebResourceRequest): WebResourceResponse? {
        val url = req.url ?: return null
        if (url.host != HOST) return null
        return try {
            val ver = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (_: Exception) { "" }
            val r = client(c.applicationContext).newCall(Request.Builder().url(url.toString())
                .header("User-Agent", "RedAlertTal/$ver (Android; +https://github.com/t0548321068/red-alert)")
                .build()).execute()
            val type = r.header("Content-Type") ?: ""
            if (!r.isSuccessful || !type.startsWith("image/")) { r.close(); return fail() }
            val bytes = r.body?.bytes() ?: return fail()
            WebResourceResponse("image/png", null, 200, "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=86400"), bytes.inputStream())
        } catch (_: Exception) { fail() }
    }

    private fun fail() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
}

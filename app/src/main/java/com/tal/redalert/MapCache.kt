package com.tal.redalert

import android.content.Context
import android.webkit.WebResourceResponse

/** הרכיב שמציג עברית על המפה (RTL) - מתוך האפליקציה, בלי הורדה מהאינטרנט בכל פתיחה */
object MapCache {
    private const val RTL_URL = "https://cdn.jsdelivr.net/npm/@mapbox/mapbox-gl-rtl-text@0.3.0/"
    private val CORS = mapOf("Access-Control-Allow-Origin" to "*")

    fun web(c: Context, url: String): WebResourceResponse? = try {
        if (url.startsWith(RTL_URL)) WebResourceResponse("application/javascript", "utf-8", 200, "OK", CORS,
            c.assets.open("maplibre/mapbox-gl-rtl-text.js"))
        else null
    } catch (_: Exception) { null }
}

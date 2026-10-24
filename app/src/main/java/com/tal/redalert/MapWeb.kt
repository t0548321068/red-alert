package com.tal.redalert

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * המפה נטענת מהקבצים שבאפליקציה, אבל בכתובת https (במקום file://).
 * כך תוסף העברית של המפה נטען מקובץ מקומי בלי אינטרנט - המפה מוכנה מהר יותר,
 * והשמות בעברית מוצגים מימין לשמאל
 */
object MapWeb {
    private const val HOST = "appassets.androidplatform.net"
    const val URL = "https://$HOST/assets/map.html"

    fun intercept(c: Context, req: WebResourceRequest): WebResourceResponse? {
        val u = req.url
        if (u.host != HOST) return null
        val path = u.path?.removePrefix("/assets/") ?: return null
        val mime = when (path.substringAfterLast('.')) {
            "html" -> "text/html"; "js" -> "application/javascript"; "css" -> "text/css"
            "json" -> "application/json"; "png" -> "image/png"; "svg" -> "image/svg+xml"; else -> "application/octet-stream"
        }
        return try { WebResourceResponse(mime, "utf-8", c.assets.open(path)) } catch (_: Exception) { null }
    }
}

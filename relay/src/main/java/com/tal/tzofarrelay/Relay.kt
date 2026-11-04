package com.tal.tzofarrelay

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * הממסר: בודק את פיקוד העורף מהאינטרנט הביתי ומעביר את התשובות לשרת כמו שהן.
 * השרת מעבד אותן בדיוק כמו בדיקה שלו (פיקוד העורף חוסם שרתי ענן מנובמבר 2026).
 */
object Relay {
    const val SERVER = "https://tzofar-app.duckdns.org/relay/"

    /** המקורות: מפתח, כתובת, כל כמה זמן */
    class Src(val key: String, val name: String, val url: String, val everyMs: Long) {
        @Volatile var lastOk = 0L
        @Volatile var lastTry = 0L
        @Volatile var error = ""
    }
    val sources = listOf(
        Src("live", "פיקוד העורף – חי", "https://www.oref.org.il/warningMessages/alert/Alerts.json", 3_000),
        Src("history", "פיקוד העורף – היסטוריה", "https://www.oref.org.il/warningMessages/alert/History/AlertsHistory.json", 10_000),
        Src("archive", "פיקוד העורף – ארכיון", "https://www.oref.org.il/api/v1/AlertHistory/heb?period=lastDay", 60_000),
    )

    @Volatile var serverOk = 0L
    @Volatile var serverError = ""
    @Volatile var startedAt = 0L

    private fun sp(c: Context) = c.getSharedPreferences("relay", Context.MODE_PRIVATE)
    fun key(c: Context) = sp(c).getString("key", "") ?: ""
    fun setKey(c: Context, k: String) = sp(c).edit().putString("key", k).apply()
    fun enabled(c: Context) = sp(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("on", on).apply()

    /** התרעות שהועברו היום (מתאפס בחצות) */
    fun forwardedToday(c: Context): Int {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        return if (sp(c).getString("fwdDay", "") == today) sp(c).getInt("fwd", 0) else 0
    }
    fun addForwarded(c: Context, n: Int) {
        if (n <= 0) return
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        sp(c).edit().putString("fwdDay", today).putInt("fwd", forwardedToday(c) + n).apply()
    }

    /** תשובה מפיקוד העורף: (קוד, טקסט). UTF-16 עם BOM או UTF-8 */
    fun fetchOref(url: String): Pair<Int, String> {
        val sep = if (url.contains("?")) "&" else "?"
        val conn = URL(url + sep + "t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 8000; conn.useCaches = false
        conn.setRequestProperty("Referer", "https://www.oref.org.il/")
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 8.1.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36")
        try {
            val code = conn.responseCode
            val b = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            val txt = if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) String(b, Charsets.UTF_16LE) else String(b, Charsets.UTF_8)
            return code to txt.trimStart('﻿').replace("\u0000", "").trim()
        } finally { conn.disconnect() }
    }

    /** שליחה לשרת: (קוד, תשובה). 401 = מפתח שגוי */
    fun post(key: String, kind: String, body: String): Pair<Int, String> {
        val conn = URL(SERVER + kind).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000; conn.readTimeout = 10000
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.setRequestProperty("X-Relay-Key", key)
        conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val t = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return code to t
        } finally { conn.disconnect() }
    }
}

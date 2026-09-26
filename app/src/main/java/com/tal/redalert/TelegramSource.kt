package com.tal.redalert

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL

/** מקור 4: ערוץ הטלגרם הרשמי של פיקוד העורף (תצוגה ציבורית, בלי בוט) */
class TelegramSource(
    private val onAlert: (title: String, areas: List<String>) -> Unit
) {
    companion object {
        private const val URL_CHANNEL = "https://t.me/s/PikudHaOref_all"
        private const val POLL_MS = 5000L
        private val POST_RE = Regex("data-post=\"PikudHaOref_all/(\\d+)\"")
        private val TEXT_RE = Regex("js-message_text[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        private val SECONDS_RE = Regex("\\(\\s*[\\d\\s]*(שניות|דקות|מיידי)[^)]*\\)")
        private val DATE_RE = Regex("\\(\\d{1,2}/\\d{1,2}/\\d{4}\\).*$")
    }

    @Volatile private var running = false
    private var lastPost = -1L

    fun start() {
        running = true
        Thread(::loop, "telegram-poll").start()
    }

    fun stop() { running = false }

    private fun loop() {
        while (running) {
            try { check() } catch (_: Exception) { }
            Thread.sleep(POLL_MS)
        }
    }

    private fun check() {
        val conn = URL(URL_CHANNEL).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RedAlert")
        val html = try {
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }

        // מפרקים לפי הודעות: כל קטע מתחיל ב-data-post
        val posts = POST_RE.findAll(html).toList()
        val parsed = posts.mapIndexedNotNull { i, m ->
            val id = m.groupValues[1].toLong()
            val end = if (i + 1 < posts.size) posts[i + 1].range.first else html.length
            val chunk = html.substring(m.range.last, end)
            TEXT_RE.find(chunk)?.let { id to it.groupValues[1] }
        }
        if (parsed.isEmpty()) return

        val newest = parsed.maxOf { it.first }
        if (lastPost < 0) {        // טעינה ראשונה: לא מתריעים על הודעות ישנות
            lastPost = newest
            return
        }
        parsed.filter { it.first > lastPost }.sortedBy { it.first }.forEach { (_, raw) ->
            parse(Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString())
        }
        lastPost = maxOf(lastPost, newest)
    }

    private fun parse(text: String) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return

        val title = when {
            text.contains("הסתיים") -> "האירוע הסתיים"
            text.contains("בדקות הקרובות") -> "בדקות הקרובות צפויות להתקבל התרעות באזורך"
            else -> lines[0]
                .replace(DATE_RE, "")
                .filter { it.isLetterOrDigit() || it == ' ' || it == '\'' || it == '"' || it == '-' }
                .trim()
        }
        if (title.isEmpty()) return

        val areas = lines.drop(1)
            .filterNot { l ->
                l.startsWith("אזור") || l.contains("מרחב המוגן") || l.contains("השוהים") ||
                    l.contains("הסתיים") || l.contains("בדקות הקרובות") || l.contains(".") ||
                    l.startsWith("עדכון") || l.startsWith("על תושבי")
            }
            .flatMap { l -> l.replace(SECONDS_RE, "").split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 40 }

        if (areas.isNotEmpty()) onAlert(title, areas)
    }
}

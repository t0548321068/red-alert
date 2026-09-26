package com.tal.redalert

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL

/**
 * קורא ערוץ טלגרם ציבורי (בלי בוט) דרך t.me/s/<channel>.
 * מבין גם את הפורמט של פיקוד העורף וגם את הפורמט של צופר.
 */
class TelegramSource(
    private val channel: String,
    private val onAlert: (title: String, areas: List<String>) -> Unit
) {
    companion object {
        private const val POLL_MS = 5000L
        private val TEXT_RE = Regex("js-message_text[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        private val SECONDS_RE = Regex("\\(\\s*[\\d\\s]*(שניות|דקות|מיידי)[^)]*\\)")
        private val DATE_RE = Regex("\\(\\d{1,2}/\\d{1,2}/\\d{4}\\).*$")
        private val ENDED_IN_RE = Regex("הסתיים ב(.+)")
    }

    private val postRe = Regex("data-post=\"" + Regex.escape(channel) + "/(\\d+)\"", RegexOption.IGNORE_CASE)

    @Volatile private var running = false
    private var lastPost = -1L

    fun start() {
        running = true
        Thread(::loop, "tg-$channel").start()
    }

    fun stop() { running = false }

    private fun loop() {
        while (running) {
            try { check() } catch (_: Exception) { }
            Thread.sleep(POLL_MS)
        }
    }

    private fun check() {
        val conn = URL("https://t.me/s/$channel").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RedAlert")
        val html = try {
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }

        val posts = postRe.findAll(html).toList()
        val parsed = posts.mapIndexedNotNull { i, m ->
            val id = m.groupValues[1].toLong()
            val end = if (i + 1 < posts.size) posts[i + 1].range.first else html.length
            TEXT_RE.find(html.substring(m.range.last, end))?.let { id to it.groupValues[1] }
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
            text.contains("כלי טיס") -> "חדירת כלי טיס עוין"
            text.contains("מחבלים") -> "חדירת מחבלים"
            text.contains("צבע אדום") || text.contains("רקטות") -> "ירי רקטות וטילים"
            else -> lines[0]
                .replace(DATE_RE, "")
                .filter { it.isLetterOrDigit() || it == ' ' || it == '\'' || it == '"' || it == '-' }
                .trim()
        }
        if (title.isEmpty()) return

        val areas = lines.flatMapIndexed { i, l ->
            val ended = ENDED_IN_RE.find(l)
            when {
                // צופר: "האירוע הסתיים בכפר יובל, דפנה"
                ended != null -> ended.groupValues[1].split(",")
                // צופר: "05:23: • עוטף עזה: כיסופים (15 שניות)"
                l.contains("•") -> l.substringAfter("•").substringAfterLast(":").split(",")
                // פיקוד העורף: שורת יישובים רגילה
                i > 0 && !skip(l) -> l.split(",")
                else -> emptyList()
            }
        }
            .map { it.replace(SECONDS_RE, "").trim().trimEnd('.') }
            .filter { it.isNotEmpty() && it.length <= 40 }

        if (areas.isNotEmpty()) onAlert(title, areas)
    }

    private fun skip(l: String) =
        l.startsWith("אזור") || l.contains("מרחב המוגן") || l.contains("השוהים") ||
            l.contains("הסתיים") || l.contains("בדקות הקרובות") || l.contains(".") ||
            l.startsWith("עדכון") || l.startsWith("על תושבי")
}

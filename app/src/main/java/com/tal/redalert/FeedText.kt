package com.tal.redalert

import android.content.Context

/** טקסטים לפיד הארצי */
object FeedText {

    /** חלון הזמן לפס הרץ */
    private const val RECENT_MS = 30 * 60 * 1000L

    private fun icon(level: Int) = when (level) {
        AlertService.LEVEL_PRE -> "🟠"
        AlertService.LEVEL_END -> "🟢"
        else -> "🔴"
    }

    private fun short(title: String) = when {
        title.contains("בדקות הקרובות") -> "מקדימה"
        title.contains("הסתיים") -> "הסתיים"
        else -> title
    }

    fun line(c: Context, e: Prefs.Entry) =
        "${icon(e.level)} ${TimeFormat.time(c, e.ts)} ${short(e.title)}: ${e.body}"

    /** פס רץ: ההתראות מ-30 הדקות האחרונות, או null אם אין */
    fun latest(c: Context): String? {
        val since = System.currentTimeMillis() - RECENT_MS
        val items = Prefs.feed(c).filter { it.ts >= since }.take(8)
        if (items.isEmpty()) return null
        return items.joinToString("     •     ") { line(c, it) }
    }

    /** רשימה מלאה ל-24 השעות האחרונות */
    fun day(c: Context): String {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val items = Prefs.feed(c).filter { it.ts >= since }
        if (items.isEmpty()) return "אין התראות בארץ ב־24 השעות האחרונות"
        return items.joinToString("\n\n") { line(c, it) }
    }
}

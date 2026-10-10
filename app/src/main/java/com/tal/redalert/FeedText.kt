package com.tal.redalert

import android.content.Context

/** טקסטים לפיד הארצי */
object FeedText {

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

    /** אפשרויות חלון הזמן של הפיד, בדקות */
    val WINDOWS = intArrayOf(30, 60, 180, 360, 720, 1440)

    /** "30 הדקות האחרונות" / "השעה האחרונה" / "3 השעות האחרונות" / "24 השעות האחרונות" */
    fun windowLabel(min: Int) = when {
        min < 60 -> "$min הדקות האחרונות"
        min == 60 -> "השעה האחרונה"
        min % 60 == 0 -> "${min / 60} השעות האחרונות"
        else -> "%d:%02d השעות האחרונות".format(min / 60, min % 60)   // מותאם אישית, למשל 1:30
    }

    /** ההתראות בפיד לפי חלון הזמן שנבחר בהגדרות (החדשה ראשונה) */
    fun recent(c: Context): List<String> {
        val since = System.currentTimeMillis() - Prefs.feedMinutes(c) * 60_000L
        return Prefs.feed(c).filter { it.ts >= since }.take(30).map { line(c, it) }
    }

    /** שורה אחת (מסך השעון): ההתראות בחלון הזמן, או null אם אין */
    fun latest(c: Context): String? = recent(c).take(8).ifEmpty { null }?.joinToString("     •     ")
}

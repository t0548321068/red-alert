package com.tal.redalert

import android.content.Context

/** טקסטים לפיד הארצי */
object FeedText {

    private fun short(title: String) = when {
        title.contains("בדקות הקרובות") -> "מקדימה"
        title.contains("הסתיים") -> "האירוע הסתיים"
        else -> title
    }

    /** אפשרויות חלון הזמן של הפיד, בדקות */
    val WINDOWS = intArrayOf(30, 60, 180, 360, 720, 1440)

    /** "30 הדקות האחרונות" / "השעה האחרונה" / "3 השעות האחרונות" / "24 השעות האחרונות" */
    fun windowLabel(min: Int) = when {
        min < 60 -> "$min הדקות האחרונות"
        min == 60 -> "השעה האחרונה"
        min % 60 == 0 -> "${min / 60} השעות האחרונות"
        else -> "%d:%02d השעות האחרונות".format(min / 60, min % 60)   // מותאם אישית, למשל 1:30
    }

    private fun inWindow(c: Context): List<Prefs.Entry> {
        val since = System.currentTimeMillis() - Prefs.feedMinutes(c) * 60_000L
        return Prefs.feed(c).filter { it.ts >= since }
    }

    /**
     * פריט בפיד: text עם תו מקום (\uFFFC) לפני ואחרי סוג האירוע - שם מוצג אייקון סוג ההתרעה
     * (icon) בצבע הרמה (color)
     */
    data class Item(val text: String, val icon: Int, val color: String)

    /** פיד במסך הראשי, בשלוש שורות: שעה / "אייקון סוג אייקון" / אזורי ההתרעה (החדשה ראשונה) */
    fun recent(c: Context): List<Item> =
        inWindow(c).take(30).map {
            Item("${TimeFormat.time(c, it.ts)}\n\uFFFC ${short(it.title)} \uFFFC\n${it.body}",
                AlertUi.icon(it.title, it.level), AlertUi.color(it.level))
        }
}

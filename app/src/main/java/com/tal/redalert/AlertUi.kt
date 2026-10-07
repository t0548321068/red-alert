package com.tal.redalert

/** טקסטים וטיימר משותפים למסך ההתרעה, לפופ-אפ, לכרטיס מעל השעון ולחלון הממוזער */
object AlertUi {
    fun color(level: Int) = when (level) {
        AlertService.LEVEL_PRE -> "#F08C00"
        AlertService.LEVEL_END -> "#2E7D32"
        else -> "#D50000"
    }

    /** כותרת + שורה מתחתיה */
    fun head(title: String, level: Int): Pair<String, String> = when (level) {
        AlertService.LEVEL_PRE -> "מבזק פיקוד העורף" to "התרעה מקדימה"
        AlertService.LEVEL_END -> "עדכון פיקוד העורף" to "האירוע הסתיים"
        else -> title to "היכנסו למרחב המוגן"
    }

    private fun mmss(s: Int) = "%d:%02d".format(s / 60, s % 60)

    /** (כיתוב, טיימר) לרמת "ירי": זמן התגוננות -> נשארים במרחב המוגן -> ממתינים לסיום. null לשאר הרמות */
    fun timer(level: Int, shelter: Int, firedAt: Long, now: Long = System.currentTimeMillis()): Pair<String, String>? {
        if (level != AlertService.LEVEL_ALERT) return null
        val enterEnd = firedAt + shelter.coerceAtLeast(0) * 1000L
        if (now < enterEnd) return "זמן התגוננות" to mmss(((enterEnd - now + 999) / 1000).toInt())
        val left = ((enterEnd + Prefs.STAY_MS - now + 999) / 1000).toInt()
        return if (left > 0) "נשארים במרחב המוגן" to mmss(left) else "" to "ממתינים להודעת סיום אירוע"
    }

    /** הכרטיס מעל השעון נעלם: סיום אחרי 10 דק', מקדימה אחרי 30, ירי אחרי שעה (אם לא הגיעה הודעת סיום) */
    fun expired(level: Int, firedAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        val keep = when (level) {
            AlertService.LEVEL_END -> 10 * 60_000L
            AlertService.LEVEL_PRE -> 30 * 60_000L
            else -> 60 * 60_000L
        }
        return now - firedAt > keep
    }
}

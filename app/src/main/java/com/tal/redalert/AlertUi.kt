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

    /** אייקון לפי סוג ההתרעה: מקדימה / סיום, ובאדום - לפי סוג האיום. לא מזוהה: צופר */
    fun icon(title: String, level: Int): Int = when {
        level == AlertService.LEVEL_PRE -> R.drawable.ic_list_pre
        level == AlertService.LEVEL_END -> R.drawable.ic_list_end
        title.contains("כלי טיס") -> R.drawable.ic_alert_aircraft
        title.contains("מחבלים") -> R.drawable.ic_alert_infiltration
        title.contains("רעידת אדמה") -> R.drawable.ic_alert_quake
        title.contains("צונאמי") -> R.drawable.ic_alert_tsunami
        title.contains("רדיולוגי") -> R.drawable.ic_alert_radio
        title.contains("חומרים מסוכנים") || title.contains("כימי") -> R.drawable.ic_alert_hazmat
        title.contains("רקטות") || title.contains("טילים") -> R.drawable.ic_alert_rocket
        else -> R.drawable.ic_list_siren
    }

    /**
     * אייקון לפי השלב: בירי - סוג ההתרעה בזמן ההתגוננות, בית בזמן "נשארים במרחב המוגן",
     * שעון חול ב"ממתינים להודעת סיום". בשאר הרמות - כמו icon()
     */
    fun stageIcon(title: String, level: Int, shelter: Int, firedAt: Long, now: Long = System.currentTimeMillis()): Int {
        if (level != AlertService.LEVEL_ALERT) return icon(title, level)
        val enterEnd = firedAt + shelter.coerceAtLeast(0) * 1000L
        return when {
            now < enterEnd -> icon(title, level)
            now < enterEnd + Prefs.STAY_MS -> R.drawable.ic_stage_stay
            else -> R.drawable.ic_stage_wait
        }
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

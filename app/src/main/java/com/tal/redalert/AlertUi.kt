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
        AlertService.LEVEL_PRE -> "מבזק פיקוד העורף" to (if (title.startsWith("התרעה מקדימה - ")) title else "התרעה מקדימה")
        AlertService.LEVEL_END -> "עדכון פיקוד העורף" to (if (update(title)) title else "האירוע הסתיים")
        else -> title to instruction(title)
    }

    /** ההנחיה לפי סוג ההתרעה - הנוסח של יישומון פיקוד העורף (alertsTranslation.json באתר שלהם) */
    fun instruction(title: String) = when {
        title.contains("כלי טיס") -> "היכנסו מייד למרחב המוגן"
        title.contains("מחבלים") -> "היכנסו מייד למרחב המוגן"
        title.contains("חומרים מסוכנים") -> "הסתגרו מייד במבנה"
        title.contains("רדיולוגי") -> "היכנסו למבנה וסגרו את הדלתות והחלונות"
        title.contains("רעידת אדמה") -> "צאו לשטח פתוח"
        title.contains("צונאמי") -> "התרחקו מייד מחוף הים"
        else -> "היכנסו למרחב המוגן"
    }

    /** כותרת קצרה ברשימות ובהתראות: מקדימה = "התרעה מקדימה" (אלא אם כבר עם הקידומת) */
    fun listTitle(title: String, level: Int) = if (level == AlertService.LEVEL_PRE && !title.startsWith("התרעה מקדימה")) "התרעה מקדימה" else title

    /** עדכון עם נוסח משלו - מוצג כמו שהגיע */
    fun update(title: String) = Config.updateWords.any { title.contains(it) }

    /**
     * זמן הגעה לפי האזור - רק בירי רקטות וטילים (וסוג שלא מזוהה). בשאר הסוגים פיקוד העורף מנחה "מייד",
     * בלי זמן התגוננות: בלי ספירה לאחור ובלי הקראת הזמן
     */
    fun noShelter(title: String) = title.contains("כלי טיס") || title.contains("מחבלים") || title.contains("חומרים מסוכנים") ||
        title.contains("רדיולוגי") || title.contains("רעידת אדמה") || title.contains("צונאמי")
    /** ירי וכלי טיס: נשארים במרחב המוגן עד הודעה (בלי 10 דקות - פיקוד העורף: "עד לקבלת הנחיה מפורשת"). השאר - ממתינים להודעת סיום */
    fun staysInShelter(title: String) = !noShelter(title) || title.contains("כלי טיס")
    const val STAY_TEXT = "נשארים במרחב המוגן עד הודעה"
    const val WAIT_TEXT = "ממתינים להודעת סיום אירוע"
    /** הכיתוב אחרי זמן ההגעה (או מההתחלה, כשאין זמן הגעה) */
    fun afterText(title: String) = if (staysInShelter(title)) STAY_TEXT else WAIT_TEXT

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

    /** אייקונים עגולים (מקדימה, סיום, מחבלים) נראים קטנים מהשאר - מוצגים מוגדלים */
    fun scale(res: Int): Float = when (res) {
        R.drawable.ic_list_pre, R.drawable.ic_list_end -> 1.40f
        R.drawable.ic_alert_infiltration -> 1.55f   // מוקטן בפנים כדי שקווי הכוונת ייצאו מהעיגול
        else -> 1f
    }

    /** הצגת אייקון התרעה בגודל אחיד. big=false: במסכי ההתרעה (מסך מלא, פופ-אפ, כרטיס, ממוזער) - בלי הגדלה */
    fun setIcon(v: android.widget.ImageView?, res: Int, big: Boolean = true) {
        v ?: return
        v.setImageResource(res); val s = if (big) scale(res) else 1f; v.scaleX = s; v.scaleY = s
    }

    /**
     * אייקון לפי השלב: בירי - סוג ההתרעה בזמן ההתגוננות, ואחריו בית ("נשארים במרחב המוגן עד הודעה").
     * סוג בלי זמן הגעה - האייקון של הסוג כל הזמן. בשאר הרמות - כמו icon()
     */
    fun stageIcon(title: String, level: Int, shelter: Int, firedAt: Long, now: Long = System.currentTimeMillis()): Int {
        if (level != AlertService.LEVEL_ALERT || noShelter(title)) return icon(title, level)
        return if (now < firedAt + shelter.coerceAtLeast(0) * 1000L) icon(title, level) else R.drawable.ic_stage_stay
    }

    private fun mmss(s: Int) = "%d:%02d".format(s / 60, s % 60)

    /** (כיתוב, טיימר) לרמת "ירי": זמן התגוננות -> נשארים במרחב המוגן עד הודעה / ממתינים להודעת סיום. null לשאר הרמות */
    fun timer(level: Int, shelter: Int, firedAt: Long, now: Long = System.currentTimeMillis(), title: String = ""): Pair<String, String>? {
        if (level != AlertService.LEVEL_ALERT) return null
        val enterEnd = firedAt + shelter.coerceAtLeast(0) * 1000L
        if (!noShelter(title) && now < enterEnd) return "זמן התגוננות" to mmss(((enterEnd - now + 999) / 1000).toInt())
        return "" to afterText(title)
    }

    /**
     * כמה זמן כל צבע מוצג: כתום (מקדימה) 2 דקות, ירוק (סיום) דקה.
     * אדום (ירי) - עד הודעת הסיום; null = בלי זמן קבוע
     */
    fun keepMs(level: Int): Long? = when (level) {
        AlertService.LEVEL_PRE -> 2 * 60_000L
        AlertService.LEVEL_END -> 60_000L
        else -> null
    }

    /** הכרטיס מעל השעון נעלם לפי keepMs. ירי - עד הסיום (רשת ביטחון: שעה, אם הודעת הסיום לא הגיעה בכלל) */
    fun expired(level: Int, firedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        now - firedAt > (keepMs(level) ?: 60 * 60_000L)

    /**
     * כרטיס ההתרעה במסך הראשי: (להציג כרטיס, לצבוע את המסך). בלי טלפון - נבדק בבדיקות.
     * - ממוזער פתוח / מסך התרעה פתוח: בלי כרטיס (כפילות), אבל צבוע
     * - מסך מלא / פופ-אפ / ממוזער שעוד לא עלה (4 השניות הראשונות): גם בלי צבע - שלא ייראה המסך הראשי לפני ההתרעה
     * - אחרי שמסך ההתרעה כבר הוצג ונסגר: כרטיס וצבע מיד
     */
    fun cardView(hasAlert: Boolean, active: Boolean, mini: Boolean, screensOpen: Int, shownBefore: Boolean,
                 cardStyle: Boolean, preview: Boolean, ageMs: Long): Pair<Boolean, Boolean> {
        val opening = screensOpen == 0 && !mini && hasAlert && !preview && !shownBefore && !cardStyle && ageMs < 4000
        val hide = !active || mini || screensOpen > 0 || opening
        return if (hide) false to (active && !opening) else true to true
    }

    /** סגירת מסך מלא / פופ-אפ: לחלון הממוזער (אם נבחר "אחרי סגירה: ממוזער", יש הרשאה, לא תצוגה מקדימה ולא נגמרה) */
    fun closeToMini(afterClose: Int, canShow: Boolean, preview: Boolean, expired: Boolean) =
        afterClose == 1 && canShow && !preview && !expired
}

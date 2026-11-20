package com.tal.redalert

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tal.redalert.MainActivity.C

// (חלק מהמסך הראשי - MainActivity; המצב עצמו נשמר שם)

// עמודי ההגדרות: תצוגה - ערכת צבעים, שעון, תאריך, פיד, מזג אוויר וברכה

internal fun MainActivity.setTheme(d: android.app.Dialog, mode: Int, rebuild: () -> Unit) {
    if (Prefs.themeMode(this) == mode) return
    Prefs.setThemeMode(this, mode)
    applyThemeNow(d, rebuild)
}

/** עמוד התצוגה מתחלף מיד לצבעים לפי ההגדרה הנוכחית */
internal fun MainActivity.applyThemeNow(d: android.app.Dialog, rebuild: () -> Unit) {
    // עמוד התצוגה מתחלף מיד לצבעים החדשים; שאר האפליקציה - ביציאה מהעמוד
    C.apply(Prefs.darkTheme(this), Palettes.current(this, Prefs.darkTheme(this)))
    (d.window?.decorView?.findViewWithTag<View>("displayPage"))?.setBackgroundColor(C.BG)
    @Suppress("DEPRECATION")
    d.window?.statusBarColor = C.BG
    if (Build.VERSION.SDK_INT >= 30) {
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        d.window?.insetsController?.setSystemBarsAppearance(if (C.LIGHT) light else 0, light)
    }
    rebuild()
}

/** עמוד "תצוגה" בסגנון One UI (כמו בהגדרות של סמסונג) */
internal fun MainActivity.showDisplay() {
    val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar)
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(30)) }
    val page = ScrollView(this).apply {
        tag = "displayPage"
        setBackgroundColor(C.BG)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        addView(body)
        setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
    }
    val blue = Color.parseColor("#5AA9FF")
    fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = graphite(26); clipToOutline = true }
    fun addCard(c: View) = body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(PAGE_SIDE), 0, dp(PAGE_SIDE), dp(12)) })
    fun note(t: String) = body.addView(text(t, 11f, C.MUTED2).apply { setPadding(dp(26), 0, dp(26), dp(14)) })
    // קו מפריד עם שוליים מהצדדים (לא מקצה לקצה), כמו בהגדרות של סמסונג
    fun divider(c: LinearLayout) = c.addView(View(this).apply { setBackgroundColor(C.DIV) },
        LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(18), 0, dp(18), 0) })
    /** שורה: שם + מצב בכחול + מתג. open = לחיצה על השם פותחת הגדרה נוספת (והמתג לבד מדליק/מכבה) */
    fun row(c: LinearLayout, title: String, sub: String = "", on: Boolean? = null, sep: Boolean = true,
            open: (() -> Unit)? = null, click: () -> Unit) {
        // גובה קבוע לכל השורות - כמו במסך ההגדרות הראשי (המתג לא מגביה)
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { (open ?: click)() }
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(text(title, 16f, C.TEXT).apply { includeFontPadding = false })
        if (sub.isNotEmpty()) t.addView(text(sub, 12f, blue).apply { includeFontPadding = false; setPadding(0, dp(4), 0, 0) })
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        if (on != null) {
            if (open != null) switchZone(r, on, dp(18)) { click() }
            else {
                if (sep) r.addView(View(this).apply { setBackgroundColor(C.SEPV) },
                    LinearLayout.LayoutParams(dp(1), dp(20)).apply { setMargins(dp(12), 0, dp(12), 0) })
                r.addView(OuiSwitch(this, on, C.LIGHT).apply { isClickable = false })
            }
        }
        c.addView(r, LinearLayout.LayoutParams(-1, dp(ROW_H)))   // כמו שורות מסך ההגדרות הראשי
    }
    fun rebuild() {
        body.removeAllViews()
        // כותרת עם חזרה
        body.addView(pageHeader("תצוגה"))
        // ערכת צבעים: תצוגה מקדימה חשוך / בהיר + "לפי המערכת"
        val mode = Prefs.themeMode(this)
        val theme = card()
        val pick = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(20), dp(10), dp(16)) }
        fun preview(light: Boolean, label: String, sel: Boolean, value: Int) = LinearLayout(this).apply {
            val p = if (light) Palettes.light(this@showDisplay) else Palettes.dark(this@showDisplay)   // התצוגה המקדימה בפלטה שנבחרה
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val box = LinearLayout(this@showDisplay).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply { setColor(p.c(if (light) p.chip else p.bg).let { if (!light && p.id == "black") Color.parseColor("#2B2B2E") else it }); cornerRadius = dp(12).toFloat() }
                listOf(12, 26, 40, 26).forEach { h ->
                    addView(View(this@showDisplay).apply {
                        background = GradientDrawable().apply { setColor(p.c(if (light) p.sepv else p.chip)); cornerRadius = dp(6).toFloat() }
                    }, LinearLayout.LayoutParams(-1, dp(h)).apply { bottomMargin = dp(5) })
                }
            }
            addView(box, LinearLayout.LayoutParams(dp(76), dp(150)))
            addLabelRadio(this, label, sel)
            setOnClickListener { setTheme(d, value) { rebuild() } }
        }
        val sys = mode == 2
        val byTime = mode == 3
        // חשוך / בהיר. הגוון של כל אחד - ב"פלטת צבעים" (מתחת)
        pick.addView(preview(false, "חשוך", mode == 0, 0), LinearLayout.LayoutParams(0, -2, 1f))
        pick.addView(preview(true, "בהיר", mode == 1, 1), LinearLayout.LayoutParams(0, -2, 1f))
        theme.addView(pick)
        divider(theme)
        row(theme, "לפי המערכת", on = sys, sep = false) { setTheme(d, if (sys) 0 else 2) { rebuild() } }
        row(theme, "לפי זמן", themeTimeLabel(), on = byTime, sep = false,
            open = { chooseThemeTime { applyThemeNow(d) { rebuild() } } }) { setTheme(d, if (byTime) 0 else 3) { rebuild() } }
        addCard(theme)
        note("לפי המערכת – בהיר או חשוך לפי הגדרת הטלפון\nלפי זמן – חשוך בלילה ובהיר ביום")
        // פלטת צבעים: גוון לחשוך וגוון לבהיר (מעריכת צופר)
        val pal = card()
        row(pal, "ערכות נושא", "חשוך: ${Palettes.dark(this).name} · בהיר: ${Palettes.light(this).name}", sep = false) {
            choosePalette { applyThemeNow(d) { rebuild() } } }
        addCard(pal)
        // כרטיס ההגנה: רקע ומסגרת
        val prot = card()
        row(prot, "כרטיס ההגנה", "${PROT_BG[Prefs.protBg(this)]} · ${PROT_FRAME[Prefs.protFrame(this)]} · ${PROT_ICON_NAMES[Prefs.protIcon(this)]} · ${Prefs.protLabel(this)}", sep = false) {
            chooseProtCard { rebuild() } }
        addCard(prot)
        // שורת החיוויים במסך הראשי
        val ind = card()
        val sm = Prefs.statusMode(this)
        row(ind, "שורת חיוויים", statusModeLabel(), on = sm != Prefs.STATUS_HIDDEN, sep = false,
            open = { chooseStatusMode { rebuild() } }) {
            Prefs.setStatusMode(this, if (sm == Prefs.STATUS_HIDDEN) Prefs.STATUS_ALWAYS else Prefs.STATUS_HIDDEN)
            applyStatusRow(); rebuild() }
        addCard(ind)
        note("מוגן · צלילים · רשת · מיקום · חיבור · גרסה – בתחתית המסך הראשי, או למעלה")
        // כרטיס השעון: שעון, תאריך, מזג אוויר וברכה - בעמוד משלו
        val more = card()
        row(more, "כרטיס השעון", clockCardSummary(), sep = false) { chooseClockCard { rebuild() } }
        addCard(more)
        // פיד ארצי - כמה זמן אחורה להציג
        val feed = card()
        row(feed, "פיד ארצי", FeedText.windowLabel(Prefs.feedMinutes(this)), on = Prefs.feedAlways(this),
            open = { chooseFeedWindow { rebuild() } }) {
            Prefs.setFeedAlways(this, !Prefs.feedAlways(this)); updateFeed(); rebuild() }
        addCard(feed)
        note("ההתרעות בכל הארץ בתחתית המסך הראשי. כשהמתג מופעל - הפיד מוצג תמיד, גם כשאין התרעות")
    }
    themeAtOpen = Prefs.themeMode(this)
    val darkAtOpen = Prefs.darkTheme(this)
    val palAtOpen = Prefs.darkPalette(this) + "|" + Prefs.lightPalette(this)
    rebuild()
    // יציאה מהעמוד: אם ערכת הצבעים השתנתה - טוענים מחדש וחוזרים להגדרות
    track(d) { if (Prefs.themeMode(this) != themeAtOpen || Prefs.darkTheme(this) != darkAtOpen || Prefs.darkPalette(this) + "|" + Prefs.lightPalette(this) != palAtOpen) { MainActivity.reopenDisplay = true; recreate() } }
    body.setPadding(0, 0, 0, dp(30))
    d.setContentView(page)
    d.window?.setLayout(-1, -1)
    @Suppress("DEPRECATION")
    d.window?.statusBarColor = C.BG
    edgeToEdge(d)
    // חוזרים מחלון בחירה - מעדכנים את המצבים
    refreshOnReturn(d) { rebuild() }
    d.show()
}

internal fun MainActivity.weatherLabel(min: Int) = when (min) {
    1 -> "כל דקה"
    60 -> "כל שעה"
    else -> "כל $min דקות"
}

/** עמוד תאריך: הצגה + סגנון היום - כל שינוי נראה מיד בשעון */
internal fun MainActivity.chooseDayStyle(done: () -> Unit) = ouiPage("תאריך", onClose = done) { body, rebuild ->
    val show = ouiCard()
    ouiRow(show, "הצגת תאריך", on = Prefs.showDate(this)) {
        Prefs.setShowDate(this, !Prefs.showDate(this)); updateClock(); refresh(); rebuild() }
    ouiAdd(body, show)
    ouiNote(body, "התאריך מוצג מתחת לשעון במסך הראשי")
    // סוג תאריך: לועזי / עברי / שניהם
    val kinds = ouiCard()
    kinds.addView(text("סוג תאריך", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
    val now = System.currentTimeMillis()
    listOf("לועזי" to TimeFormat.date(now), "עברי" to TimeFormat.hebrewDate(now),
        "לועזי ועברי" to TimeFormat.date(now) + " · " + TimeFormat.hebrewDate(now)).forEachIndexed { i, (label, ex) ->
        if (i > 0) ouiDivider(kinds)
        ouiRow(kinds, label, sub = ex, radio = Prefs.dateKind(this) == i) {
            Prefs.setDateKind(this, i); updateClock(); refresh(); rebuild() }
    }
    ouiAdd(body, kinds)
    val styles = ouiCard()
    styles.addView(text("סגנון יום", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
    TimeFormat.dayOptions().forEachIndexed { i, label ->
        if (i > 0) ouiDivider(styles)
        ouiRow(styles, label, radio = Prefs.dayStyle(this) == i) {
            Prefs.setDayStyle(this, i); updateClock(); refresh(); rebuild() }
    }
    ouiAdd(body, styles)
}

/** עמוד פיד ארצי: כמה זמן אחורה להציג - מ-30 דקות ועד 24 שעות */
internal fun MainActivity.chooseFeedWindow(done: () -> Unit) = ouiPage("פיד ארצי", onClose = done) { body, rebuild ->
    val opts = ouiCard()
    opts.addView(text("הצגת התרעות מ…", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
    val cur = Prefs.feedMinutes(this)
    FeedText.WINDOWS.forEachIndexed { i, m ->
        if (i > 0) ouiDivider(opts)
        ouiRow(opts, FeedText.windowLabel(m), radio = cur == m) {
            Prefs.setFeedMinutes(this, m); updateFeed(); rebuild() }
    }
    // מותאם אישית: שעות ודקות בגלגלים (כמו "הגדר שעה" של סמסונג)
    val custom = cur !in FeedText.WINDOWS
    ouiDivider(opts)
    ouiRow(opts, "מותאם אישית", if (custom) FeedText.windowLabel(cur) else "", radio = custom) {
        // עד 24:00 בדיוק (24 שעות) - בגלגל השעות 00 עד 24
        pickTime("זמן מותאם אישית (שעות : דקות)", if (custom) cur else 90, hourCount = 25) { m ->
            Prefs.setFeedMinutes(this, m.coerceIn(1, 24 * 60)); updateFeed(); rebuild() }
    }
    ouiAdd(body, opts)
    ouiNote(body, "ההתרעות מתחלפות אחת אחרי השנייה בגלילה כלפי מעלה")
}

internal fun MainActivity.chooseWeatherInterval(done: () -> Unit) = ouiPage("מזג אוויר", onClose = done) { body, rebuild ->
    val show = ouiCard()
    ouiRow(show, "הצגת מזג אוויר", on = Prefs.showWeather(this)) {
        Prefs.setShowWeather(this, !Prefs.showWeather(this)); updateWeather(force = true); rebuild() }
    ouiAdd(body, show)
    ouiNote(body, "טמפרטורה ומצב מזג האוויר לפי המיקום, בכרטיס השעון")
    val opts = intArrayOf(1, 2, 5, 10, 15, 30, 60)
    val freq = ouiCard()
    freq.addView(text("תדירות עדכון", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
    opts.forEachIndexed { i, m ->
        if (i > 0) ouiDivider(freq)
        ouiRow(freq, weatherLabel(m), radio = Prefs.weatherMinutes(this) == m) {
            Prefs.setWeatherMinutes(this, m); rebuild() }
    }
    ouiAdd(body, freq)
    ouiNote(body, "עדכון תכוף יותר משתמש קצת יותר בסוללה ובאינטרנט")
}

/** עמוד שעון: שניות ונקודתיים מהבהבות */
internal fun MainActivity.showClockPage() = ouiPage("שעון") { body, rebuild ->
    val c = ouiCard()
    ouiRow(c, "הצגת שניות", on = Prefs.showSeconds(this)) {
        Prefs.setShowSeconds(this, !Prefs.showSeconds(this)); refresh(); updateClock(); rebuild() }
    ouiDivider(c)
    ouiRow(c, "נקודתיים מהבהבות", on = Prefs.blinkColon(this)) {
        Prefs.setBlinkColon(this, !Prefs.blinkColon(this)); updateClock(); rebuild() }
    ouiAdd(body, c)
    ouiNote(body, "השינוי נראה מיד בשעון במסך הראשי")
}


// ---- רכיבים ----

/** הזנת שם לברכה: "ערב טוב (שם)" */
internal fun MainActivity.showGreetingName(done: () -> Unit) {
    val field = android.widget.EditText(this)
    ouiPage("ברכה", onClose = { Prefs.setGreetingName(this, field.text.toString()); updateClock(); done() }) { body, _ ->
        val c = ouiCard()
        c.addView(text("שם", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), 0) })
        field.apply {
            setText(Prefs.greetingName(this@showGreetingName)); setSelection(text.length)
            hint = "לדוגמה: טל"; setHintTextColor(C.MUTED); setTextColor(C.TEXT); textSize = 17f
            filters = arrayOf(android.text.InputFilter.LengthFilter(20))
            isSingleLine = true
            background = GradientDrawable().apply { setColor(if (C.LIGHT) Color.parseColor("#E3E3E8") else Color.parseColor("#2C2C2E")); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            // תצוגה מקדימה מתעדכנת בזמן ההקלדה
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    (tag as? TextView)?.text = TimeFormat.greeting(System.currentTimeMillis()) + s.toString().trim().let { if (it.isEmpty()) "" else " $it" }
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        }
        (field.parent as? ViewGroup)?.removeView(field)
        c.addView(field, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(18), dp(10), dp(18), dp(14)) })
        ouiAdd(body, c)
        val prev = text(TimeFormat.greeting(System.currentTimeMillis()) + Prefs.greetingName(this).let { if (it.isEmpty()) "" else " $it" },
            20f, C.TEXT).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(4)) }
        field.tag = prev
        body.addView(prev)
        ouiNote(body, "כך תופיע הברכה בכרטיס השעון. בלי שם – רק הברכה")
    }
}

internal fun MainActivity.themeTimeLabel(): String {
    if (Prefs.themeTimeMode(this) == 0) return "משקיעה עד זריחה"
    fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    return "${hm(Prefs.darkFrom(this))} – ${hm(Prefs.darkTo(this))}"
}

/** "לפי זמן": משקיעה עד זריחה, או לוח זמנים מותאם (כמו בסמסונג) */
internal fun MainActivity.chooseThemeTime(done: () -> Unit) = ouiPage("לפי זמן", onClose = done) { body, rebuild ->
    fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    val c = ouiCard()
    val tm = Prefs.themeTimeMode(this)
    ouiRow(c, "משקיעה עד זריחה", radio = tm == 0) { Prefs.setThemeTimeMode(this, 0); rebuild() }
    ouiDivider(c)
    ouiRow(c, "לוח זמנים מותאם", radio = tm == 1) { Prefs.setThemeTimeMode(this, 1); rebuild() }
    if (tm == 1) {
        ouiDivider(c)
        ouiRow(c, "שעת התחלה", sub = hm(Prefs.darkFrom(this))) {
            pickTime("שעת התחלה", Prefs.darkFrom(this)) { Prefs.setDarkFrom(this, it); rebuild() } }
        ouiDivider(c)
        ouiRow(c, "שעת סיום", sub = hm(Prefs.darkTo(this))) {
            pickTime("שעת סיום", Prefs.darkTo(this)) { Prefs.setDarkTo(this, it); rebuild() } }
    }
    ouiAdd(body, c)
    ouiNote(body, if (tm == 0) "חשוך מהשקיעה ועד הזריחה, לפי המיקום שלך" else "חשוך משעת ההתחלה ועד שעת הסיום")
}

/** פלטת צבעים: הגוון של הערכה החשוכה ושל הבהירה - כמה צבעים לכל אחת, עם עיגול צבע ליד כל שם */
internal fun MainActivity.choosePalette(done: () -> Unit) = ouiPage("ערכות נושא", onClose = done) { body, rebuild ->
    // עיגול: הרקע של הפלטה מבחוץ והכרטיס בפנים
    fun swatch(p: Palette) = android.widget.FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.c(p.bg)); setStroke(dp(1), p.c(p.sepv)) }
        addView(View(this@choosePalette).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.c(p.chip)) } },
            android.widget.FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
    }
    // הערכה מתחלפת מיד: הצבעים החדשים + רקע העמוד עצמו (שאר האפליקציה - ביציאה מעמוד התצוגה)
    fun recolor() {
        C.apply(Prefs.darkTheme(this), Palettes.current(this, Prefs.darkTheme(this)))
        var v: View? = body
        while (v != null) { if (v !== body) v.setBackgroundColor(C.BG); v = v.parent as? View }
        // שורת המצב והניווט של העמוד הזה (העמוד הפתוח האחרון)
        pages.lastOrNull()?.window?.let { w ->
            @Suppress("DEPRECATION")
            w.statusBarColor = C.BG
            if (Build.VERSION.SDK_INT >= 30) {
                val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                w.insetsController?.setSystemBarsAppearance(if (C.LIGHT) light else 0, light)
            }
        }
    }
    fun section(title: String, list: List<Palette>, sel: String, top: Int, set: (String) -> Unit) {
        body.addView(text(title, 15f, C.MUTED).apply { setPadding(dp(30), dp(top), dp(30), dp(6)) })
        val c = ouiCard()
        list.forEachIndexed { i, p ->
            if (i > 0) ouiDivider(c)
            ouiRow(c, p.name, radio = p.id == sel, lead = swatch(p)) { set(p.id); recolor(); rebuild() }
        }
        ouiAdd(body, c)
    }
    section("בחשוך", Palettes.DARK, Prefs.darkPalette(this), 6) { Prefs.setDarkPalette(this, it) }
    section("בבהיר", Palettes.LIGHT, Prefs.lightPalette(this), 14) { Prefs.setLightPalette(this, it) }
    ouiNote(body, "הערכה מתחלפת מיד. המסך הראשי מתחלף כשחוזרים אליו")
}

internal val PROT_BG = listOf("ללא", "ירוק כהה", "מעבר ירוק לשחור", "פס ירוק למעלה", "זוהר מבפנים", "מעבר מלמטה", "פס ירוק למטה")
internal val PROT_FRAME = listOf("ללא", "מסגרת דקה", "מסגרת עבה", "זוהר ירוק", "מסגרת בינונית")

internal val PROT_ICON_NAMES = listOf("מגן עם וי", "מגן מתאר", "מגן מלא", "עיגול וי", "בית מוגן", "פעמון וי")

/** כרטיס ההגנה: תפריט - עיצוב, אייקון */
internal fun MainActivity.chooseProtCard(done: () -> Unit) {
    // תמונה של אזור התרעה | זמן התגוננות - פעם אחת בפתיחה, כדי שלא תיעלם בכל בחירה
    val areaBmp = if (clockArea.width > 0 && clockArea.height > 0)
        android.graphics.Bitmap.createBitmap(clockArea.width, clockArea.height, android.graphics.Bitmap.Config.ARGB_8888)
            .also { clockArea.draw(android.graphics.Canvas(it)) } else null
    val greet = if (greeting.visibility == View.VISIBLE) greeting.text.toString() else null
    val cardW = card1.width
    ouiPage("כרטיס ההגנה", onClose = done) { body, rebuild ->
        val c = ouiCard()
        ouiRow(c, "עיצוב", sub = "רקע: ${PROT_BG[Prefs.protBg(this)]} · מסגרת: ${PROT_FRAME[Prefs.protFrame(this)]}") {
            chooseProtDesign(areaBmp, greet, cardW) { rebuild() } }
        ouiDivider(c)
        ouiRow(c, "אייקון", sub = PROT_ICON_NAMES[Prefs.protIcon(this)]) { chooseProtIcon(areaBmp, greet, cardW) { rebuild() } }
        ouiDivider(c)
        ouiRow(c, "כיתוב", sub = Prefs.protLabel(this)) { chooseProtText(areaBmp, greet, cardW) { rebuild() } }
        ouiAdd(body, c)
        protLayoutCard(body, rebuild)
    }
}

/** תצוגה מקדימה: אותו כרטיס כמו במסך הראשי - ברכה, אייקון, "אתה מוגן", אזור התרעה | זמן התגוננות, בעיצוב שנבחר */
private fun MainActivity.protPreview(body: LinearLayout, areaBmp: android.graphics.Bitmap?, greet: String?, cardW: Int): TextView {
    lateinit var label: TextView
    val pv = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(18), dp(20), dp(18), dp(18))
        if (greet != null)
            addView(text(greet, greeting.textSize / resources.displayMetrics.scaledDensity, C.TEXT).apply {
                gravity = greeting.gravity; typeface = greeting.typeface }, LinearLayout.LayoutParams(-1, -2))
        addView(android.widget.FrameLayout(this@protPreview).apply {
            addView(android.widget.ImageView(this@protPreview).apply { setImageResource(PROT_ICONS[Prefs.protIcon(this@protPreview)]) },
                android.widget.FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { topMargin = dp(10); bottomMargin = dp(6) })
        label = text(Prefs.protLabel(this@protPreview), circleLabel.textSize / resources.displayMetrics.scaledDensity, C.TEXT, bold = true).apply {
            gravity = Gravity.CENTER; typeface = circleLabel.typeface }
        addView(label, LinearLayout.LayoutParams(-1, -2))
        if (areaBmp != null)
            addView(android.widget.ImageView(this@protPreview).apply { setImageBitmap(areaBmp); adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }
    // אותו רוחב כמו הכרטיס במסך הראשי
    val side = if (cardW > 0) ((resources.displayMetrics.widthPixels - cardW) / 2) else dp(PAGE_SIDE + 4)
    body.addView(pv, LinearLayout.LayoutParams(-1, -2).apply { setMargins(side, dp(10), side, dp(18)) })
    protStyle(pv, Prefs.protBg(this), Prefs.protFrame(this), 0, 26)
    return label
}

/** כרטיס ההגנה ← כיתוב: תצוגה מקדימה + 4 נוסחים או טקסט משלו */
private fun MainActivity.chooseProtText(areaBmp: android.graphics.Bitmap?, greet: String?, cardW: Int, done: () -> Unit) {
    ouiPage("כיתוב", onClose = done, pin = 1) { body, rebuild ->
        val label = protPreview(body, areaBmp, greet, cardW)
        val sel = Prefs.protText(this)
        val c = ouiCard()
        listOf("אתה מוגן", "מוגן", "הכל תקין", "ההתרעות פעילות", "טקסט משלך").forEachIndexed { i, n ->
            if (i > 0) ouiDivider(c)
            ouiRow(c, n, radio = i == sel) { Prefs.setProtText(this, i); refreshProtCard(); rebuild() }
        }
        ouiAdd(body, c)
        // טקסט משלו: שדה כתיבה, מתעדכן תוך כדי הקלדה
        if (sel == 4) {
            val f = ouiCard()
            f.addView(android.widget.EditText(this).apply {
                hint = "למשל: שקט ובטוח"
                setText(Prefs.protTextOwn(this@chooseProtText)); textSize = 16f; isSingleLine = true
                setTextColor(C.TEXT); setHintTextColor(C.MUTED)
                background = GradientDrawable().apply { setColor(C.CARD2); cornerRadius = dp(18).toFloat() }
                setPadding(dp(16), dp(12), dp(16), dp(12))
                filters = arrayOf(android.text.InputFilter.LengthFilter(24))
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        Prefs.setProtTextOwn(this@chooseProtText, s?.toString() ?: "")
                        label.text = Prefs.protLabel(this@chooseProtText); refreshProtCard()
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(14), dp(14), dp(14)) })
            ouiAdd(body, f)
        }
        ouiNote(body, "משנה רק את המצב \"מוגן\". \"אין רשת\" ו\"לא מוגן\" נשארים כמו שהם")
    }
}

/** כרטיס ההגנה ← אייקון: תצוגה מקדימה + 6 אייקונים */
private fun MainActivity.chooseProtIcon(areaBmp: android.graphics.Bitmap?, greet: String?, cardW: Int, done: () -> Unit) {
    ouiPage("אייקון", onClose = done, pin = 1) { body, rebuild ->
        protPreview(body, areaBmp, greet, cardW)
        val sel = Prefs.protIcon(this)
        val c = ouiCard().apply { setPadding(dp(8), dp(10), dp(8), dp(10)) }
        var line: LinearLayout? = null
        PROT_ICONS.forEachIndexed { i, res ->
            if (i % 3 == 0) line = LinearLayout(this).also { c.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            line!!.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
                if (i == sel) background = GradientDrawable().apply { cornerRadius = dp(14).toFloat()
                    setColor((OUI_BLUE and 0xFFFFFF) or 0x2E000000); setStroke(dp(1).coerceAtLeast(2), OUI_BLUE) }
                addView(android.widget.ImageView(this@chooseProtIcon).apply { setImageResource(res) }, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(text(PROT_ICON_NAMES[i], 12f, C.TEXT).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
                setOnClickListener { Prefs.setProtIcon(this@chooseProtIcon, i); refreshProtCard(); rebuild() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        ouiAdd(body, c)
        ouiNote(body, "באין רשת ובלא מוגן נשאר האייקון הרגיל")
    }
}

/** כרטיס ההגנה ← עיצוב: בוחרים רקע ומסגרת, עם תצוגה מקדימה */
private fun MainActivity.chooseProtDesign(areaBmp: android.graphics.Bitmap?, greet: String?, cardW: Int, done: () -> Unit) {
    ouiPage("עיצוב", onClose = done, pin = 1) { body, rebuild ->
        val bgSel = Prefs.protBg(this); val frSel = Prefs.protFrame(this)
        protPreview(body, areaBmp, greet, cardW)
        // ריבוע קטן בסגנון - ליד כל אפשרות
        fun sw(b: Int, f: Int) = View(this).also { protStyle(it, b, f, 0, 7, glow = false) }
        body.addView(text("רקע", 15f, C.MUTED).apply { setPadding(dp(30), dp(6), dp(30), dp(6)) })
        val cb = ouiCard()
        PROT_BG.forEachIndexed { i, n ->
            if (i > 0) ouiDivider(cb)
            ouiRow(cb, n, radio = i == bgSel, lead = sw(i, 0)) { Prefs.setProtBg(this, i); refreshProtCard(); rebuild() }
        }
        ouiAdd(body, cb)
        body.addView(text("מסגרת", 15f, C.MUTED).apply { setPadding(dp(30), dp(14), dp(30), dp(6)) })
        val cf = ouiCard()
        PROT_FRAME.forEachIndexed { i, n ->
            if (i > 0) ouiDivider(cf)
            ouiRow(cf, n, radio = i == frSel, lead = sw(0, i)) { Prefs.setProtFrame(this, i); refreshProtCard(); rebuild() }
        }
        ouiAdd(body, cf)
        ouiNote(body, "כשאין רשת הכרטיס כתום, וכשההתרעות כבויות – אדום")
    }
}

/** כרטיס ההגנה ← מיקום במסך הראשי: למעלה או מתחת לשעון (תצוגה מקדימה קטנה של המסך) */
private fun MainActivity.protLayoutCard(body: LinearLayout, rebuild: () -> Unit) {
        body.addView(text("מיקום במסך הראשי", 15f, C.MUTED).apply { setPadding(dp(30), dp(14), dp(30), dp(6)) })
        val lay = ouiCard()
        val pickL = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(20), dp(10), dp(16)) }
        fun layoutPreview(clockFirst: Boolean, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val box = LinearLayout(this@protLayoutCard).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply { setColor(Color.parseColor("#2B2B2E")); cornerRadius = dp(12).toFloat() }
                // כרטיס השעון: שלושה פסים · כרטיס ההגנה: מגן ירוק ושני פסים
                fun clockBox() = LinearLayout(this@protLayoutCard).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    background = GradientDrawable().apply { setColor(Color.parseColor("#3E3E42")); cornerRadius = dp(6).toFloat() }
                    listOf(30, 18, 24).forEach { w -> addView(View(this@protLayoutCard).apply {
                        background = GradientDrawable().apply { setColor(Color.parseColor("#77777C")); cornerRadius = dp(2).toFloat() }
                    }, LinearLayout.LayoutParams(dp(w), dp(4)).apply { setMargins(0, dp(2), 0, dp(2)) }) }
                }
                fun shieldBox() = LinearLayout(this@protLayoutCard).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    background = GradientDrawable().apply { setColor(Color.parseColor("#3E3E42")); cornerRadius = dp(6).toFloat() }
                    addView(android.widget.ImageView(this@protLayoutCard).apply { setImageResource(R.drawable.ic_shield_green) },
                        LinearLayout.LayoutParams(dp(14), dp(14)))
                    listOf(26, 34).forEach { w -> addView(View(this@protLayoutCard).apply {
                        background = GradientDrawable().apply { setColor(Color.parseColor("#77777C")); cornerRadius = dp(2).toFloat() }
                    }, LinearLayout.LayoutParams(dp(w), dp(4)).apply { setMargins(0, dp(3), 0, 0) }) }
                }
                val a = if (clockFirst) clockBox() else shieldBox()
                val b = if (clockFirst) shieldBox() else clockBox()
                addView(a, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(5) })
                addView(b, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(5) })
                addView(View(this@protLayoutCard).apply {
                    background = GradientDrawable().apply { setColor(Color.parseColor("#3E3E42")); cornerRadius = dp(6).toFloat() }
                }, LinearLayout.LayoutParams(-1, dp(22)))
            }
            addView(box, LinearLayout.LayoutParams(dp(76), dp(150)))
            val v = if (clockFirst) 1 else 0
            addLabelRadio(this, label, Prefs.mainLayout(this@protLayoutCard) == v)
            setOnClickListener { Prefs.setMainLayout(this@protLayoutCard, v); applyMainLayout(); rebuild() }
        }
        // כרטיס ההגנה "למעלה" מימין, "מתחת לשעון" משמאל
        pickL.addView(layoutPreview(false, "למעלה"), LinearLayout.LayoutParams(0, -2, 1f))
        pickL.addView(layoutPreview(true, "מתחת לשעון"), LinearLayout.LayoutParams(0, -2, 1f))
        lay.addView(pickL)
        ouiAdd(body, lay)
        ouiNote(body, "הברכה מוצגת בראש הכרטיס העליון")
}

/** תקציר כרטיס השעון: מה מופעל */
internal fun MainActivity.clockCardSummary(): String = listOf(
    if (Prefs.showSeconds(this)) "שניות" else "",
    if (Prefs.showDate(this)) "תאריך" else "",
    if (Prefs.showWeather(this)) "מזג אוויר" else "",
    if (Prefs.showGreeting(this)) "ברכה" else "").filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "שעה בלבד" }

/** כרטיס השעון: תצוגה מקדימה קבועה למעלה + שעון, תאריך, מזג אוויר וברכה */
internal fun MainActivity.chooseClockCard(done: () -> Unit) {
    val cardW = clockCard.width
    ouiPage("כרטיס השעון", onClose = done, pin = 1) { body, rebuild ->
        // תצוגה מקדימה: תמונה של כרטיס השעון במסך הראשי, אחרי שהוא מתעדכן
        val pv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val side = if (cardW > 0) ((resources.displayMetrics.widthPixels - cardW) / 2) else dp(PAGE_SIDE + 4)
        body.addView(pv, LinearLayout.LayoutParams(-1, -2).apply { setMargins(side, dp(10), side, dp(18)) })
        fun snap() {
            pv.removeAllViews()
            // הברכה נמצאת בכרטיס העליון - אם הוא כרטיס ההגנה, מוסיפים אותה מעל
            val gIn = greeting.parent === clockCard
            val img = if (clockCard.width > 0 && clockCard.height > 0)
                android.graphics.Bitmap.createBitmap(clockCard.width, clockCard.height, android.graphics.Bitmap.Config.ARGB_8888)
                    .also { clockCard.draw(android.graphics.Canvas(it)) } else null
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = graphite(26) }
            if (!gIn && greeting.visibility == View.VISIBLE)
                box.addView(text(greeting.text.toString(), greeting.textSize / resources.displayMetrics.scaledDensity, C.TEXT).apply {
                    gravity = Gravity.CENTER; typeface = greeting.typeface; setPadding(0, dp(16), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
            if (img != null) box.addView(android.widget.ImageView(this).apply { setImageBitmap(img); adjustViewBounds = true },
                LinearLayout.LayoutParams(-1, -2))
            pv.addView(box, LinearLayout.LayoutParams(-1, -2))
        }
        snap(); clockCard.post { clockCard.post { snap() } }
        val c = ouiCard()
        ouiRow(c, "שעון", sub = listOf(if (Prefs.showSeconds(this)) "שניות" else "", if (Prefs.blinkColon(this)) "נקודתיים מהבהבות" else "")
            .filter { it.isNotEmpty() }.joinToString(" · ")) { showClockPage() }
        ouiDivider(c)
        ouiRow(c, "תאריך", sub = TimeFormat.dayOptions()[Prefs.dayStyle(this)], on = Prefs.showDate(this),
            onToggle = { Prefs.setShowDate(this, !Prefs.showDate(this)); updateClock(); refresh(); rebuild() }) { chooseDayStyle { rebuild() } }
        ouiDivider(c)
        ouiRow(c, "מזג אוויר", sub = "עדכון " + weatherLabel(Prefs.weatherMinutes(this)), on = Prefs.showWeather(this),
            onToggle = { Prefs.setShowWeather(this, !Prefs.showWeather(this)); updateWeather(force = true); rebuild() }) { chooseWeatherInterval { rebuild() } }
        ouiDivider(c)
        ouiRow(c, "ברכה", sub = Prefs.greetingName(this), on = Prefs.showGreeting(this),
            onToggle = { Prefs.setShowGreeting(this, !Prefs.showGreeting(this)); updateClock(); rebuild() }) { showGreetingName { rebuild() } }
        ouiAdd(body, c)
    }
}

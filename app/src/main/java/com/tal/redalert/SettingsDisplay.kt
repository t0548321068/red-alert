package com.tal.redalert

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tal.redalert.MainActivity.C
import com.tal.redalert.MainActivity.Row
import com.tal.redalert.MainActivity.Perm
import com.tal.redalert.MainActivity.SameHeight

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
    C.apply(Prefs.darkTheme(this))
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
    /** שורה: שם, מצב בכחול, ומתג (אם יש) */
    /** שורה: שם + מצב בכחול + מתג. open = לחיצה על השם פותחת הגדרה נוספת (והמתג לבד מדליק/מכבה) */
    fun row(c: LinearLayout, title: String, sub: String = "", on: Boolean? = null, sep: Boolean = true,
            open: (() -> Unit)? = null, click: () -> Unit) {
        // גובה קבוע כמו בסמסונג: שורה רגילה 48, שורה עם מצב מתחת 56 (המתג לא מגביה)
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
        c.addView(r, LinearLayout.LayoutParams(-1, dp(if (sub.isEmpty()) 48 else 56)))
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
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val box = LinearLayout(this@showDisplay).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply { setColor(Color.parseColor(if (light) "#D9D9DC" else "#2B2B2E")); cornerRadius = dp(12).toFloat() }
                listOf(12, 26, 40, 26).forEach { h ->
                    addView(View(this@showDisplay).apply {
                        background = GradientDrawable().apply { setColor(Color.parseColor(if (light) "#BDBDC2" else "#3E3E42")); cornerRadius = dp(6).toFloat() }
                    }, LinearLayout.LayoutParams(-1, dp(h)).apply { bottomMargin = dp(5) })
                }
            }
            addView(box, LinearLayout.LayoutParams(dp(76), dp(150)))
            addLabelRadio(this, label, sel)
            setOnClickListener { setTheme(d, value) { rebuild() } }
        }
        val sys = mode == 2
        val byTime = mode == 3
        pick.addView(preview(false, "חשוך", mode == 0, 0), LinearLayout.LayoutParams(0, -2, 1f))
        pick.addView(preview(true, "בהיר", mode == 1, 1), LinearLayout.LayoutParams(0, -2, 1f))
        theme.addView(pick)
        divider(theme)
        row(theme, "לפי המערכת", on = sys) { setTheme(d, if (sys) 0 else 2) { rebuild() } }
        row(theme, "לפי זמן", themeTimeLabel(), on = byTime, sep = false,
            open = { chooseThemeTime { applyThemeNow(d) { rebuild() } } }) { setTheme(d, if (byTime) 0 else 3) { rebuild() } }
        addCard(theme)
        note("לפי המערכת – בהיר או חשוך לפי הגדרת הטלפון\nלפי זמן – חשוך בלילה ובהיר ביום")
        // שורת החיוויים במסך הראשי
        val ind = card()
        val sm = Prefs.statusMode(this)
        row(ind, "שורת חיוויים", statusModeLabel(), on = sm != Prefs.STATUS_HIDDEN, sep = false,
            open = { chooseStatusMode { rebuild() } }) {
            Prefs.setStatusMode(this, if (sm == Prefs.STATUS_HIDDEN) Prefs.STATUS_ALWAYS else Prefs.STATUS_HIDDEN)
            applyStatusRow(); rebuild() }
        addCard(ind)
        note("מוגן · צלילים · רשת · מיקום · חיבור · גרסה – מעל השעון במסך הראשי")
        // שעון ותאריך, מזג אוויר וברכה - בכרטיס אחד
        val more = card()
        row(more, "שעון", listOf(if (Prefs.showSeconds(this)) "שניות" else "", if (Prefs.blinkColon(this)) "נקודתיים מהבהבות" else "")
            .filter { it.isNotEmpty() }.joinToString(" · ")) { showClockPage() }
        divider(more)
        row(more, "תאריך", TimeFormat.dayOptions()[Prefs.dayStyle(this)], on = Prefs.showDate(this),
            open = { chooseDayStyle { rebuild() } }) {
            Prefs.setShowDate(this, !Prefs.showDate(this)); updateClock(); refresh(); rebuild() }
        divider(more)
        row(more, "מזג אוויר", "עדכון " + weatherLabel(Prefs.weatherMinutes(this)), on = Prefs.showWeather(this),
            open = { chooseWeatherInterval { rebuild() } }) {
            Prefs.setShowWeather(this, !Prefs.showWeather(this)); updateWeather(force = true); rebuild() }
        divider(more)
        // ברכה: מתג נפרד (עם קו מפריד), ולחיצה על השם פותחת הזנת שם ("ערב טוב טל")
        row(more, "ברכה", Prefs.greetingName(this), on = Prefs.showGreeting(this), open = { showGreetingName { rebuild() } }) {
            Prefs.setShowGreeting(this, !Prefs.showGreeting(this)); updateClock(); rebuild() }
        addCard(more)
        note("מזג אוויר וברכה בכרטיס השעון במסך הראשי")
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
    rebuild()
    // יציאה מהעמוד: אם ערכת הצבעים השתנתה - טוענים מחדש וחוזרים להגדרות
    track(d) { if (Prefs.themeMode(this) != themeAtOpen || Prefs.darkTheme(this) != darkAtOpen) { MainActivity.reopenDisplay = true; recreate() } }
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

/** עמוד מזג אוויר: הצגה + תדירות עדכון */
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

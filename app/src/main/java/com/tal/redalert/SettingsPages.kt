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

// עמודי ההגדרות: הגנה, התרעות, צלילים, תצוגה, שבת, בדיקה, שעות שקט, הקראה, עדכונים, אודות, יצירת קשר
// (חלק מהמסך הראשי - MainActivity; המצב עצמו נשמר שם)

/** הגדרות בסגנון One UI: עמוד מלא, כותרת גדולה, כל הקבוצות פתוחות */
internal fun MainActivity.showSettings() {
    val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar)
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val page = ScrollView(this).apply {
        setBackgroundColor(C.BG)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        addView(body)
    }
    page.setOnApplyWindowInsetsListener { v, insets ->
        @Suppress("DEPRECATION")
        v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        insets
    }
    val cats = listOf(
        // הפעלה / כיבוי של האפליקציה - כמו הכפתור בשורת החיווי
        "צופר חירום" to { listOf(Row("⏻", "הגנה", { protectionLabel() },
            on = { Prefs.enabled(this) }, toggle = { toggle() }) { showProtection() }) },
        "תצוגה" to { listOf(Row("🎨", "תצוגה", { "ערכת צבעים · שעון · תאריך · מזג אוויר" }) { showDisplay() }) },
        "התרעות" to { listOf(
            Row("🔔", "הגדרות התרעות", { "סוג תצוגה · מיקום · אזורים נוספים · צלילים ורטט · הקראה · בדיקה" }) { showAlertSettings() }) },
        "הרשאות" to { phoneRows() },
        // עדכונים ואודות - כל אחד בכרטיס נפרד
        "עדכונים" to { listOf(Row("⬆️", "בדיקת עדכונים", { "v" + Updater.versionLabel(this) }) { showUpdates() }) },
        "אודות" to { listOf(Row("ℹ️", "אודות", { "" }) { showAbout() },
            Row("✉️", "יצירת קשר", { "דיווח על בעיה למפתח" }) { showContact() }) }
    )
    fun rebuild() {
        body.removeAllViews()
        // כותרת כמו בשאר מסכי ההגדרות
        body.addView(text("הגדרות", 20f, C.TEXT, bold = true).apply { setPadding(dp(24), dp(14), dp(24), dp(14)) })
        cats.forEach { (_, rows) ->
            // בלי כותרת מעל כל כרטיס - רק רווח בין הכרטיסים
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = graphite(28)
                clipToOutline = true
            }
            val list = rows()
            list.forEachIndexed { i, r ->
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(C.DIV) },
                    LinearLayout.LayoutParams(-1, dp(1)))
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setOnClickListener { r.action(); rebuild() }
                }
                // אייקון פשוט בקו לבן בתוך עיגול צבעוני
                row.addView(FrameLayout(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(iconColor(r.icon)) }
                    val res = setIcon(r.icon)
                    if (res != 0) addView(android.widget.ImageView(this@showSettings).apply { setImageResource(res) },
                        FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                    else addView(text(r.icon, 16f, Color.WHITE).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(text(r.title, 16f, C.TEXT))
                val v = r.value()
                if (v.isNotEmpty()) texts.addView(text(v, 12f, Color.parseColor("#5AA9FF")))
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                r.on?.let { isOn ->
                    val on = isOn()
                    // מתג נפרד: קו מפריד, והמתג לבד מדליק/מכבה; לחיצה על השורה פותחת את המסך
                    val t = r.toggle
                    if (t != null) switchZone(row, on, dp(16)) { t(); rebuild() }
                    else row.addView(OuiSwitch(this, on, C.LIGHT).apply { isClickable = false })
                }
                card.addView(row)
            }
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), 0, dp(10), dp(12)) })
        }
        body.addView(View(this), LinearLayout.LayoutParams(-1, dp(30)))
    }
    rebuild()
    d.setContentView(page)
    d.window?.setLayout(-1, -1)
    @Suppress("DEPRECATION")
    d.window?.statusBarColor = C.BG
    edgeToEdge(d)
    // חוזרים מחלון של הגדרה - מעדכנים את המצבים
    refreshOnReturn(d) { rebuild() }
    track(d)
    d.show()
}

internal fun MainActivity.onOff(b: Boolean) = if (b) "פעיל" else "כבוי"

internal fun MainActivity.protectionLabel(): String = when {
    !Prefs.enabled(this) -> "כבוי – לא מקבל התרעות"
    Prefs.scheduleOffNow(this) -> "כבוי עכשיו לפי תזמון"
    Prefs.schedOn(this) -> "פעיל – מוגן · לפי תזמון"
    else -> "פעיל – מוגן"
}

/** מסך הגנה: הפעלה/כיבוי, ותזמון - באילו ימים ושעות האפליקציה פעילה */
internal fun MainActivity.showProtection() = ouiPage("הגנה") { body, rebuild ->
    val main = ouiCard()
    ouiRow(main, "הגנה", sub = protectionLabel(), on = Prefs.enabled(this)) { toggle(); rebuild() }
    ouiAdd(body, main)

    val sc = ouiCard()
    val on = Prefs.schedOn(this)
    ouiRow(sc, "תזמון", sub = "פעיל רק בימים ובשעות שתבחר", on = on) { Prefs.setSchedOn(this, !on); refresh(); rebuild() }
    if (on) {
        // לכל יום: מתג + השעות שלו. לחיצה על השורה - בחירת שעת התחלה ואז סיום
        TimeFormat.NAMES.forEachIndexed { d, name ->
            ouiDivider(sc)
            val dOn = Prefs.schedDayOn(this, d)
            val f = Prefs.schedDayFrom(this, d); val t = Prefs.schedDayTo(this, d)
            val range = when { !dOn -> "כבוי כל היום"; f == t -> "כל היום"; else -> hhmm(f) + "–" + hhmm(t) + if (t < f) " (עד למחרת)" else "" }
            ouiRow(sc, name, sub = range, on = dOn,
                onToggle = { Prefs.setSchedDay(this, d, !dOn, f, t); refresh(); rebuild() }) {
                pickTime("$name – פעיל משעה", f) { nf ->
                    pickTime("$name – פעיל עד שעה", t) { nt -> Prefs.setSchedDay(this, d, true, nf, nt); refresh(); rebuild() }
                }
            }
        }
    }
    ouiAdd(body, sc)
    ouiNote(body, if (on) "מחוץ לשעות האלה האפליקציה לא מקבלת התרעות. לחיצה על יום – שינוי השעות. סיום לפני התחלה = עד למחרת"
                  else "כשהתזמון כבוי - ההגנה פעילה כל הזמן")
}

/** עמוד התרעות: כל ההגדרות של ההתרעה במקום אחד */
internal fun MainActivity.showAlertSettings() = ouiPage("הגדרות התרעות") { body, rebuild ->
    // סוג תצוגת התרעה: הדמיה קטנה של כל סוג + בחירה (הועבר מעמוד תצוגה)
    val style = ouiCard()
    style.addView(text("סוג תצוגת התרעה", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
    val opts = LinearLayout(this).apply { setPadding(dp(6), dp(10), dp(6), dp(16)) }
    listOf("מסך מלא", "פופ-אפ", "כרטיס", "ממוזער").forEachIndexed { i, label ->
        val sel = Prefs.alertStyle(this) == i
        opts.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(alertStylePreview(i), LinearLayout.LayoutParams(dp(58), dp(116)))
            addLabelRadio(this, label, sel)
            setOnClickListener { Prefs.setAlertStyle(this@showAlertSettings, i); rebuild() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    style.addView(opts)
    ouiDivider(style)
    ouiRow(style, "מפה במסך ההתרעה", on = Prefs.alertMap(this)) {
        Prefs.setAlertMap(this, !Prefs.alertMap(this)); rebuild() }
    ouiAdd(body, style)
    ouiNote(body, "המפה מוצגת במסך מלא ובפופ-אפ")

    val c = ouiCard()
    // מתג = הדלקה/כיבוי, לחיצה על השם = ההגדרות המלאות
    ouiRow(c, "אזור התרעה", sub = onOff(Prefs.nearMe(this)), on = Prefs.nearMe(this)) { toggleNearMe(); rebuild() }
    ouiDivider(c)
    ouiRow(c, "מרחק מקסימלי", sub = Prefs.radiusLabel(Prefs.nearRadiusKm(this))) { showNearRadius() }
    ouiAdd(body, c)

    // אזורים נוספים - שורה אחת שפותחת את חלון האזורים
    val extra = ouiCard()
    ouiRow(extra, "אזורים נוספים") { showExtraAreas() }
    ouiAdd(body, extra)
    // צלילים ורטט (הועבר מההגדרות הראשיות) - כרטיס נפרד מעל הקראה
    val snd = ouiCard()
    ouiRow(snd, "צלילים ורטט", sub = "שעות שקט · נא לא להפריע · מצב שקט · שבת") { showSoundSettings() }
    ouiAdd(body, snd)
    // הקראה - כרטיס נפרד
    val sp = ouiCard()
    ouiRow(sp, "הקראה", sub = onOff(Prefs.speakAlerts(this)), on = Prefs.speakAlerts(this),
        onToggle = { Prefs.setSpeakAlerts(this, !Prefs.speakAlerts(this)); rebuild() }) { showSpeech() }
    ouiAdd(body, sp)
    // בדיקת התראה (עברה מהכפתור שבמסך הראשי)
    val t = ouiCard()
    ouiRow(t, "בדיקת התראה", sub = "מקדימה · ירי · סיום") { chooseTest() }
    ouiAdd(body, t)
}

/** עמוד מרחק מקסימלי: בחירת מרחק, והיישובים שבקרבתי לפי המרחק הזה */
internal fun MainActivity.showNearRadius() = ouiPage("מרחק מקסימלי") { body, rebuild ->
    val r = ouiCard()
    val radii = Prefs.NEAR_RADII
    val cur = radii.indexOfFirst { it == Prefs.nearRadiusKm(this) }.coerceAtLeast(0)
    ouiRow(r, "מרחק", sub = Prefs.radiusLabel(radii[cur]))
    // הכיתוב הכחול מתחת ל"מרחק" - מתעדכן תוך כדי גרירה
    val distLabel = ((r.getChildAt(r.childCount - 1) as LinearLayout).getChildAt(0) as LinearLayout).getChildAt(1) as TextView
    // פס בחירה בשלבים באותו כרטיס, מתחת לשורת "מרחק": קרוב מימין, רחוק משמאל
    val bar = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(18), dp(10))
    }
    bar.addView(StepSlider(this, radii.size, cur, C.LIGHT) { i ->
        distLabel.text = Prefs.radiusLabel(radii[i])
        val km = radii[i]
        Prefs.setNearRadiusKm(this, km)
        // חישוב מחדש מיד לפי המיקום האחרון (אם אין - מיקום חדש)
        val last = Prefs.lastNearLoc(this)
        if (last != null) Thread {
            try { Prefs.setNearbyAreas(this, AreaData.nearby(this, last.first, last.second)) } catch (_: Exception) { }
            runOnUiThread { rebuild() }
        }.start() else { refreshNearby(); rebuild() }
    }.apply { onMove = { i -> distLabel.text = Prefs.radiusLabel(radii[i]) } },
        LinearLayout.LayoutParams(0, -2, 1f))   // מימין: באזורך · משמאל: 10 ק"מ
    r.addView(bar)
    ouiAdd(body, r)
    ouiNote(body, "התרעות יגיעו לאזורים שבמרחק הזה מהמיקום שלך, בנוסף לאזורים שהוספת")

    val near = ouiCard()
    near.addView(text("יישובים בקרבתי", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
    val list = Prefs.nearbyAreas(this).filterNot { AreaData.isNational(it) }
    if (!Prefs.nearMe(this)) ouiRow(near, "אזור התרעה כבוי")
    else if (list.isEmpty()) ouiRow(near, "עוד לא זוהה מיקום")
    else list.forEachIndexed { i, a -> if (i > 0) ouiDivider(near); ouiRow(near, a) }
    ouiAdd(body, near)
}

/** עמוד צלילים ורטט: צלילים, רטט, שעות שקט, נא לא להפריע, מצב שקט ומצב שבת */
internal fun MainActivity.muteLabel(): String {
    val u = Prefs.muteUntil(this)
    return when {
        u <= System.currentTimeMillis() -> "כבוי"
        u == Long.MAX_VALUE -> "מושתק עד שתבטל"
        else -> "מושתק עד " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(u))
    }
}

/** השתקה זמנית: בחירת משך */
internal fun MainActivity.chooseMute(done: () -> Unit) = ouiPage("השתקה זמנית", onClose = done) { body, rebuild ->
    val c = ouiCard()
    val opts = listOf("כבוי" to 0L, "30 דקות" to 30L, "שעה" to 60L, "שעתיים" to 120L, "4 שעות" to 240L, "8 שעות" to 480L, "עד שאבטל" to -1L)
    val u = Prefs.muteUntil(this); val active = u > System.currentTimeMillis()
    opts.forEachIndexed { i, (label, min) ->
        if (i > 0) ouiDivider(c)
        val sel = if (min == 0L) !active else if (min == -1L) u == Long.MAX_VALUE else false
        ouiRow(c, label, radio = sel) {
            Prefs.setMuteUntil(this, when (min) { 0L -> 0L; -1L -> Long.MAX_VALUE
                else -> System.currentTimeMillis() + min * 60_000 })
            refresh(); rebuild()
        }
    }
    ouiAdd(body, c)
    ouiNote(body, if (active) muteLabel() + ". ההתרעות מוצגות כרגיל, בלי צליל, רטט והקראה" else "ההתרעות יוצגו כרגיל, אבל בלי צליל, רטט והקראה")
}

internal fun MainActivity.showSoundSettings() = ouiPage("צלילים ורטט") { body, rebuild ->
    // השתקה זמנית - כרטיס נפרד למעלה
    val m = ouiCard()
    val muted = Prefs.mutedNow(this)
    ouiRow(m, "השתקה זמנית", sub = muteLabel(), on = muted,
        onToggle = { if (muted) Prefs.setMuteUntil(this, 0L) else Prefs.setMuteUntil(this, System.currentTimeMillis() + 60 * 60_000); rebuild() }) {
        chooseMute { rebuild() } }
    ouiAdd(body, m)
    ouiNote(body, "כל ההתרעות מתקבלות בלי צליל ורטט – לזמן שתבחר")
    val c = ouiCard()
    val snd = Prefs.soundsOn(this); val vib = Prefs.vibesOn(this)
    ouiRow(c, "צלילים", sub = if (!snd) "כבוי" else if (AlertService.PHONE_DEFAULTS) "של הטלפון" else "פעיל",
        on = snd, onToggle = { Prefs.setSoundsOn(this, !snd); rebuild() }) {
        openNotificationSettings() }
    ouiDivider(c)
    ouiRow(c, "רטט", sub = if (!vib) "כבוי" else if (AlertService.PHONE_DEFAULTS) "של הטלפון" else "פעיל",
        on = vib, onToggle = { Prefs.setVibesOn(this, !vib); rebuild() }) {
        openNotificationSettings() }
    ouiDivider(c)
    val loud = Prefs.loudSound(this)
    ouiRow(c, "צליל חזק", sub = if (loud) "תמיד בעוצמה מלאה – גם בווליום נמוך, בשעות שקט ובמצב שקט" else "כבוי", on = loud) {
        Prefs.setLoudSound(this, !loud); rebuild() }
    ouiDivider(c)
    val hs = Prefs.speakerWithHeadset(this)
    ouiRow(c, "גם מהרמקול עם אוזניות", sub = if (hs) "כשמחוברות אוזניות – הצופר וההקראה יוצאים גם מהטלפון" else "כבוי", on = hs) {
        Prefs.setSpeakerWithHeadset(this, !hs); rebuild() }
    ouiAdd(body, c)

    val q = ouiCard()
    ouiRow(q, "שעות שקט",
        sub = if (Prefs.quietOn(this)) "${hhmm(Prefs.quietFrom(this))}–${hhmm(Prefs.quietTo(this))}" else "כבוי",
        on = Prefs.quietOn(this),
        onToggle = { Prefs.setQuietOn(this, !Prefs.quietOn(this)); rebuild() }) { showQuietHours() }
    ouiDivider(q)
    // רק הדלקה/כיבוי - ההרשאה עצמה במסך ההרשאות
    ouiRow(q, "עקיפת נא לא להפריע", sub = onOff(Prefs.dndOverride(this)), on = Prefs.dndOverride(this)) {
        Prefs.setDndOverride(this, !Prefs.dndOverride(this)); rebuild() }
    ouiDivider(q)
    ouiRow(q, "עקיפת מצב שקט", sub = if (Prefs.bypassSilent(this)) "צליל גם כשהטלפון על שקט או רטט" else "כבוי",
        on = Prefs.bypassSilent(this)) { Prefs.setBypassSilent(this, !Prefs.bypassSilent(this)); rebuild() }
    ouiDivider(q)
    // מצב שבת: המתג מדליק "אוטומטי" או מכבה. דלוק תמיד - בעמוד מצב שבת
    val sh = Prefs.shabbatMode(this)
    ouiRow(q, "מצב שבת", sub = Shabbat.label(sh), on = sh != Shabbat.OFF,
        onToggle = { Prefs.setShabbatMode(this, if (sh == Shabbat.OFF) Shabbat.AUTO else Shabbat.OFF); rebuild() }) { chooseShabbat() }
    ouiAdd(body, q)
    ouiNote(body, "מצב שבת עוקף את שעות השקט, נא לא להפריע ומצב שקט")
}

internal fun MainActivity.chooseShabbat() = ouiPage("מצב שבת") { body, rebuild ->
    // מיקום עדכני (מהרשת) בכל פתיחה - והזמנים מחושבים מחדש לפיו
    if (System.currentTimeMillis() - shabbatLocAsked > 60_000) {
        shabbatLocAsked = System.currentTimeMillis()
        try { Weather.networkLocation(this) { l -> if (l != null) runOnUiThread { rebuild() } } } catch (_: Exception) { }
    }
    // מצב
    val modes = ouiCard()
    listOf(Shabbat.OFF to "כבוי", Shabbat.ON to "דלוק", Shabbat.AUTO to "אוטומטי")
        .forEachIndexed { i, (mode, label) ->
            if (i > 0) ouiDivider(modes)
            ouiRow(modes, label, sub = if (mode == Shabbat.AUTO) "בשבת ובחג לפי המיקום" else "",
                radio = Prefs.shabbatMode(this) == mode) { Prefs.setShabbatMode(this, mode); rebuild() }
        }
    ouiAdd(body, modes)
    ouiNote(body, "באוטומטי מצב השבת נדלק בכניסה ונכבה ביציאה, בלי לגעת")

    // זמנים
    val fmtDay = java.text.SimpleDateFormat("EEEE, d בMMMM", java.util.Locale("iw"))
    val fmtTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    val w = try { Shabbat.nextWindow(this) } catch (_: Exception) { null }
    val times = ouiCard()
    val now = System.currentTimeMillis()
    times.addView(text(if (w != null && now >= w.first) "השבת עכשיו" else "השבת הקרובה", 16f, C.TEXT)
        .apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
    // מיקום (עיר) שלפיו מחושבים הזמנים - נטען ברקע
    val hasLoc = Shabbat.hasLocation(this)
    ouiRow(times, "מיקום", sub = if (hasLoc) "…" else "מרכז הארץ (אין מיקום)")
    if (hasLoc) {
        val row = times.getChildAt(times.childCount - 1) as LinearLayout
        val subView = (row.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
        (try { Weather.lastLocation(this) } catch (_: Exception) { null })?.let { loc ->
            Thread {
                val city = try { Weather.cityName(this, loc) } catch (_: Exception) { "" }
                runOnUiThread { subView.text = city.ifBlank { "המיקום שלך" } }
            }.start()
        }
    }
    if (w == null) { ouiDivider(times); ouiRow(times, "לא הצלחנו לחשב את הזמנים") }
    else {
        ouiDivider(times)
        ouiRow(times, "כניסה", sub = fmtDay.format(java.util.Date(w.first)), subColor = C.MUTED,
            end = fmtTime.format(java.util.Date(w.first)), endBig = true)
        ouiDivider(times)
        ouiRow(times, "יציאה", sub = fmtDay.format(java.util.Date(w.second)), subColor = C.MUTED,
            end = fmtTime.format(java.util.Date(w.second)), endBig = true)
    }
    ouiAdd(body, times)
    ouiNote(body, "לפי המיקום ושיטת אתר ישיבה: כניסה לפי מנהג המקום (ירושלים 40 דק׳, חיפה 30, תל אביב 21...), יציאה בצאת הכוכבים")

    // מה מצב שבת עושה
    val info = ouiCard()
    info.addView(text("מה מצב שבת עושה", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), 0) })
    ouiText(info, listOf(
        "כל התרעה נפתחת במסך מלא, גם כשהטלפון בשימוש",
        "ההתרעה מוקראת בקול, גם אם ההקראה כבויה בהגדרות",
        "מסך ההתרעה נסגר לבד אחרי דקה – אין צורך לגעת בטלפון",
        "עוקף הכל: שעות שקט, נא לא להפריע, מצב שקט של הטלפון, וגם צלילים ורטט כבויים"
    ).joinToString("\n") { "• $it" })
    ouiAdd(body, info)
}

internal fun MainActivity.chooseTest() = ouiPage("בדיקת התראה") { body, rebuild ->
    val fab = ouiCard()
    val fm = Prefs.testFabMode(this)
    ouiRow(fab, "כפתור צף במסך הראשי", sub = showModeLabel(fm, Prefs.testFabSeconds(this)), on = fm != Prefs.STATUS_HIDDEN,
        onToggle = { Prefs.setTestFabMode(this, if (fm == Prefs.STATUS_HIDDEN) Prefs.STATUS_ALWAYS else Prefs.STATUS_HIDDEN)
            applyTestFab(); rebuild() }) {
        chooseShowMode("כפתור צף", { Prefs.testFabMode(this) }, { Prefs.setTestFabMode(this, it) },
            { Prefs.testFabSeconds(this) }, { Prefs.setTestFabSeconds(this, it) }, ::applyTestFab,
            "\"לפי זמן\" – הכפתור מוצג כשפותחים את האפליקציה או נוגעים במסך, ונעלם אחרי הזמן שבוחרים") { rebuild() } }
    ouiAdd(body, fab)
    ouiNote(body, "כפתור אדום בפינת המסך הראשי שפותח את העמוד הזה")
    // הקראה בהתרעת בדיקה - אפשר לכבות (רק בבדיקה; בהתרעה אמיתית לפי הגדרות ההקראה)
    val spk = ouiCard()
    val spkOn = Prefs.testSpeak(this)
    ouiRow(spk, "הקראה בבדיקה", sub = if (spkOn) "מוקרא" else "לא מוקרא", on = spkOn,
        onToggle = { Prefs.setTestSpeak(this, !spkOn); rebuild() }) { Prefs.setTestSpeak(this, !spkOn); rebuild() }
    ouiAdd(body, spk)
    ouiNote(body, "רק בהתרעות בדיקה – בהתרעה אמיתית ההקראה לפי ההגדרות")
    // אירוע מלא: התרעה מקדימה, ירי וסיום - ברצף, כמו אירוע אמיתי (מקוצר)
    val seq = ouiCard()
    ouiRow(seq, "אירוע מלא", sub = "מקדימה ← ירי ← סיום") {
        Prefs.setEnabled(this, true)
        AlertService.startTestSequence(this)
        pages.toList().forEach { it.dismiss() }
        refresh()
    }
    ouiAdd(body, seq)
    ouiNote(body, "ירי אחרי 15 שניות, וסיום 20 שניות אחרי שנגמר הזמן להגעה למרחב המוגן")
    // כל סוגי ההתרעה של פיקוד העורף - כל שורה עם האייקון של הסוג בצבע הרמה
    val tests = listOf(
        "התרעה מקדימה" to "בדקות הקרובות צפויות להתקבל התרעות באזורך",
        "ירי רקטות וטילים" to "ירי רקטות וטילים",
        "חדירת כלי טיס עוין" to "חדירת כלי טיס עוין",
        "חדירת מחבלים" to "חדירת מחבלים",
        "רעידת אדמה" to "רעידת אדמה",
        "צונאמי" to "צונאמי",
        "אירוע רדיולוגי" to "אירוע רדיולוגי",
        "חומרים מסוכנים" to "חומרים מסוכנים",
        "האירוע הסתיים" to "האירוע הסתיים")
    val c = ouiCard()
    tests.forEachIndexed { i, (label, title) ->
        if (i > 0) ouiDivider(c)
        val level = AlertService.levelOf(title)
        c.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            addView(FrameLayout(this@chooseTest).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(AlertUi.color(level))) }
                addView(android.widget.ImageView(this@chooseTest).apply {
                    AlertUi.setIcon(this, AlertUi.icon(title, level))
                    imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))   // כמו בהתרעות אחרונות
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
            addView(text(label, 16f, C.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener {
                Prefs.setEnabled(this@chooseTest, true)
                AlertService.start(this@chooseTest, testTitle = title)
                if (level != AlertService.LEVEL_ALERT && Prefs.isQuietNow(this@chooseTest)) {
                    android.widget.Toast.makeText(this@chooseTest,
                        "שעות שקט פעילות – ההתראה נשלחה בשקט, בלי צליל ומסך מלא",
                        android.widget.Toast.LENGTH_LONG).show()
                }
                // סוגרים את עמודי ההגדרות - ההתרעה מוצגת מיד (גם כרטיס / ממוזער שבמסך הראשי)
                pages.toList().forEach { it.dismiss() }
                refresh()
            }
        }, LinearLayout.LayoutParams(-1, dp(56)))
    }
    ouiAdd(body, c)
    ouiNote(body, "ההתראה נשלחת מיד, בדיוק כמו התרעה אמיתית")
}

/** הדמיה קטנה של סוג תצוגת ההתרעה: 0 מסך מלא · 1 פופ-אפ · 2 כרטיס · 3 ממוזער */
internal fun MainActivity.alertStylePreview(kind: Int): View {
    fun rect(c: String, r: Int) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(r).toFloat() }
    // בהיר / חשוך לפי ערכת הצבעים
    val bg = if (C.LIGHT) "#D9D9DC" else "#2B2B2E"
    val block = if (C.LIGHT) "#BDBDC2" else "#3E3E42"
    val red = "#D50000"
    val f = FrameLayout(this).apply { background = rect(bg, 10); clipToOutline = true }
    // רשימת כרטיסים עם רווח שווה; בכרטיס/ממוזער - ההתרעה היא אחד הכרטיסים ברשימה
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(5), dp(5), dp(5), dp(5)) }
    listOf(10, 20, 30, 20).forEachIndexed { i, h ->
        val isAlert = (kind == 3 && i == 0) || (kind == 2 && i == 1)
        list.addView(View(this).apply { background = rect(if (isAlert) red else block, 4) },
            LinearLayout.LayoutParams(-1, dp(h)).apply { bottomMargin = dp(4) })
    }
    f.addView(list, FrameLayout.LayoutParams(-1, -1))
    when (kind) {
        0 -> f.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(red))
            addView(View(this@alertStylePreview).apply { background = rect("#B3FFFFFF", 3) },
                LinearLayout.LayoutParams(-1, dp(7)).apply { setMargins(dp(10), dp(8), dp(10), dp(4)) })
            if (Prefs.alertMap(this@alertStylePreview))
                addView(View(this@alertStylePreview).apply { setBackgroundColor(Color.parseColor(if (C.LIGHT) "#BDBDC2" else "#333333")) }, LinearLayout.LayoutParams(-1, dp(30)))   // המפה
            addView(View(this@alertStylePreview).apply { background = rect("#40000000", 5) },
                LinearLayout.LayoutParams(-1, dp(18)).apply { setMargins(dp(8), dp(6), dp(8), 0) })
        }, FrameLayout.LayoutParams(-1, -1))
        // פופ-אפ: כרטיס אדום באמצע, עם המפה בתוכו (כמו במסך המלא)
        1 -> f.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rect(red, 8); clipToOutline = true
            addView(View(this@alertStylePreview).apply { background = rect("#B3FFFFFF", 2) },
                LinearLayout.LayoutParams(-1, dp(5)).apply { setMargins(dp(8), dp(6), dp(8), dp(4)) })
            if (Prefs.alertMap(this@alertStylePreview))
                addView(View(this@alertStylePreview).apply { setBackgroundColor(Color.parseColor(if (C.LIGHT) "#BDBDC2" else "#333333")) },
                    LinearLayout.LayoutParams(-1, dp(18)))
            addView(View(this@alertStylePreview).apply { background = rect("#40000000", 3) },
                LinearLayout.LayoutParams(-1, dp(12)).apply { setMargins(dp(6), dp(5), dp(6), dp(6)) })
        }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(6), 0, dp(6), 0) })
    }
    return f
}

internal fun MainActivity.setTheme(d: android.app.Dialog, mode: Int, rebuild: () -> Unit) {
    if (Prefs.themeMode(this) == mode) return
    Prefs.setThemeMode(this, mode)
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
    fun addCard(c: View) = body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), 0, dp(10), dp(8)) })
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
        body.addView(text("תצוגה", 20f, C.TEXT, bold = true).apply { setPadding(dp(24), dp(14), dp(24), dp(14)) })
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
        pick.addView(preview(false, "חשוך", !sys && mode == 0, 0), LinearLayout.LayoutParams(0, -2, 1f))
        pick.addView(preview(true, "בהיר", !sys && mode == 1, 1), LinearLayout.LayoutParams(0, -2, 1f))
        theme.addView(pick)
        divider(theme)
        row(theme, "לפי המערכת", on = sys, sep = false) { setTheme(d, if (sys) 0 else 2) { rebuild() } }
        addCard(theme)
        note("כשמופעל - בהיר או חשוך לפי הגדרת הטלפון")
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
        row(more, "ברכה", on = Prefs.showGreeting(this), sep = false) {
            Prefs.setShowGreeting(this, !Prefs.showGreeting(this)); updateClock(); rebuild() }
        addCard(more)
        note("מזג אוויר וברכה בכרטיס השעון במסך הראשי")
        // פיד ארצי - כמה זמן אחורה להציג
        val feed = card()
        row(feed, "פיד ארצי", FeedText.windowLabel(Prefs.feedMinutes(this)), on = Prefs.feedAlways(this),
            open = { chooseFeedWindow { rebuild() } }) {
            Prefs.setFeedAlways(this, !Prefs.feedAlways(this)); updateFeed(); rebuild() }
        addCard(feed)
        note("ההתראות בכל הארץ בתחתית המסך הראשי. כשהמתג מופעל - הפיד מוצג תמיד, גם כשאין התראות")
    }
    themeAtOpen = Prefs.themeMode(this)
    rebuild()
    // יציאה מהעמוד: אם ערכת הצבעים השתנתה - טוענים מחדש וחוזרים להגדרות
    track(d) { if (Prefs.themeMode(this) != themeAtOpen) { MainActivity.reopenDisplay = true; recreate() } }
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

/** מסך עדכונים: גרסה מותקנת + בדיקה, גרסאות בטא (למי שנפתח לו), ומה חדש */
internal fun MainActivity.showUpdates() = ouiPage("עדכונים",
    // חלון בדיקת העדכונים - אותו חלון כמו לחיצה על הגרסה בשורת החיווי
    button = "בדוק אם יש עדכונים" to { done -> done(); showUpdateCheck() }) { body, rebuild ->
    val ver = ouiCard()
    ouiRow(ver, "גרסה מותקנת", end = "v" + Updater.versionLabel(this), endColor = C.MUTED)
    ouiDivider(ver)
    // מתי האפליקציה עודכנה לאחרונה בטלפון
    val updatedAt = try {
        java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.US)
            .format(java.util.Date(packageManager.getPackageInfo(packageName, 0).lastUpdateTime))
    } catch (_: Exception) { "" }
    ouiRow(ver, "עודכן לאחרונה", end = updatedAt, endColor = C.MUTED)
    ouiDivider(ver)
    // מתי נבדק לאחרונה אם יש גרסה חדשה (גם הבדיקה האוטומטית)
    val checkedAt = Prefs.lastUpdateCheck(this).takeIf { it > 0 }?.let {
        java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.US).format(java.util.Date(it)) } ?: "עוד לא"
    ouiRow(ver, "בדיקה אחרונה", end = checkedAt, endColor = C.MUTED)
    ouiAdd(body, ver)
    ouiNote(body, "מהעדכון השני ההתקנה אוטומטית, בלי ללחוץ התקן")

    // עדכון אוטומטי ברקע: כשהשרת מודיע על גרסה חדשה - יורדת ומותקנת לבד (לא באמצע אירוע)
    val au = ouiCard()
    val auto = Prefs.autoUpdate(this)
    ouiRow(au, "עדכון אוטומטי", sub = if (auto) "גרסה חדשה מותקנת לבד ברקע" else "כבוי – רק התראה על גרסה חדשה", on = auto) {
        Prefs.setAutoUpdate(this, !auto); rebuild() }
    ouiAdd(body, au)
    ouiNote(body, "לא מתעדכן באמצע אירוע – מחכה שיסתיים")

    // גרסאות בטא - רק למי שפתח אותן (7 לחיצות על הגרסה)
    if (Prefs.betaUnlocked(this) || Prefs.betaUpdates(this)) {
        val on = Prefs.betaUpdates(this)
        val beta = ouiCard()
        ouiRow(beta, "גרסאות בטא", sub = if (on) "מקבל גרסאות ניסיון" else "כבוי", on = on) {
            Prefs.setBetaUpdates(this, !on)
            Prefs.setSkippedVersion(this, "")
            if (Prefs.enabled(this)) AlertService.reloadPush(this)
            if (on) Prefs.setBetaUnlocked(this, false)   // כיבוי - האפשרות חוזרת להיות מוסתרת
            else showUpdateCheck()
            rebuild()
        }
        ouiAdd(body, beta)
        ouiNote(body, "גרסאות לפני שהן משוחררות לכולם · מזהה מכשיר: ${Prefs.deviceCode(this)}")
    }

    // מה חדש בגרסה המותקנת
    val news = ouiCard()
    news.addView(text("מה חדש", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), 0) })
    ouiText(news, whatsNewText(null) ?: "אין פירוט לגרסה הזו")
    ouiAdd(body, news)
}

/** עמוד אודות: אייקון, שם וגרסה, מקורות, רישיונות והבהרה */
internal fun MainActivity.showAbout() = ouiPage("אודות") { body, _ ->
    // ראש העמוד כמו "אודות" בסמסונג: אייקון גדול, שם וגרסה
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(10), 0, dp(22))
        addView(android.widget.ImageView(this@showAbout).apply { setImageResource(R.mipmap.ic_launcher) },
            LinearLayout.LayoutParams(dp(88), dp(88)))
        addView(text("צופר חירום", 24f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        addView(text("גרסה " + Updater.versionLabel(this@showAbout), 14f, C.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        addView(text("התראות פיקוד העורף בזמן אמת, גם כשהאפליקציה סגורה והמסך כבוי", 13f, C.MUTED2).apply {
            gravity = Gravity.CENTER; setPadding(dp(30), dp(10), dp(30), 0) })
    }, LinearLayout.LayoutParams(-1, -2))

    val note = ouiCard()
    note.addView(text("הבהרה", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), 0) })
    ouiText(note, listOf(
        "זו אפליקציה פרטית ולא רשמית. היא אינה קשורה לפיקוד העורף, לצה\"ל או לכל גוף ממשלתי",
        "המידע מגיע ממקורות ציבוריים, וייתכנו עיכובים, תקלות או התראות חסרות",
        "האפליקציה משלימה ואינה מחליפה את האפליקציה הרשמית של פיקוד העורף, את הצופרים ואת ההנחיות הרשמיות",
        "השימוש באחריות המשתמש בלבד. ההנחיות הרשמיות באתר פיקוד העורף גוברות תמיד"
    ).joinToString("\n") { "• $it" })
    ouiAdd(body, note)

    val src = ouiCard()
    src.addView(text("מקורות (${SourceHealth.total()})", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
    SourceHealth.NAMES.values.forEachIndexed { i, n -> if (i > 0) ouiDivider(src); ouiRow(src, n) }
    ouiAdd(body, src)
    ouiNote(body, "ההתראה מוצגת מהמקור שהגיע הכי מהר, פעם אחת בלבד")

    val lic = ouiCard()
    lic.addView(text("רישיונות", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
    listOf("מפה" to "Leaflet · BSD-2", "גבולות אזורים וזמני הגעה" to "oref_alert · MIT",
        "רשת" to "OkHttp · Apache 2.0", "מזג אוויר ושמות מקומות" to "Open-Meteo · OpenStreetMap")
        .forEachIndexed { i, (a, b) -> if (i > 0) ouiDivider(lic); ouiRow(lic, a, sub = b, subColor = C.MUTED) }
    ouiAdd(body, lic)

    body.addView(text("נבנתה על ידי טל ששון · © ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} כל הזכויות שמורות",
        12f, C.MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(10), dp(20), 0) }, LinearLayout.LayoutParams(-1, -2))
}

/** עמוד הקראה: הדלקה/כיבוי והשמעת דוגמה */
internal fun MainActivity.showSpeech() = ouiPage("הקראה") { body, rebuild ->
    val c = ouiCard()
    ouiRow(c, "הקראה בקול", on = Prefs.speakAlerts(this)) {
        Prefs.setSpeakAlerts(this, !Prefs.speakAlerts(this)); rebuild() }
    ouiDivider(c)
    ouiRow(c, "השמע דוגמה", sub = "ירי רקטות וטילים באזורים שלך") {
        // דוגמה בקול של ההתראות (הקלטות "אבר")
        val sp = testSpeaker ?: Speaker(this).also { testSpeaker = it }
        val mine = Prefs.cities(this) + Prefs.nearbyAreas(this)
        val areas = mine.ifEmpty { listOf("תל אביב - מרכז העיר") }.take(3)
        sp.speakAlert("ירי רקטות וטילים", areas, AreaData.shelterSeconds(this, areas) ?: 90) {}
    }
    ouiAdd(body, c)
    ouiNote(body, "מקריא סוג, אזורים וזמן להגעה – ואז הצליל. במצב שבת ההקראה פועלת תמיד")
}

/** שעות שקט: רק התראה מקדימה וסיום אירוע מושתקים. ירי תמיד נשמע. */
/** עמוד שעות שקט: הדלקה/כיבוי, שעת התחלה וסיום */
internal fun MainActivity.showQuietHours() = ouiPage("שעות שקט") { body, rebuild ->
    val on = Prefs.quietOn(this)
    val sw = ouiCard()
    ouiRow(sw, "שעות שקט", on = on) { Prefs.setQuietOn(this, !on); rebuild() }
    ouiAdd(body, sw)
    ouiNote(body, "בשעות האלה התרעה מקדימה וסיום אירוע מגיעים בשקט. ירי תמיד נשמע")
    val t = ouiCard()
    ouiRow(t, "התחלה", end = hhmm(Prefs.quietFrom(this)), endBig = true) {
        pickTime("שעת התחלה", Prefs.quietFrom(this)) { Prefs.setQuiet(this, it, Prefs.quietTo(this)); rebuild() } }
    ouiDivider(t)
    ouiRow(t, "סיום", end = hhmm(Prefs.quietTo(this)), endBig = true) {
        pickTime("שעת סיום", Prefs.quietTo(this)) { Prefs.setQuiet(this, Prefs.quietFrom(this), it); rebuild() } }
    ouiAdd(body, t)
    ouiNote(body, "מצב שבת עוקף את שעות השקט")
}

/** בחירת שעה - חלון השעה של הטלפון (בסמסונג: גלגלים, "ביטול | סיום") */
/**
 * בחירת שעה בדיוק כמו "הגדר שעה" בהגדרות של סמסונג: חלון אפור מעוגל בתחתית המסך,
 * כותרת, גלגל שעות וגלגל דקות, ולמטה ביטול | סיום
 */
/** hourCount = כמה ערכים בגלגל השעות (24 = 00–23 לשעון; 25 = 00–24 למשך זמן) */
internal fun MainActivity.pickTime(title: String, cur: Int, hourCount: Int = 24, done: (Int) -> Unit) {
    // גלגלים: שעה משמאל, דקות מימין (כמו בצילום)
    val hours = TimeWheel(this, hourCount, cur / 60)
    val mins = TimeWheel(this, 60, cur % 60)
    ouiSheet(title, cancel = "ביטול", ok = "סיום", onOk = { done(hours.value * 60 + mins.value) }) { card, white ->
        card.addView(LinearLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(44), 0, dp(44), 0)
            addView(hours, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(":", 32f, white, bold = true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40), -2))
            addView(mins, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
    }
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
    opts.addView(text("הצגת התראות מ…", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
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
    ouiNote(body, "ההתראות מתחלפות אחת אחרי השנייה בגלילה כלפי מעלה")
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

internal fun MainActivity.hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

/** אחרי עדכון: פעם אחת, כל מה שהשתנה מאז הגרסה הקודמת שהייתה מותקנת */
internal fun MainActivity.showWhatsNewIfUpdated() {
    val cur = Updater.currentVersion(this)
    val seen = Prefs.seenVersion(this)
    Prefs.setSeenVersion(this, cur)
    when {
        seen.isNotEmpty() && seen != cur -> showWhatsNew(sinceVersion = seen)
        // גרסאות ישנות לא שמרו את הערך - אם זו לא התקנה חדשה, מציגים את הגרסה הנוכחית
        seen.isEmpty() && (Prefs.enabled(this) || Prefs.history(this).isNotEmpty()) ->
            showWhatsNew(sinceVersion = null)
    }
}

/** הטקסט של "מה חדש". sinceVersion = null: רק הגרסה הנוכחית */
internal fun MainActivity.whatsNewText(sinceVersion: String?): String? {
    val log = try {
        org.json.JSONObject(assets.open("changelog.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    } catch (_: Exception) { return null }
    val cur = Updater.currentVersion(this)
    val versions = log.keys().asSequence()
        .filter { v -> if (sinceVersion == null) v == cur else Updater.isNewer(v, sinceVersion) && !Updater.isNewer(v, cur) }
        .sortedWith { a, b -> if (Updater.isNewer(a, b)) -1 else if (a == b) 0 else 1 }
        .toList()
    if (versions.isEmpty()) return null
    return versions.joinToString("\n\n") { v ->
        val items = log.getJSONArray(v)
        "גרסה $v\n" + (0 until items.length()).joinToString("\n") { "• " + items.getString(it) }
    }
}

internal fun MainActivity.showWhatsNew(sinceVersion: String?) {
    val text = whatsNewText(sinceVersion) ?: return
    AppSheet.show(this, "🆕 מה חדש", text, "הבנתי")?.let { track(it) }
}

/**
 * יצירת קשר: דיווח על בעיה ישירות למפתח (מגיע לאתר ולטלפון של טל).
 * אפשר לצרף פרטי מכשיר והגדרות - כך לא צריך להסביר בטלפון. התשובה מגיעה כהודעה באפליקציה
 */
internal fun MainActivity.showContact() {
    var draft = ""
    var attach = true
    lateinit var input: android.widget.EditText
    ouiPage("יצירת קשר", refreshOnFocus = false, button = "שלח למפתח" to { done ->
        val msg = input.text.toString().trim()
        if (msg.length < 3) { android.widget.Toast.makeText(this, "כתוב מה קרה", android.widget.Toast.LENGTH_SHORT).show(); done(); return@to }
        val info = if (attach) deviceInfo() else ""
        Thread {
            val ok = Reporter.sendReport(this, msg, info)
            runOnUiThread {
                done()
                if (ok) { input.setText(""); draft = "" }
                android.widget.Toast.makeText(this, if (ok) "✓ הדיווח נשלח – תודה!" else "השליחה נכשלה – בדוק את החיבור לאינטרנט", android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }) { body, rebuild ->
        val c = ouiCard()
        c.addView(text("דיווח על בעיה", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(8)) })
        input = android.widget.EditText(this).apply {
            hint = "מה קרה? למשל: לא קיבלתי התרעה בירושלים בשעה 14:30"
            setText(draft); textSize = 15f; minLines = 5; gravity = Gravity.TOP or Gravity.START
            setTextColor(C.TEXT); setHintTextColor(C.MUTED)
            background = GradientDrawable().apply { setColor(C.CARD2); cornerRadius = dp(18).toFloat() }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            filters = arrayOf(android.text.InputFilter.LengthFilter(1500))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { draft = s?.toString() ?: "" }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        }
        c.addView(input, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), 0, dp(14), dp(14)) })
        ouiAdd(body, c)

        val a = ouiCard()
        ouiRow(a, "צרף פרטי מכשיר והגדרות", sub = if (attach) "גרסה, דגם, אזורים, צלילים ומצב החיבור" else "כבוי", on = attach) {
            attach = !attach; rebuild() }
        ouiAdd(body, a)
        ouiNote(body, "הדיווח מגיע ישירות למפתח. התשובה תגיע כהודעה באפליקציה")
    }
}

/** פרטי המכשיר וההגדרות לדיווח (בלי מיקום מדויק ובלי מידע אישי) */
private fun MainActivity.deviceInfo(): String {
    val yes = { b: Boolean -> if (b) "כן" else "לא" }
    val lines = listOf(
        "גרסה" to Updater.versionLabel(this),
        "דגם" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
        "מוגן" to yes(Prefs.enabled(this) && !Prefs.scheduleOffNow(this)),
        "אזורים" to (Prefs.nearbyAreas(this) + Prefs.cities(this)).distinct().joinToString(", ").ifEmpty { "–" },
        "צלילים" to yes(Prefs.soundsOn(this)), "רטט" to yes(Prefs.vibesOn(this)),
        "מושתק" to yes(Prefs.mutedNow(this)), "צליל חזק" to yes(Prefs.loudSound(this)),
        "עקיפת שקט" to yes(Prefs.bypassSilent(this)), "שעות שקט" to yes(Prefs.quietOn(this)),
        "מצב שבת" to Shabbat.label(Prefs.shabbatMode(this)), "הקראה" to yes(Prefs.speakAlerts(this)),
        "תצוגה" to listOf("מסך מלא", "פופ-אפ", "כרטיס", "ממוזער").getOrElse(Prefs.alertStyle(this)) { Prefs.alertStyle(this).toString() },
        "רשת" to (network()?.first ?: "אין"),
    )
    return lines.joinToString("\n") { "${it.first}: ${it.second}" }
}

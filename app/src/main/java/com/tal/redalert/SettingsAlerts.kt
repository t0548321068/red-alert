package com.tal.redalert

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tal.redalert.MainActivity.C

// (חלק מהמסך הראשי - MainActivity; המצב עצמו נשמר שם)

// עמודי ההגדרות: התרעות - סוג התצוגה, מרחק, השתקה זמנית ובדיקת התרעה

/** עמוד התרעות: כל ההגדרות של ההתרעה במקום אחד */
internal fun MainActivity.showAlertSettings() = ouiPage("התרעות") { body, rebuild ->
    // סוג תצוגת התרעה: שורה אחת - העמוד שלה מרכז את הבחירה הכללית וההתאמה האישית לכל נושא
    val style = ouiCard()
    ouiRow(style, "סגנון התרעה", sub = styleSummary()) { showCustomStyles() }
    ouiAdd(body, style)

    val c = ouiCard()
    // מתג = הדלקה/כיבוי, לחיצה על השם = ההגדרות המלאות
    ouiRow(c, "אזור התרעה", sub = onOff(Prefs.nearMe(this)), on = Prefs.nearMe(this)) { toggleNearMe(); rebuild() }
    ouiDivider(c)
    ouiRow(c, "מרחק מקסימלי", sub = Prefs.radiusLabel(Prefs.nearRadiusKm(this))) { showNearRadius() }
    ouiDivider(c)
    // אזורים נוספים - באותו כרטיס, ומתחת מה שנבחר (עד 3 שמות, "ועוד N")
    val (towns, dists) = extraSelection()
    val chosen = towns + dists
    val extraSub = when {
        chosen.isEmpty() -> "לא נבחרו"
        chosen.size <= 3 -> chosen.joinToString(", ")
        else -> chosen.take(3).joinToString(", ") + " ועוד ${chosen.size - 3}"
    }
    ouiRow(c, "אזורים נוספים", sub = extraSub) { showExtraAreas() }
    // התראה רגילה על כל התרעה בפיד הארצי - תמיד מופיע
    ouiDivider(c)
    val fn = Prefs.feedNotify(this)
    ouiRow(c, "התראה מהפיד הארצי", sub = onOff(fn), on = fn) { Prefs.setFeedNotify(this, !fn); rebuild() }
    ouiAdd(body, c)
    // צלילים ורטט (ההקראה בתוכו)
    val snd = ouiCard()
    ouiRow(snd, "צלילים ורטט") { showSoundSettings() }
    ouiAdd(body, snd)
    // בדיקת התראה (עברה מהכפתור שבמסך הראשי)
    val t = ouiCard()
    ouiRow(t, "בדיקת התרעה") { chooseTest() }
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
        val sel = if (min == 0L) !active else if (min == -1L) u == Long.MAX_VALUE else active && u != Long.MAX_VALUE && Prefs.muteMin(this) == min
        ouiRow(c, label, radio = sel) {
            Prefs.setMuteUntil(this, when (min) { 0L -> 0L; -1L -> Long.MAX_VALUE
                else -> System.currentTimeMillis() + min * 60_000 })
            Prefs.setMuteMin(this, min)
            refresh(); rebuild()
        }
    }
    ouiAdd(body, c)
    ouiNote(body, if (active) muteLabel() + ". ההתרעות מוצגות כרגיל, בלי צליל, רטט והקראה" else "ההתרעות יוצגו כרגיל, אבל בלי צליל, רטט והקראה")
}

/**
 * בדיקה: ההתרעה מוצגת על המסך הראשי, ובסגירה חוזרים אליו.
 * כרטיס / ממוזער - עמודי ההגדרות נסגרים מיד. מסך מלא / פופ-אפ - רק כשמסך ההתרעה כבר עלה (שלא ייראה המסך הראשי לפני)
 */
private fun MainActivity.closePagesForCard(level: Int) {
    val st = if (Shabbat.active(this)) 0 else Prefs.alertStyleFor(this, level)
    if (st >= 2) { closeAllPages(); return }
    closePagesOnScreen = true
    ui.postDelayed({ if (closePagesOnScreen) { closePagesOnScreen = false; closeAllPages() } }, 5000)   // אם מסך ההתרעה לא עלה
}

internal fun MainActivity.chooseTest() = ouiPage("בדיקת התרעה") { body, rebuild ->
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
    ouiRow(spk, "הקראה בבדיקה", on = spkOn) { Prefs.setTestSpeak(this, !spkOn); rebuild() }   // כל השורה מדליקה/מכבה - בלי קו
    ouiAdd(body, spk)
    ouiNote(body, "רק בהתרעות בדיקה – בהתרעה אמיתית ההקראה לפי ההגדרות")
    // ארבעה חלקים: מבזק · התרעה · סיום - כל אחד עם המסכים שלו, ואירוע מלא שבוחרים בו מה יוצג בכל שלב
    fun header(t: String) = body.addView(text(t, 13f, C.MUTED2).apply { setPadding(dp(PAGE_SIDE + 16), dp(6), dp(PAGE_SIDE + 16), dp(6)) })
    fun send(title: String) {
        val level = AlertService.levelOf(title)
        Prefs.setEnabled(this, true)
        AlertService.start(this, testTitle = title)
        if (level != AlertService.LEVEL_ALERT && Prefs.isQuietNow(this)) {
            android.widget.Toast.makeText(this, "שעות שקט פעילות – ההתרעה נשלחה בשקט, בלי צליל ומסך מלא",
                android.widget.Toast.LENGTH_LONG).show()
        }
        closePagesForCard(level)
    }
    fun section(name: String, list: List<Pair<String, String>>) {
        header(name)
        val c = ouiCard()
        list.forEachIndexed { i, (label, title) -> if (i > 0) ouiDivider(c); testRow(c, label, title) { send(title) } }
        ouiAdd(body, c)
    }
    section("1 · מבזק", TEST_PRE)
    section("2 · התרעה", TEST_ALERT)
    section("3 · סיום", TEST_END)

    // 4 · אירוע מלא: בוחרים מבזק, התרעה וסיום, והם נשלחים ברצף
    header("4 · אירוע מלא")
    val seq = ouiCard()
    fun stage(key: String, name: String, list: List<Pair<String, String>>) {
        val cur = Prefs.testSeq(this, key, list[0].second)
        ouiRow(seq, name, sub = list.firstOrNull { it.second == cur }?.first ?: list[0].first) {
            ouiPage(name) { b2, rebuild2 ->
                val c = ouiCard()
                val sel = Prefs.testSeq(this, key, list[0].second)
                list.forEachIndexed { i, (label, title) ->
                    if (i > 0) ouiDivider(c)
                    testRow(c, label, title, selected = sel == title) { Prefs.setTestSeq(this, key, title); rebuild2() }
                }
                ouiAdd(b2, c)
            }
        }
        ouiDivider(seq)
    }
    stage("pre", "מבזק", TEST_PRE)
    stage("alert", "התרעה", TEST_ALERT)
    stage("end", "סיום", TEST_END)
    ouiRow(seq, "הפעלת האירוע המלא", sub = "מבזק ← התרעה ← סיום") {
        Prefs.setEnabled(this, true)
        AlertService.startTestSequence(this, Prefs.testSeq(this, "pre", TEST_PRE[0].second),
            Prefs.testSeq(this, "alert", TEST_ALERT[0].second), Prefs.testSeq(this, "end", TEST_END[0].second))
        closePagesForCard(AlertService.LEVEL_PRE)
    }
    ouiAdd(body, seq)
    ouiNote(body, "התרעה 15 שניות אחרי המבזק, וסיום 20 שניות אחרי שנגמר הזמן להגעה למרחב המוגן. ההתרעות נשלחות מיד, בדיוק כמו התרעה אמיתית")
}

/** המסכים לבדיקה: (מה שכתוב בשורה, הכותרת שנשלחת - כמו שהיא מגיעה מהמקור) */
private val TEST_PRE = listOf(
    "מבזק כללי" to "בדקות הקרובות צפויות להתקבל התרעות באזורך",
    "מבזק – איום מלבנון" to "איום מלבנון")
private val TEST_ALERT = listOf(
    "ירי רקטות וטילים" to "ירי רקטות וטילים",
    "חדירת כלי טיס עוין" to "חדירת כלי טיס עוין",
    "חדירת מחבלים" to "חדירת מחבלים",
    "רעידת אדמה" to "רעידת אדמה",
    "צונאמי" to "צונאמי",
    "אירוע רדיולוגי" to "אירוע רדיולוגי",
    "חומרים מסוכנים" to "חומרים מסוכנים")
private val TEST_END = listOf(
    "האירוע הסתיים" to "האירוע הסתיים",
    "עדכון – הנחיות חזרה למבנים" to "רעידת אדמה - הנחיות חזרה למבנים")

/** שורת בדיקה: אייקון הסוג בעיגול בצבע הרמה (כמו בהתרעות אחרונות), שם, ובבחירה - כפתור בחירה */
private fun MainActivity.testRow(c: LinearLayout, label: String, title: String, selected: Boolean? = null, click: () -> Unit) {
    val level = AlertService.levelOf(title)
    c.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(18), 0)
        addView(FrameLayout(this@testRow).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(AlertUi.color(level))) }
            addView(android.widget.ImageView(this@testRow).apply {
                AlertUi.setIcon(this, AlertUi.icon(title, level))
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
        addView(text(label, 16f, C.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        if (selected != null) addView(ouiRadio(selected), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(10) })
        setOnClickListener { click() }
    }, LinearLayout.LayoutParams(-1, dp(ROW_H)))
}

/**
 * תצוגה מקדימה של סוג התצוגה שנבחר: רק במסך - בלי צליל, רטט, התראה, היסטוריה או ווידג'ט.
 * נסגרת לבד אחרי 20 שניות. כשיש התרעה אמיתית פעילה - לא מציגים (שלא תידרס)
 */
internal fun MainActivity.previewAlertStyle(level: Int = AlertService.LEVEL_ALERT, style: Int? = null) {
    val real = Prefs.activeAlert(this)
    // חוסמים רק התרעה אמיתית שעוד מוצגת - לא בדיקה, ולא התרעה שנסגרה ב-✕
    if (real != null && !AlertUi.expired(real.optInt("level"), real.optLong("firedAt")) &&
        real.optString("source") != "בדיקה" && Prefs.alertDismissed(this) != real.optLong("firedAt")) {
        android.widget.Toast.makeText(this, "יש התרעה פעילה – אפשר לנסות אחרי שתסתיים", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val areas = (Prefs.nearbyAreas(this).take(1) + Prefs.cities(this)).distinct().ifEmpty { listOf("ירושלים - צפון") }
    val body = areas.first()
    val title = when (level) {
        AlertService.LEVEL_PRE -> "בדקות הקרובות צפויות להתקבל התרעות באזורך"
        AlertService.LEVEL_END -> "האירוע הסתיים"
        else -> "ירי רקטות וטילים"
    }
    val shelter = if (level == AlertService.LEVEL_ALERT) AreaData.shelterSeconds(this, listOf(body)) ?: 90 else -1
    val now = System.currentTimeMillis()
    val src = "תצוגה מקדימה"
    val st = style ?: Prefs.alertStyleFor(this, level)
    val full = Intent(this, if (st == 1) AlertPopupActivity::class.java else AlertActivity::class.java)
        .putExtra("title", title).putExtra("body", body).putExtra("level", level)
        .putExtra("shelter", shelter).putExtra("firedAt", now).putExtra("source", src)
        .putExtra("autoCloseMs", 20_000L).putExtra("mapAreas", body)
    // עמודי ההגדרות נשארים פתוחים - בסגירת התצוגה המקדימה חוזרים אליהם
    when (st) {
        0, 1 -> startActivity(full)
        2 -> {
            // הכרטיס נמצא במסך הראשי: מסתירים את עמודי ההגדרות זמנית, ומחזירים בסיום (20 שניות או ✕)
            previewAlert = org.json.JSONObject().put("title", title).put("body", body).put("level", level)
                .put("shelter", shelter).put("firedAt", now).put("source", src).put("mapAreas", body)
            pages.forEach { it.hide() }
            updateAlertCard()
            ui.postDelayed({ if (previewAlert?.optLong("firedAt") == now) endCardPreview() }, 20_000L)
        }
        else -> {
            // ממוזער - בתוך המסך הראשי (במקום הכרטיס), כמו בהתרעה אמיתית: מסתירים את ההגדרות ומחזירים בסיום
            // בזמן התצוגה המקדימה המסך הראשי מציג רק אותה: כרטיס של התרעה אחרת (למשל בדיקה) מוסתר, וחוזר בסיום
            previewAlert = org.json.JSONObject().put("title", title).put("body", body).put("level", level)
                .put("shelter", shelter).put("firedAt", now).put("source", src).put("mapAreas", body)
            pages.forEach { it.hide() }
            fun back() { previewAlert = null; if (MiniOverlay.hostedIn(miniHost)) MiniOverlay.hide(this); miniHost.visibility = View.GONE; pages.forEach { it.show() }; updateAlertCard() }
            ui.post {
                MiniOverlay.show(this, full, into = miniHost, inline = true) { back() }
                miniHost.visibility = View.VISIBLE
                updateAlertCard()
            }
            // אחרי 20 שניות חוזרים להגדרות בכל מקרה (גם אם בינתיים החלון הוחלף / נסגר)
            ui.postDelayed({
                if (previewAlert?.optLong("firedAt") == now) previewAlert = null
                if (MiniOverlay.lastFull?.getStringExtra("source") == "תצוגה מקדימה") MiniOverlay.hide(this)
                miniHost.visibility = View.GONE; pages.forEach { it.show() }; updateAlertCard()
            }, 20_000L)
        }
    }
}

/** סוף התצוגה המקדימה של הכרטיס: הכרטיס נעלם ועמודי ההגדרות חוזרים */
internal fun MainActivity.endCardPreview() {
    previewAlert = null
    updateAlertCard()
    pages.forEach { it.show() }
}

private val STYLE_NAMES = listOf("מסך מלא", "פופ-אפ", "כרטיס", "ממוזער")
private val TOPICS = listOf(AlertService.LEVEL_ALERT to "התרעות", AlertService.LEVEL_PRE to "מבזקים והנחיות", AlertService.LEVEL_END to "עדכונים")

/** השורה בהגדרות: הבחירה הכללית, ואילו נושאים שונים ממנה */
internal fun MainActivity.styleSummary(): String {
    if (!Prefs.customStyles(this)) return STYLE_NAMES[Prefs.alertStyle(this)]
    return "מותאם אישית"
}

/** סוג תצוגת התרעה: למעלה הבחירה לכל הנושאים (הדמיות אפורות, בלי כותרת), ומתחת התאמה אישית - כל נושא בשורה */
internal fun MainActivity.showCustomStyles() = ouiPage("סגנון התרעה") { body, rebuild ->
    val all = ouiCard()
    val opts = LinearLayout(this).apply { setPadding(dp(6), dp(20), dp(6), dp(16)) }   // בלי כותרת - ההדמיות בראש הכרטיס
    STYLE_NAMES.forEachIndexed { i, label ->
        opts.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(alertStylePreview(i), LinearLayout.LayoutParams(dp(58), dp(116)))
            // התאמה אישית מבטלת את הסימון כאן; בחירה כאן מכבה את ההתאמה האישית
            addLabelRadio(this, label, !Prefs.customStyles(this@showCustomStyles) && Prefs.alertStyle(this@showCustomStyles) == i)
            setOnClickListener {
                Prefs.setAlertStyle(this@showCustomStyles, i); Prefs.setCustomStyles(this@showCustomStyles, false); rebuild() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    all.addView(opts)
    ouiDivider(all)
    ouiRow(all, "תצוגה מקדימה", sub = "רק איך זה נראה – בלי צליל ורטט") {
        previewAlertStyle(AlertService.LEVEL_ALERT, Prefs.alertStyle(this)) }
    ouiAdd(body, all)

    // מתג: כשמופעל - השורות של כל נושא מוצגות מתחת
    val c = ouiCard()
    val custom = Prefs.customStyles(this)
    ouiRow(c, "התאמה אישית", sub = "סגנון נפרד לכל נושא", on = custom) {
        // בהפעלה: כל נושא שעוד לא נבחר לו סגנון מקבל את הסגנון שהיה מסומן למעלה
        if (!custom) TOPICS.forEach { (l, _) -> if (Prefs.topicStyle(this, l) !in 0..3) Prefs.setTopicStyle(this, l, Prefs.alertStyle(this)) }
        Prefs.setCustomStyles(this, !custom); rebuild() }
    if (custom) TOPICS.forEach { (level, name) ->
        ouiDivider(c)
        ouiRow(c, name, sub = STYLE_NAMES[Prefs.alertStyleFor(this, level)]) {
            chooseTopicStyle(level, name) { rebuild() }
        }
    }
    ouiAdd(body, c)
    ouiNote(body, if (custom) "התרעות – אדום · מבזקים והנחיות – כתום · עדכונים – ירוק\nבמצב שבת כל ההתרעות במסך מלא"
        else "כשמופעל – הסימון למעלה מתבטל, ולכל נושא בוחרים סגנון משלו")
    // מה מוצג אחרי שסוגרים מסך מלא / פופ-אפ
    val ac = ouiCard()
    ouiRow(ac, "אחרי סגירה", sub = if (Prefs.afterClose(this) == 1) "ממוזער" else "כרטיס") { chooseAfterClose { rebuild() } }
    ouiAdd(body, ac)
    ouiNote(body, "מה מוצג כשסוגרים התרעה במסך מלא או בפופ-אפ")
    // המפה במסך ההתרעה (הועבר מעמוד ההתרעות)
    val m = ouiCard()
    ouiRow(m, "הצג מפה", on = Prefs.alertMap(this)) { Prefs.setAlertMap(this, !Prefs.alertMap(this)); rebuild() }
    ouiAdd(body, m)
    ouiNote(body, "המפה מוצגת במסך מלא ובפופ-אפ")
}

/** אחרי סגירה: כרטיס במסך הראשי או החלון הקטן למעלה */
private fun MainActivity.chooseAfterClose(done: () -> Unit) = ouiPage("אחרי סגירה", onClose = done) { body, rebuild ->
    val cur = Prefs.afterClose(this)
    val c = ouiCard()
    c.addView(LinearLayout(this).apply {
        setPadding(dp(6), dp(20), dp(6), dp(16))
        listOf(2 to "כרטיס", 3 to "ממוזער").forEachIndexed { i, (kind, label) ->
            addView(LinearLayout(this@chooseAfterClose).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(alertStylePreview(kind), LinearLayout.LayoutParams(dp(58), dp(116)))
                addLabelRadio(this, label, cur == i)
                setOnClickListener { Prefs.setAfterClose(this@chooseAfterClose, i); rebuild() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    })
    ouiAdd(body, c)
    ouiNote(body, if (cur == 1 && !android.provider.Settings.canDrawOverlays(this))
        "ממוזער צריך הרשאת \"הצגה מעל אפליקציות\" – בלעדיה יוצג כרטיס"
        else "כרטיס – במסך הראשי, מעל השעון\nממוזער – חלון קטן בראש המסך, מעל כל אפליקציה")
}

/** בחירת סוג התצוגה לנושא: אותן הדמיות כמו בסוג התצוגה הכללי, בצבע הנושא */
private fun MainActivity.chooseTopicStyle(level: Int, name: String, done: () -> Unit) = ouiPage(name, onClose = done) { body, rebuild ->
    val color = AlertUi.color(level)
    val cur = Prefs.alertStyleFor(this, level)
    val c = ouiCard()
    c.addView(text("סוג תצוגה", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
    val opts = LinearLayout(this).apply { setPadding(dp(6), dp(10), dp(6), dp(16)) }
    STYLE_NAMES.forEachIndexed { i, label ->
        opts.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(alertStylePreview(i, color), LinearLayout.LayoutParams(dp(58), dp(116)))
            addLabelRadio(this, label, cur == i)
            setOnClickListener { Prefs.setTopicStyle(this@chooseTopicStyle, level, i); rebuild() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }
    c.addView(opts)
    ouiDivider(c)
    ouiRow(c, "תצוגה מקדימה", sub = "רק איך זה נראה – בלי צליל ורטט") { previewAlertStyle(level) }
    ouiAdd(body, c)
}

/** הדמיה קטנה של סוג תצוגת ההתרעה: 0 מסך מלא · 1 פופ-אפ · 2 כרטיס · 3 ממוזער */
internal fun MainActivity.alertStylePreview(kind: Int, color: String = "#6E6E73"): View {
    fun rect(c: String, r: Int) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(r).toFloat() }
    // בהיר / חשוך לפי ערכת הצבעים
    val bg = if (C.LIGHT) "#D9D9DC" else "#2B2B2E"
    val block = if (C.LIGHT) "#BDBDC2" else "#3E3E42"
    val red = color   // בכללי אפור ניטרלי (חל על כל הנושאים); בהתאמה אישית - צבע הנושא
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
        // פופ-אפ: כרטיס באמצע, עם המפה בתוכו (כמו במסך המלא)
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

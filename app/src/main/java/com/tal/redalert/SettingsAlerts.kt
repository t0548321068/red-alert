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

// עמודי ההגדרות: התרעות - סוג התצוגה, מרחק, השתקה זמנית ובדיקת התרעה

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
    ouiRow(style, "תצוגה מקדימה", sub = "רק איך זה נראה – בלי צליל ורטט") { previewAlertStyle() }
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
    ouiAdd(body, c)
    // צלילים ורטט (ההקראה בתוכו)
    val snd = ouiCard()
    ouiRow(snd, "צלילים ורטט", sub = "הקראה · שעות שקט · נא לא להפריע · מצב שקט · שבת") { showSoundSettings() }
    ouiAdd(body, snd)
    // בדיקת התראה (עברה מהכפתור שבמסך הראשי)
    val t = ouiCard()
    ouiRow(t, "בדיקת התרעה", sub = "מקדימה · ירי · סיום") { chooseTest() }
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
                        "שעות שקט פעילות – ההתרעה נשלחה בשקט, בלי צליל ומסך מלא",
                        android.widget.Toast.LENGTH_LONG).show()
                }
                // סוגרים את עמודי ההגדרות - ההתרעה מוצגת מיד (גם כרטיס / ממוזער שבמסך הראשי)
                pages.toList().forEach { it.dismiss() }
                refresh()
            }
        }, LinearLayout.LayoutParams(-1, dp(56)))
    }
    ouiAdd(body, c)
    ouiNote(body, "ההתרעה נשלחת מיד, בדיוק כמו התרעה אמיתית")
}

/**
 * תצוגה מקדימה של סוג התצוגה שנבחר: רק במסך - בלי צליל, רטט, התראה, היסטוריה או ווידג'ט.
 * נסגרת לבד אחרי 20 שניות. כשיש התרעה אמיתית פעילה - לא מציגים (שלא תידרס)
 */
internal fun MainActivity.previewAlertStyle() {
    val real = Prefs.activeAlert(this)
    if (real != null && !AlertUi.expired(real.optInt("level"), real.optLong("firedAt"))) {
        android.widget.Toast.makeText(this, "יש התרעה פעילה – אפשר לנסות אחרי שתסתיים", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val areas = (Prefs.nearbyAreas(this).take(1) + Prefs.cities(this)).distinct().ifEmpty { listOf("ירושלים - צפון") }
    val body = areas.first()
    val shelter = AreaData.shelterSeconds(this, listOf(body)) ?: 90
    val now = System.currentTimeMillis()
    val title = "ירי רקטות וטילים"
    val src = "תצוגה מקדימה"
    val full = Intent(this, if (Prefs.alertStyle(this) == 1) AlertPopupActivity::class.java else AlertActivity::class.java)
        .putExtra("title", title).putExtra("body", body).putExtra("level", AlertService.LEVEL_ALERT)
        .putExtra("shelter", shelter).putExtra("firedAt", now).putExtra("source", src)
        .putExtra("autoCloseMs", 20_000L).putExtra("mapAreas", body)
    pages.toList().forEach { it.dismiss() }
    when (Prefs.alertStyle(this)) {
        0, 1 -> startActivity(full)
        2 -> {
            previewAlert = org.json.JSONObject().put("title", title).put("body", body).put("level", AlertService.LEVEL_ALERT)
                .put("shelter", shelter).put("firedAt", now).put("source", src).put("mapAreas", body)
            updateAlertCard()
            ui.postDelayed({ if (previewAlert?.optLong("firedAt") == now) { previewAlert = null; updateAlertCard() } }, 20_000L)
        }
        else -> if (MiniOverlay.canShow(this)) {
            MiniOverlay.show(this, full)
            ui.postDelayed({
                // לא סוגרים חלון של התרעה אמיתית שהגיעה בינתיים
                if ((Prefs.activeAlert(this)?.optLong("firedAt") ?: 0L) < now) MiniOverlay.hide(this) }, 20_000L)
        } else android.widget.Toast.makeText(this, "התצוגה הממוזערת צריכה הרשאת \"הצגה מעל אפליקציות\"", android.widget.Toast.LENGTH_LONG).show()
    }
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

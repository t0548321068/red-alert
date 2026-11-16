package com.tal.redalert

import android.widget.LinearLayout
import android.widget.TextView
import com.tal.redalert.MainActivity.C

// (חלק מהמסך הראשי - MainActivity; המצב עצמו נשמר שם)

// עמוד ההגדרות: מצב שבת

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
    ouiNote(body, "במצב אוטומטי, מצב שבת נדלק בכניסת השבת ונכבה ביציאתה – בלי לגעת")

    // מסך שבת
    val scr = ouiCard()
    val clk = Prefs.shabbatClock(this)
    ouiRow(scr, "שעון במסך", sub = if (clk) "המסך נשאר דלוק עם שעון, תאריך וצאת השבת" else "כבוי – המסך נכבה, ונדלק רק בהתרעה",
        on = clk) { Prefs.setShabbatClock(this, !clk); rebuild() }
    if (clk) {
        ouiDivider(scr)
        val sec = Prefs.shabbatSeconds(this)
        ouiRow(scr, "שניות", sub = onOff(sec), on = sec) { Prefs.setShabbatSeconds(this, !sec); rebuild() }
        ouiDivider(scr)
        val blink = Prefs.shabbatBlink(this)
        ouiRow(scr, "נקודתיים מהבהבות", sub = onOff(blink), on = blink) { Prefs.setShabbatBlink(this, !blink); rebuild() }
    }
    ouiAdd(body, scr)
    ouiNote(body, "בזמן מצב שבת המסך לא מגיב לנגיעות. יציאה – לחיצה ארוכה של 5 שניות על הכפתור בתחתית")

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
    ouiNote(body, "לפי המיקום וזמני עתים לבינה: כניסה לפי מנהג המקום (ירושלים 40 דק׳, חיפה 30, תל אביב 22...), יציאה בצאת הכוכבים")

    // מה מצב שבת עושה
    val info = ouiCard()
    info.addView(text("מה מצב שבת עושה", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), 0) })
    ouiText(info, listOf(
        "כל התרעה נפתחת במסך מלא, גם כשהטלפון בשימוש",
        "ההתרעה מוקראת בקול, גם אם ההקראה כבויה בהגדרות",
        "בהתרעה המסך נדלק, ואחרי 2 דקות נכבה (או חוזר לשעון) – אין צורך לגעת בטלפון",
        "עוקף הכול: שעות שקט, נא לא להפריע, מצב שקט של הטלפון, וגם צלילים ורטט כבויים"
    ).joinToString("\n") { "• $it" })
    ouiAdd(body, info)
}

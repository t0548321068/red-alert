package com.tal.redalert

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.tal.redalert.MainActivity.C
import com.tal.redalert.MainActivity.Row

// (חלק מהמסך הראשי - MainActivity; המצב עצמו נשמר שם)

// מסך ההגדרות הראשי, עמוד ההגנה, ורכיבים משותפים לעמודי ההגדרות

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
        "צופר חירום" to { listOf(Row("⏻", "הגנה",
            on = { Prefs.enabled(this) }, toggle = { toggle() }) { showProtection() }) },
        "תצוגה" to { listOf(Row("🎨", "תצוגה") { showDisplay() }) },
        "התרעות" to { listOf(
            Row("🔔", "התרעות") { showAlertSettings() }) },
        "הרשאות" to { phoneRows() },
        // עדכונים ואודות - כל אחד בכרטיס נפרד
        "עדכונים" to { listOf(Row("⬆️", "עדכונים") { showUpdates() }) },
        "אודות" to { listOf(Row("ℹ️", "אודות") { showAbout() }) }
        // יצירת קשר מושהית - כדי להחזיר: "יצירת קשר" to { listOf(Row("✉️", "יצירת קשר") { showContact() }) }
    )
    fun rebuild() {
        body.removeAllViews()
        // כותרת כמו בשאר מסכי ההגדרות
        body.addView(pageHeader("הגדרות"))
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
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(PAGE_SIDE), 0, dp(PAGE_SIDE), dp(12)) })
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

/** מסך הגנה: הפעלה/כיבוי, ותזמון - באילו ימים ושעות האפליקציה פעילה */
internal fun MainActivity.showProtection() = ouiPage("הגנה") { body, rebuild ->
    val main = ouiCard()
    ouiRow(main, "הגנה", on = Prefs.enabled(this)) { toggle(); rebuild() }
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

internal fun MainActivity.hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

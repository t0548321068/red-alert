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

// ערכת בנייה משותפת לכל המסכים: עמודים, כרטיסים, שורות, חלונות תחתונים, טקסט וצבעים
// (חלק מהמסך הראשי - MainActivity; המצב עצמו נשמר שם)

internal fun MainActivity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

// סגנון One UI: כרטיס אפור כהה אחיד עם פינות מעוגלות מאוד
internal fun MainActivity.graphite(@Suppress("UNUSED_PARAMETER") radius: Int) = GradientDrawable().apply {
    setColor(C.CARD2); cornerRadius = dp(28).toFloat() }

/** עמוד מלא: כותרת קטנה למעלה (כמו "תצוגה"), build נקרא מחדש בכל rebuild */
internal fun MainActivity.ouiPage(title: String, refreshOnFocus: Boolean = true, onClose: (() -> Unit)? = null,
                    button: Pair<String, (done: () -> Unit) -> Unit>? = null,
                    buttonLabel: ((TextView) -> Unit)? = null,
                    build: (LinearLayout, () -> Unit) -> Unit) {
    val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar)
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(30)) }
    val page = ScrollView(this).apply {
        setBackgroundColor(C.BG)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        addView(body)
        setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
    }
    lateinit var rebuild: () -> Unit
    rebuild = {
        body.removeAllViews()
        body.addView(text(title, 20f, C.TEXT, bold = true).apply { setPadding(dp(24), dp(14), dp(24), dp(14)) })
        build(body, rebuild)
    }
    rebuild()
    val frame = FrameLayout(this).apply { setBackgroundColor(C.BG) }
    frame.addView(page, FrameLayout.LayoutParams(-1, -1))
    frame.setOnApplyWindowInsetsListener { _, insets -> page.dispatchApplyWindowInsets(insets) }
    body.setPadding(0, 0, 0, dp(if (button == null) 30 else 100))
    if (button == null) d.setContentView(frame)
    else {
        // כפתור כחול גדול קבוע מעל התפריט (כמו "בדוק אם יש עדכונים" בסמסונג)
        // מידות לפי הצילום מסמסונג: 60% רוחב, גובה 46, עיגול מלא, כחול #4C7DFF, טקסט 16 מודגש
        // בלחיצה: המלל מתחלף לאייקון טעינה מונפש עד שהפעולה מסתיימת
        val label = text(button.first, 16f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        buttonLabel?.invoke(label)   // העמוד יכול להחליף את המלל (למשל "בדוק שוב" / "התקן עכשיו")
        val spinner = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
        }
        var busy = false
        val btn = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#4C7DFF")); cornerRadius = dp(23).toFloat() }
            addView(label, FrameLayout.LayoutParams(-1, -1))
            addView(spinner, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            setOnClickListener {
                if (busy) return@setOnClickListener
                busy = true; label.visibility = View.INVISIBLE; spinner.visibility = View.VISIBLE
                val started = System.currentTimeMillis()
                button.second {
                    // לפחות חצי שנייה של טעינה - שלא יהבהב
                    postDelayed({ busy = false; spinner.visibility = View.GONE; label.visibility = View.VISIBLE },
                        (500 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                }
            }
        }
        val above = dp(10)
        frame.addView(btn, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.5f).toInt(), dp(46),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = navMargin() + above })
        frame.viewTreeObserver.addOnGlobalLayoutListener {
            val want = navMargin() + above
            val lp = btn.layoutParams as FrameLayout.LayoutParams
            if (lp.bottomMargin != want) { lp.bottomMargin = want; btn.layoutParams = lp }
        }
        d.setContentView(frame)
    }
    d.window?.setLayout(-1, -1)
    @Suppress("DEPRECATION")
    d.window?.statusBarColor = C.BG
    edgeToEdge(d)
    if (refreshOnFocus) refreshOnReturn(d) { rebuild() }
    track(d, onClose)
    d.show()
}

internal fun MainActivity.ouiCard() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = graphite(26); clipToOutline = true }

internal fun MainActivity.ouiAdd(body: LinearLayout, c: View) =
    body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), 0, dp(10), dp(8)) })

internal fun MainActivity.ouiNote(body: LinearLayout, t: String) =
    body.addView(text(t, 11f, C.MUTED2).apply { setPadding(dp(26), 0, dp(26), dp(14)) })

internal fun MainActivity.ouiDivider(c: LinearLayout) = c.addView(View(this).apply { setBackgroundColor(C.DIV) },
    LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(18), 0, dp(18), 0) })

/** כפתור בחירה כמו בסמסונג: טבעת, ובנבחר נקודה כחולה */
internal fun MainActivity.ouiRadio(sel: Boolean) = FrameLayout(this).apply {
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(2), Color.parseColor(if (sel) "#3E82F7" else "#6E6E73"))
    }
    if (sel) addView(View(this@ouiRadio).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#3E82F7")) }
    }, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
}

/**
 * אזור המתג בשורה: מהקו המפריד ועד קצה השורה, לכל גובה השורה - לחיצה בכל האזור מדליקה/מכבה
 * (המתג עצמו קטן מדי, ולחיצה לידו פתחה את המסך של השורה)
 */
internal fun MainActivity.switchZone(row: LinearLayout, on: Boolean, padEnd: Int, toggle: () -> Unit) {
    // בלי לשנות את הריווח של השורה (שינוי לפני שהכיוון ימין-לשמאל נקבע הזיז את הטקסט לקצה בפתיחה):
    // האזור נמתח עד הקצה עם שוליים שליליים בגודל הריווח
    val z = LinearLayout(this).apply {
        // כיוון מפורש: הריווח היחסי נקבע מיד כימין-לשמאל. בלי זה, בבנייה לפני שהעמוד מוצג
        // הריווח נקבע כשמאל-לימין והמתג נחתך בקצה (עד עכשיו בנייה שנייה מיד בפתיחה הסתירה את זה - והייתה קפיצה)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dp(12), 0, padEnd, 0)
        isClickable = true
        setOnClickListener { toggle() }
    }
    z.addView(View(this).apply { setBackgroundColor(C.SEPV) },
        LinearLayout.LayoutParams(dp(1), dp(20)).apply { marginEnd = dp(12) })
    z.addView(OuiSwitch(this, on, C.LIGHT).apply { isClickable = false })
    row.layoutDirection = View.LAYOUT_DIRECTION_RTL
    row.addView(z, LinearLayout.LayoutParams(-2, -1).apply { marginEnd = -padEnd })
}

/**
 * שורה בגובה קבוע (48, או 56 עם שורת מצב): שם, מצב בכחול מתחת,
 * ובצד: ערך (end), כפתור בחירה (radio) או מתג (on)
 */
internal fun MainActivity.ouiRow(c: LinearLayout, title: String, sub: String = "", subColor: Int = OUI_BLUE,
                   end: String? = null, endColor: Int = C.TEXT, endBig: Boolean = false,
                   radio: Boolean? = null, on: Boolean? = null, onToggle: (() -> Unit)? = null,
                   click: (() -> Unit)? = null) {
    val r = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(18), 0)
        if (click != null) setOnClickListener { click() }
    }
    val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    t.addView(text(title, 16f, C.TEXT).apply { includeFontPadding = false })
    if (sub.isNotEmpty()) t.addView(text(sub, 12f, subColor).apply { includeFontPadding = false; setPadding(0, dp(4), 0, 0) })
    r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
    if (end != null) r.addView(text(end, if (endBig) 22f else 14f, endColor, bold = endBig).apply { includeFontPadding = false },
        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
    if (radio != null) r.addView(ouiRadio(radio), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(10) })
    // onToggle: לחיצה על השורה פותחת הגדרה, והמתג לבד מדליק/מכבה (כמו בעמוד תצוגה)
    // רווחים זהים בכל האפליקציה: 12 מהטקסט לקו, והקו צמוד למתג
    if (on != null && onToggle != null) switchZone(r, on, dp(18)) { onToggle() }
    else if (on != null) r.addView(OuiSwitch(this, on, C.LIGHT).apply { isClickable = false },
        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
    c.addView(r, LinearLayout.LayoutParams(-1, dp(if (sub.isEmpty()) 48 else 56)))
}

/** טקסט חופשי בתוך כרטיס (הסברים, מה חדש) */
internal fun MainActivity.ouiText(c: LinearLayout, t: String) =
    c.addView(text(t, 14f, C.TEXT).apply { setPadding(dp(18), dp(12), dp(18), dp(12)); setLineSpacing(dp(3).toFloat(), 1f) })

/** עמוד בסגנון One UI: כותרת גדולה ותוכן נגלל (למשל "התרעות אחרונות") */
internal fun MainActivity.onePage(title: String, action: Pair<Int, () -> Unit>? = null, headerExtra: ((LinearLayout) -> Unit)? = null,
                    build: (LinearLayout) -> Unit) {
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
    // כותרת כמו בשאר מסכי ההגדרות, ואם יש - כפתור פעולה בצד (למשל מפה)
    body.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(14), dp(12), dp(14))
        addView(text(title, 20f, C.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        headerExtra?.invoke(this)
        action?.let { (icon, run) ->
            // כמו האייקונים בראש המסך הראשי: בלי רקע, רק הבהוב בלחיצה
            addView(android.widget.ImageView(this@onePage).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(C.TEXT)
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(C.LINE2), null,
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
                setOnClickListener { run() }
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
        }
    })
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(content)
    body.addView(View(this), LinearLayout.LayoutParams(-1, dp(30)))
    build(content)
    d.setContentView(page)
    d.window?.setLayout(-1, -1)
    @Suppress("DEPRECATION")
    d.window?.statusBarColor = C.BG
    edgeToEdge(d)
    track(d)
    d.show()
}

/**
 * חלון קופץ בסגנון סמסונג (כמו "הגדר שעה"): כרטיס אפור מעוגל בתחתית המסך, כותרת מודגשת,
 * תוכן, ולמטה שני כפתורים עם קו מפריד
 */
internal fun MainActivity.ouiSheet(title: String, cancel: String, ok: String, onOk: () -> Unit,
                     content: (LinearLayout, Int) -> Unit) {
    // אותו חלון כמו כל ההודעות באפליקציה (AppSheet), עם תוכן משלנו
    AppSheet.show(this, title, "", ok, onOk, cancel = cancel, content = content)?.let { track(it) }
}

/** מסך מלא עם גלולה צפה למטה כמו בשעון של סמסונג: ביטול | שמור */
internal fun MainActivity.showWithCancelSave(d: android.app.Dialog, root: View, onSave: () -> Unit) {
    val frame = FrameLayout(this).apply { setBackgroundColor(C.BG) }
    frame.addView(root, FrameLayout.LayoutParams(-1, -1))
    val save = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        // כמו בשעון של סמסונג: גלולה כהה, טקסט לבן מודגש, קו מפריד דק
        background = GradientDrawable().apply { setColor(if (C.LIGHT) Color.parseColor("#E3E3E8") else Color.parseColor("#252525")) }
        addView(text("ביטול", 17f, C.TEXT, bold = true).apply {
            gravity = Gravity.CENTER; setOnClickListener { d.dismiss() } }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(View(this@showWithCancelSave).apply { setBackgroundColor(C.SEPV) }, LinearLayout.LayoutParams(dp(1), dp(20)))
        addView(text("שמור", 17f, C.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onSave(); d.dismiss() }
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }
    // מידות לפי הצילום מהשעון של סמסונג: רוחב 47% מהמסך, גובה 12.35% מרוחב המסך
    val pillW = (resources.displayMetrics.widthPixels * 0.47f).toInt()
    val pillH = (resources.displayMetrics.widthPixels * 0.1235f).toInt()
    (save.background as GradientDrawable).cornerRadius = pillH / 2f
    frame.addView(save, FrameLayout.LayoutParams(pillW, pillH,
        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = navMargin() })
    frame.setOnApplyWindowInsetsListener { _, insets ->
        @Suppress("DEPRECATION")
        root.setPadding(0, insets.systemWindowInsetTop, 0, 0)
        @Suppress("DEPRECATION")
        (save.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = insets.systemWindowInsetBottom + dp(10)
            leftMargin = insets.systemWindowInsetLeft + dp(10); rightMargin = insets.systemWindowInsetRight + dp(10) }
        save.requestLayout()
        insets
    }
    d.setContentView(frame)
    d.window?.setLayout(-1, -1)
    @Suppress("DEPRECATION")
    d.window?.statusBarColor = C.BG
    edgeToEdge(d)
    track(d)
    d.show()
}

/**
 * עמודים שנפתחים כחלון (הגדרות, התרעות, תצוגה) - מצוירים מתחת לשורת המצב ולסרגל הניווט כמו המסך הראשי,
 * כדי שהתפריט התחתון יישב בדיוק באותו מקום בכל המסכים (בלי לזוז במעבר בין לשוניות)
 */
/** מרחק התפריט התחתון מהתחתית - מחושב מיד (לא מחכים לאירוע insets), כדי שלא יקפוץ בפתיחה */
internal fun MainActivity.navMargin(): Int {
    @Suppress("DEPRECATION")
    val inset = window?.decorView?.rootWindowInsets?.systemWindowInsetBottom ?: 0
    return inset + dp(10)
}

/**
 * חזרה לעמוד (מחלון בחירה / הגדרות הטלפון) - בונים אותו מחדש כדי לעדכן מצבים.
 * לא בפתיחה עצמה: העמוד כבר נבנה, ובנייה כפולה מיד אחרי הפתיחה גרמה לקפיצה בשורות עם מתגים
 */
internal fun MainActivity.refreshOnReturn(d: android.app.Dialog, rebuild: () -> Unit) {
    var opened = false
    d.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { has ->
        if (has) { if (opened) rebuild() else opened = true }
    }
}

internal fun MainActivity.edgeToEdge(d: android.app.Dialog) {
    val w = d.window ?: return
    // בלי אנימציית פתיחה/סגירה - העמוד מופיע במקום, והתפריט התחתון לא זז
    w.setWindowAnimations(0)
    // החלונות בנויים על ערכת "Theme.Black" הישנה - בה המערכת מציירת לשורת המצב רקע שחור משלה.
    // בעיצוב בהיר האייקונים כהים, אז על השחור לא רואים שעה/סוללה. מבקשים לצייר את הרקע בעצמנו בצבע העמוד
    @Suppress("DEPRECATION")
    w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
        android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    @Suppress("DEPRECATION")
    w.statusBarColor = C.BG
    w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C.BG))
    if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false)
    else @Suppress("DEPRECATION") { w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        (if (C.LIGHT) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0) }
    @Suppress("DEPRECATION")
    w.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 30 && C.LIGHT) {
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        w.insetsController?.setSystemBarsAppearance(light, light)
    }
    // הריווח לשורת המצב כבר מהציור הראשון (לפי המסך הראשי) - בלי שהתוכן יקפוץ למטה כשהריווח מגיע מהמערכת
    try {
        window.decorView.rootWindowInsets?.let { ins ->
            (w.decorView.findViewById<ViewGroup>(android.R.id.content))?.getChildAt(0)?.dispatchApplyWindowInsets(ins)
        }
    } catch (_: Exception) { }
}

/** כיתוב ממורכז + כפתור בחירה עגול (מתחת לתצוגה מקדימה) */
internal fun MainActivity.addLabelRadio(parent: LinearLayout, label: String, sel: Boolean) {
    parent.addView(text(label, 13f, if (sel) Color.parseColor("#5AA9FF") else C.MUTED2, bold = sel).apply {
        gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
    }, LinearLayout.LayoutParams(-1, -2))
    // כפתור בחירה כמו בסמסונג: טבעת, ובנבחר - נקודה כחולה בתוכה עם רווח
    parent.addView(FrameLayout(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), Color.parseColor(if (sel) "#3E82F7" else "#6E6E73"))
        }
        if (sel) addView(View(this@addLabelRadio).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#3E82F7")) }
        }, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
    }, LinearLayout.LayoutParams(dp(20), dp(20)))
}

internal fun MainActivity.text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

internal fun MainActivity.track(d: android.app.Dialog, onClose: (() -> Unit)? = null) {
    pages.add(d)
    d.setOnDismissListener { pages.remove(d); onClose?.invoke() }
}

/** אייקון בקו לבן לכל שורה בהגדרות (לפי האמוג'י שלה) */
internal fun MainActivity.setIcon(icon: String) = when (icon) {
    "⏻" -> R.drawable.ic_set_shield; "🎨" -> R.drawable.ic_set_display; "📳" -> R.drawable.ic_set_sound
    "🔔" -> R.drawable.ic_set_bell; "🛡" -> R.drawable.ic_set_lock; "⬆️" -> R.drawable.ic_set_update
    "ℹ️" -> R.drawable.ic_set_info
    else -> 0
}

/** צבע העיגול של כל אייקון בהגדרות (סגנון One UI) */
internal fun MainActivity.iconColor(icon: String) = Color.parseColor(when (icon) {
    "⏻" -> "#D50000"; "🎨" -> "#7E57C2"; "📳" -> "#8E24AA"; "🔔" -> "#FB8C00"
    "🛡" -> "#00897B"; "⬆️" -> "#1E88E5"
    else -> "#546E7A"
})

/** הגדרות: מגירה שנפתחת מצד ימין, כל קטגוריה נפתחת/נסגרת במקום */
// ---- עמודי הגדרות בסגנון One UI - אותם רכיבים כמו בעמוד "תצוגה" ----
internal val MainActivity.OUI_BLUE get() = Color.parseColor("#5AA9FF")

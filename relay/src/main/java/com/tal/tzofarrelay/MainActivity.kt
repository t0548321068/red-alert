package com.tal.tzofarrelay

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** מסך אחד: הקלדת המפתח בפעם הראשונה, ואחר כך מצב הממסר (מתעדכן כל שנייה) */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    // אותם צבעים כמו בצופר חירום 4.0 - חשוך / בהיר לפי הגדרת התצוגה
    private var GREEN = 0; private var ORANGE = 0; private var RED = 0; private var MUTED = 0
    private var TEXT = 0; private var MUTED2 = 0; private var CARD = 0; private var DIV = 0
    private var SEPV = 0; private var LINE2 = 0; private var SOFT = 0; private var BG = 0
    private var LIGHT = false
    private val BLUE = Color.parseColor("#5AA9FF"); private val PILL = Color.parseColor("#4C7DFF")
    private fun applyTheme(dark: Boolean) {
        fun c(d: String, l: String) = Color.parseColor(if (dark) d else l)
        BG = c("#000000", "#F2F2F7"); TEXT = c("#F2F2F2", "#1C1C1E"); MUTED = c("#8E8E93", "#6E6E73")
        GREEN = c("#30D158", "#248A3D"); RED = c("#FF3B30", "#E0352B"); ORANGE = c("#FF9F0A", "#E08600")
        CARD = c("#1B1B1D", "#FFFFFF"); DIV = c("#2A2A2C", "#E0E0E5"); SEPV = c("#5C5C5E", "#C7C7CC")
        SOFT = c("#E6FFFFFF", "#CC1C1C1E"); MUTED2 = c("#AAAAAA", "#6E6E73"); LINE2 = c("#22FFFFFF", "#22000000")
        LIGHT = !dark
        if (::frame.isInitialized) {
            frame.setBackgroundColor(BG); scroll.setBackgroundColor(BG)
            later.setTextColor(TEXT)
            (later.background as GradientDrawable).setColor(c("#3A3A3C", "#E5E5EA"))
        }
        @Suppress("DEPRECATION") window.statusBarColor = BG
        @Suppress("DEPRECATION") window.navigationBarColor = BG
        // אייקונים כהים בשורת המצב בבהיר
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
    }
    private lateinit var frame: android.widget.FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout
    private lateinit var pill: android.widget.FrameLayout
    private lateinit var pillLabel: TextView
    private lateinit var pillSpin: android.widget.ProgressBar
    /** מצב העיגול: צבע, צבע שני (מתחלפים כל חצי שנייה), הבהוב */
    private class St(val c1: Int, val c2: Int?, val blink: Boolean, val title: String, val sub: String)
    private var state: St? = null
    private var circleViews: Triple<View, GradientDrawable, ImageView>? = null
    private var titleView: TextView? = null
    /** העיגול מתעדכן כל 50ms (החלפת צבע / הבהוב), בלי לבנות את המסך מחדש */
    private val anim = object : Runnable { override fun run() { paint(); ui.postDelayed(this, 50) } }
    private var clockView: TextView? = null
    private fun paint() {
        // השעון מתעדכן רק כשמשהו בו משתנה (שנייה חדשה / הנקודתיים מהבהבות)
        clockView?.let { v ->
            val ms = System.currentTimeMillis()
            val k = TimeFormat.time(this, ms) + (Display.blinkColon(this) && ms % 1000 >= 500)
            if (v.tag != k) { v.tag = k; v.text = TimeFormat.clock(this, ms) }
        }
        val st = state ?: return
        val (circle, bg, icon) = circleViews ?: return
        val now = System.currentTimeMillis()
        val c = if (st.c2 != null && (now / 500) % 2 == 1L) st.c2 else st.c1
        icon.setColorFilter(c); titleView?.setTextColor(c)
        bg.setColor(Color.argb(60, Color.red(c), Color.green(c), Color.blue(c)))
        // הבהוב: 1 → 0.25 → 1 בשנייה (כמו באפליקציה)
        circle.alpha = if (st.blink) { val ph = (now % 1000) / 500f; 0.25f + 0.75f * (if (ph < 1f) 1f - ph else ph - 1f) } else 1f
    }
    private val tick = object : Runnable { override fun run() { build(); ui.postDelayed(this, 1000) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(t: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    // כרטיס כמו בצופר חירום: אפור כהה, פינות 28
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; clipToOutline = true
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(28).toFloat() }
    }
    private fun add(v: View) = root.addView(v, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(12)) })
    private fun note(t: String) = root.addView(text(t, 11f, MUTED2).apply { setPadding(dp(32), 0, dp(32), dp(14)) })
    private fun divider(c: LinearLayout) = c.addView(View(this).apply { setBackgroundColor(DIV) },
        LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(18), 0, dp(18), 0) })
    /** שורה בגובה 58 כמו בהגדרות של צופר חירום: שם 16, שורת משנה 12, ערך 14 בצד */
    private fun row(c: LinearLayout, title: String, sub: String, end: String, endColor: Int, click: (() -> Unit)? = null) {
        if (c.childCount > 0 && c.getChildAt(c.childCount - 1).tag == "row") divider(c)
        c.addView(LinearLayout(this).apply {
            tag = "row"
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0)
            if (click != null) setOnClickListener { click() }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 16f).apply { includeFontPadding = false })
                if (sub.isNotEmpty()) addView(text(sub, 12f, MUTED).apply { includeFontPadding = false; setPadding(0, dp(4), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (end.isNotEmpty()) addView(text(end, 14f, endColor).apply { includeFontPadding = false },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        }, LinearLayout.LayoutParams(-1, dp(58)))
    }
    private fun ago(t: Long): String {
        if (t == 0L) return "עוד לא"
        val s = ((System.currentTimeMillis() - t) / 1000).coerceAtLeast(0)
        return when { s < 60 -> "לפני $s שניות"; s < 3600 -> "לפני ${s / 60} דקות"; else -> "לפני ${s / 3600} שעות" }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        applyTheme(Display.dark(this))
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(100)) }
        scroll = ScrollView(this).apply { setBackgroundColor(BG); isVerticalScrollBarEnabled = false; addView(root) }   // בלי פס גלילה
        // הכפתור הכחול הצף למטה - בדיוק כמו "בדיקה" בעמוד הרשת של צופר חירום: חצי רוחב, גובה 46, טקסט 16 מודגש
        pillLabel = text("בדיקה", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        pillSpin = android.widget.ProgressBar(this).apply {
            isIndeterminate = true; visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        pill = android.widget.FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(PILL); cornerRadius = dp(23).toFloat() }
            addView(pillLabel, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(pillSpin, android.widget.FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            setOnClickListener { pillAction() }
        }
        later = text("מאוחר יותר", 16f, TEXT, true).apply {
            gravity = Gravity.CENTER; includeFontPadding = false; visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.parseColor("#3A3A3C")); cornerRadius = dp(23).toFloat() }
            setOnClickListener { back() }
        }
        frame = android.widget.FrameLayout(this)
        applyTheme(Display.dark(this))
        setContentView(frame.apply {
            addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(pill, android.widget.FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.5f).toInt(), dp(46),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(20) })
            addView(later, android.widget.FrameLayout.LayoutParams(0, dp(46), Gravity.BOTTOM or Gravity.RIGHT).apply { bottomMargin = dp(20) })
        })
        if (Build_SDK >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
    }
    private lateinit var later: TextView
    private var pillAction: () -> Unit = {}
    /** "בדיקה" בהגדרות: שני השרתים, המקורות ו-Firebase */
    private val checkNow: () -> Unit = {
        if (!checking) {
            checking = true; pillBusy(true)
            val started = System.currentTimeMillis()
            Thread {
                Relay.checkNow(this@MainActivity)
                // לפחות חצי שנייה של טעינה - שלא יהבהב
                ui.postDelayed({ checking = false; pillBusy(false); build() },
                    (500 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            }.start()
        }
    }
    /** הכפתור הכחול: לבד באמצע (חצי רוחב), או "התקן עכשיו" משמאל ו"מאוחר יותר" מימין - כמו בצופר חירום */
    private fun pills(label: String?, two: Boolean = false, action: () -> Unit = {}) {
        pillLabel.text = label ?: ""; pillAction = action
        pill.visibility = if (label == null) View.GONE else View.VISIBLE
        later.visibility = if (two && label != null) View.VISIBLE else View.GONE
        val w = resources.displayMetrics.widthPixels
        val lp = pill.layoutParams as android.widget.FrameLayout.LayoutParams
        if (two) {
            val each = (w * 0.42f).toInt(); val side = (w * 0.06f).toInt()
            lp.width = each; lp.gravity = Gravity.BOTTOM or Gravity.LEFT; lp.leftMargin = side
            (later.layoutParams as android.widget.FrameLayout.LayoutParams).apply { width = each; rightMargin = side }
            later.requestLayout()
        } else { lp.width = (w * 0.5f).toInt(); lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.leftMargin = 0 }
        pill.layoutParams = lp
    }
    private fun pillBusy(b: Boolean) { pillLabel.visibility = if (b) View.INVISIBLE else View.VISIBLE; pillSpin.visibility = if (b) View.VISIBLE else View.GONE }
    private val Build_SDK get() = android.os.Build.VERSION.SDK_INT

    override fun onResume() { super.onResume(); touchedAt = System.currentTimeMillis(); RelayService.start(this); ui.post(tick); ui.post(anim) }
    override fun onPause() { ui.removeCallbacks(tick); ui.removeCallbacks(anim); super.onPause() }

    /** כותרת עמוד כמו "צופר חירום" במסך הראשי: 24 מודגש, ובמסך הראשי גלגל שיניים בשמאל (46, הבהוב בלחיצה) */
    private fun header(title: String, gear: (() -> Unit)?) {
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(10 + 46 + 12)
            setPadding(dp(16), dp(10), dp(16), dp(12))
            addView(text(title, 24f, bold = true).apply { setPadding(dp(4), 0, dp(4), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            if (gear != null) addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_gear)
                imageTintList = android.content.res.ColorStateList.valueOf(TEXT)
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(LINE2), null,
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
                setOnClickListener { gear() }
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
        })
    }
    // עמודים: 0 ראשי, 1 הגדרות, 2 בדיקת עדכונים, 3 תצוגה, 4 לפי זמן, 5 שורת חיוויים, 6 שעון, 7 תאריך.
    // מחסנית - "חזרה" חוזרת לעמוד הקודם
    private val stack = mutableListOf(0)
    private val page get() = stack.last()
    private fun go(p: Int) { stack.add(p); show() }
    private fun back() { if (stack.size > 1) { stack.removeAt(stack.size - 1); show() } }
    /** בונה את העמוד הנוכחי. ראשי והגדרות מתעדכנים כל שנייה; שאר העמודים - רק כשמשהו בהם משתנה */
    private fun show() {
        scroll.scrollTo(0, 0)
        when (page) { 2 -> updatePage(); 3 -> displayPage(); 4 -> themeTimePage(); 5 -> statusModePage(); 6 -> clockPage(); 7 -> datePage(); else -> build() }
    }
    @Deprecated("") override fun onBackPressed() { if (stack.size > 1) back() else @Suppress("DEPRECATION") super.onBackPressed() }
    /** שורת החיוויים "לפי זמן": מוצגת כשפותחים או נוגעים במסך, ונעלמת אחרי הזמן שנבחר */
    private var touchedAt = 0L
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val was = statusShown(); touchedAt = System.currentTimeMillis()
            if (!was && page == 0) build()
        }
        return super.dispatchTouchEvent(ev)
    }
    private fun statusShown() = when (Display.statusMode(this)) {
        Display.STATUS_HIDDEN -> false
        Display.STATUS_TIMED -> System.currentTimeMillis() - touchedAt < Display.statusSeconds(this) * 1000L
        else -> true
    }

    @Volatile private var checking = false
    private var keyField: EditText? = null
    private var setupMsg = ""
    private fun build() {
        // ערכת הצבעים מתחלפת לבד ("לפי המערכת" / "לפי זמן")
        if (Display.dark(this) == LIGHT) { applyTheme(!LIGHT); if (page > 1) { show(); return } }
        if (page > 1) return   // שאר העמודים לא נבנים מחדש כל שנייה
        if (Relay.key(this).isEmpty()) { state = null; circleViews = null; if (keyField == null) setup(); return }
        keyField = null
        root.removeAllViews()
        val now = System.currentTimeMillis()
        val on = Relay.enabled(this) && RelayService.isRunning
        val charging = charging(); val wifi = onWifi()
        val hasKey = Relay.fcmKey(this).isNotEmpty()
        val primary = now - Relay.primaryOk < 90_000
        val secondary = now - Relay.secondaryOk < 90_000
        // אותה שיטה כמו חיווי החיבור בצופר חירום: צבע קבוע / שני צבעים שמתחלפים כל חצי שנייה / הבהוב
        val upN = Relay.sources.count { now - it.lastOk < it.everyMs * 3 + 10_000 }
        val st = when {
            !on -> St(MUTED, null, false, "הממסר כבוי", "לא ישמש כגיבוי")
            Relay.lastCheckAt == 0L -> St(GREEN, null, true, "מתחבר…", "בודק את השרתים")
            !Relay.active && primary && (!charging || !wifi || !hasKey) ->
                St(ORANGE, null, false, "בהמתנה – יש סיכון", if (!hasKey) "עוד אין מפתח Firebase" else if (!charging) "הטלפון לא מחובר לחשמל" else "הטלפון לא מחובר ל‑Wi‑Fi")
            !Relay.active && primary -> St(GREEN, null, false, "בהמתנה", "השרת הראשי עובד · פועל " + uptime(now - Relay.startedAt))
            !Relay.active && secondary && Relay.secondaryActive -> St(GREEN, ORANGE, false, "בהמתנה – השרת המשני פעיל", "השרת הראשי לא עונה")
            !Relay.active -> St(ORANGE, null, true, "השרתים לא עונים", "נכנס לפעולה בעוד רגע")
            upN == 0 -> St(RED, null, false, "פעיל – אין מקורות", Relay.sources[0].error.ifEmpty { "אף מקור לא עונה" })
            upN < Relay.sources.size -> St(ORANGE, RED, false, "פעיל חלקית", "$upN/${Relay.sources.size} מקורות עונים")
            else -> St(ORANGE, null, true, "פעיל – שולח לטלפונים", "השרתים לא עונים · " + uptime(now - Relay.activeSince))
        }
        state = st
        if (page == 1) settingsPage(now, on, charging, wifi, hasKey, primary, secondary) else mainPage(st, now, on, charging, wifi, primary, secondary)
    }

    /** מסך ראשי כמו בצופר חירום: כותרת עם גלגל שיניים, שורת חיוויים, וכרטיס המצב */
    private fun mainPage(st: St, now: Long, on: Boolean, charging: Boolean, wifi: Boolean, primary: Boolean, secondary: Boolean) {
        pills(null)
        header("ממסר צופר") { go(1) }
        // שורת חיוויים: אייקון בצבע המצב, מתחתיו מלל קטן, קווים דקים ביניהם (כמו בצופר חירום)
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val padV = ((7f - 22f * (1.3f - 1.15f) / 2f) * resources.displayMetrics.density).toInt()
            setPadding(dp(6), padV, dp(6), padV)
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(28).toFloat() }
        }
        val enabled = Relay.enabled(this)
        val srv = when { !on -> "כבוי" to MUTED; primary -> "ראשי" to GREEN; secondary -> "משני" to ORANGE; else -> "לא עונים" to RED }
        val inds = listOf(
            ind(R.drawable.ic_power, if (enabled) "פועל" else "כבוי", if (enabled) GREEN else MUTED) { toggleRelay() },
            ind(R.drawable.ic_antenna, srv.first, srv.second, null),
            ind(R.drawable.ic_globe, if (wifi) "Wi‑Fi" else "לא Wi‑Fi", if (wifi) GREEN else ORANGE, null),
            ind(R.drawable.ic_battery, if (charging) "בטעינה" else "${battery()}%", if (charging) GREEN else ORANGE, null),
            ind(R.drawable.ic_info, "1.${Update.current(this)}", TEXT) { go(2) })   // גרסה: עמוד בדיקת העדכונים
        inds.forEachIndexed { i, v ->
            if (i > 0) row.addView(View(this).apply { setBackgroundColor(LINE2) }, LinearLayout.LayoutParams(dp(1), dp(26)))
            row.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }
        if (statusShown()) add(row)
        // כרטיס המצב - כמו כרטיס השעון בצופר חירום: אנטנה בעיגול, מצב, שעה ותאריך
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#232526"), Color.parseColor("#414345"))).apply { cornerRadius = dp(24).toFloat() }
        }
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val icon = ImageView(this).apply { setImageResource(R.drawable.ic_antenna) }
        val circle = android.widget.FrameLayout(this).apply {
            background = bg
            addView(icon, android.widget.FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        c.addView(circle, LinearLayout.LayoutParams(dp(56), dp(56)))
        c.addView(text(st.title, 22f, TEXT, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        c.addView(text(st.sub, 14f, SOFT).apply { gravity = Gravity.CENTER })
        // שעון ותאריך - כמו בצופר חירום, לפי הגדרות התצוגה (שניות, נקודתיים מהבהבות, סוג תאריך וסגנון יום)
        val clock = text("", 46f, TEXT).apply { gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD); text = TimeFormat.clock(this@MainActivity, now) }
        c.addView(clock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        clockView = clock
        c.addView(text(TimeFormat.dayLine(this, now), 19f, SOFT).apply { gravity = Gravity.CENTER })
        c.addView(text("התרעות שנשלחו היום: ${Relay.forwardedToday(this)}", 14f, SOFT).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        circleViews = Triple(circle, bg, icon); titleView = null
        paint()
        add(c)
        // המקורות - רק כשהממסר פעיל
        if (on && Relay.active) {
            val src = card()
            src.addView(text("מקורות", 16f).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
            Relay.sources.forEach { s ->
                val ok = now - s.lastOk < s.everyMs * 3 + 10_000
                row(src, s.name, "בדיקה אחרונה ${ago(s.lastOk)}", if (ok) "מחובר" else s.error.ifEmpty { "מתחבר…" }, if (ok) GREEN else RED)
            }
            if (Relay.fcmError.isNotEmpty()) row(src, "Firebase", "", Relay.fcmError, RED)
            add(src)
        }
    }
    /** חיווי אחד בשורה - כמו בצופר חירום: אייקון מוגדל ב-45%, מלל 12 מתחתיו */
    private fun ind(icon: Int, label: String, color: Int, click: (() -> Unit)?) = text(label, 12f, TEXT).apply {
        getDrawable(icon)?.mutate()?.let { d ->
            d.setBounds(0, 0, (d.intrinsicWidth * 1.45f).toInt(), (d.intrinsicHeight * 1.45f).toInt())
            setCompoundDrawablesRelative(null, d, null, null)
        }
        compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
        compoundDrawablePadding = (dp(4) - (dp(18) * 0.15f).toInt()).coerceAtLeast(0)
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        if (click != null) setOnClickListener { click() }   // רשת / חשמל / שרתים - חיווי בלבד, לא כפתור
    }
    private fun toggleRelay() {
        val enabled = Relay.enabled(this)
        Relay.setEnabled(this, !enabled)
        if (!enabled) RelayService.start(this) else RelayService.stop(this)
        build()
    }

    /** הגדרות: הפעלה, שרשרת הגיבוי, מצב הטלפון, עדכונים והחלפת מפתח - עם כפתור "בדיקה" צף */
    private fun settingsPage(now: Long, on: Boolean, charging: Boolean, wifi: Boolean, hasKey: Boolean, primary: Boolean, secondary: Boolean) {
        pills("בדיקה", action = checkNow); circleViews = null; clockView = null
        header("הגדרות", null)
        val enabled = Relay.enabled(this)
        val top = card()
        top.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0)
            addView(text("ממסר", 16f).apply { includeFontPadding = false }, LinearLayout.LayoutParams(0, -2, 1f))
            // קו דק לפני המתג, כמו בצופר חירום
            addView(View(this@MainActivity).apply { setBackgroundColor(SEPV) },
                LinearLayout.LayoutParams(dp(1), dp(20)).apply { marginEnd = dp(12) })
            addView(OuiSwitch(this@MainActivity, enabled, LIGHT))
            setOnClickListener { toggleRelay() }
        }, LinearLayout.LayoutParams(-1, dp(58)))
        add(top)
        note("גיבוי שלישי: שולח לטלפונים רק כששני השרתים לא עונים")
        val disp = card()
        row(disp, "תצוגה", "", "", TEXT) { go(3) }
        add(disp)

        // בדיקה עכשיו: שני השרתים, המקורות ו-Firebase, ומתי בוצעה הבדיקה האחרונה
        val chk = card()
        val (chkAt, chkRes) = Relay.lastCheck(this)
        val fmt = java.text.SimpleDateFormat(if (now - chkAt < 20 * 3600_000L) "HH:mm:ss" else "dd/MM HH:mm", java.util.Locale.US)
        row(chk, "בדיקה אחרונה", if (chkAt > 0) chkRes else "", if (chkAt > 0) fmt.format(java.util.Date(chkAt)) else "עוד לא בוצעה",
            if (chkAt == 0L) MUTED else if (chkRes == "הכול תקין") GREEN else RED)
        add(chk)

        // שרשרת הגיבוי
        val chain = card()
        chain.addView(text("שרשרת הגיבוי", 16f).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
        row(chain, "1. שרת ראשי (Oracle)", if (on) "בדיקה אחרונה ${ago(Relay.lastCheckAt)}" else "", if (!on) "—" else if (primary) "מחובר" else "לא עונה",
            if (!on) MUTED else if (primary) GREEN else RED)
        row(chain, "2. שרת משני (Cloudflare)", "", if (!on) "—" else if (!secondary) "לא עונה" else if (Relay.secondaryActive) "פעיל" else "בהמתנה",
            if (!on) MUTED else if (!secondary) RED else if (Relay.secondaryActive) ORANGE else GREEN)
        row(chain, "3. ממסר (הטלפון הזה)", "", if (!on) "כבוי" else if (Relay.active) "פעיל" else "בהמתנה", if (!on) MUTED else if (Relay.active) ORANGE else GREEN)
        row(chain, "מפתח Firebase", "לשליחה לטלפונים", if (hasKey) "קיים ✓" else "חסר", if (hasKey) GREEN else ORANGE)
        add(chain)

        val info = card()
        row(info, "התרעות שנשלחו היום", "", Relay.forwardedToday(this).toString(), TEXT)
        row(info, "חשמל", "", if (charging) "בטעינה ✓" else "סוללה ${battery()}% – חבר לחשמל", if (charging) GREEN else ORANGE)
        row(info, "רשת", "", if (wifi) "Wi‑Fi ✓" else "לא Wi‑Fi", if (wifi) GREEN else ORANGE)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            row(info, "חיסכון בסוללה", "לחץ כדי לאשר שהממסר לא ייעצר", "לאשר", BLUE) {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
            }
        }
        add(info)
        // עדכונים: גרסה נוכחית, ובדיקה + הורדה + התקנה מתוך הממסר
        val upd = card()
        row(upd, "גרסה", "", "1.${Update.current(this)}", TEXT)
        // בדיקת עדכונים - פותח את עמוד בדיקת העדכונים, כמו בצופר חירום
        row(upd, "בדיקת עדכונים", "", "", TEXT) { go(2) }
        add(upd)
        val key = card()
        row(key, "החלפת מפתח", "מנתק את הממסר ומבקש מפתח חדש", "", TEXT) {
            RelayService.stop(this@MainActivity); Relay.setKey(this@MainActivity, ""); Relay.setEnabled(this@MainActivity, false); build()
        }
        add(key)
    }

    /**
     * עמוד "בדיקת עדכונים" - בדיוק כמו בצופר חירום: עיגול עם טבעת מסתובבת בזמן הבדיקה,
     * ואז אחד משלושה: גרסה חדשה (פרטים + "התקן עכשיו" / "מאוחר יותר") / מעודכן / לא ניתן לבדוק
     */
    private fun updatePage() {
        root.removeAllViews(); circleViews = null; state = null; clockView = null
        header("בדיקת עדכונים", null)
        val blue = PILL
        val circleBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val ring = UpdateRing(this)
        val icon = ImageView(this)
        val circle = android.widget.FrameLayout(this).apply {
            background = circleBg
            addView(ring, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(icon, android.widget.FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER))
        }
        val headline = text("", 20f, TEXT, true).apply { gravity = Gravity.CENTER }
        val sub = text("", 14f, MUTED).apply { gravity = Gravity.CENTER }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(22), dp(24), dp(18))
            addView(circle, LinearLayout.LayoutParams(dp(110), dp(110)))
            addView(headline, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        fun head(bg: String, sym: Int, symColor: Int, title: String, subText: String) {
            circleBg.setColor(Color.parseColor(if (!LIGHT) bg else if (bg == "#2C2C2E") "#E5E5EA" else "#E3EBFF"))
            icon.setImageResource(sym); icon.imageTintList = android.content.res.ColorStateList.valueOf(symColor)
            ring.color = symColor
            headline.text = title
            sub.text = subText; sub.visibility = if (subText.isEmpty()) View.GONE else View.VISIBLE
        }
        val mine = "1.${Update.current(this)}"
        val dateFmt = java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.US)
        /** כרטיס הגרסאות - נבנה מחדש כשמצב ההורדה משתנה */
        fun cardInto(vararg rows: Pair<String, String>) {
            val c = card(); rows.forEach { (t, e) -> row(c, t, "", e, MUTED) }
            content.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(12)) })
        }
        fun check() {
            // ---- בודק ----
            content.removeAllViews()
            head("#162038", R.drawable.ic_upd_checking, blue, "בודק אם יש גרסה חדשה…", "מתחבר לשרת העדכונים")
            pills(null)   // בזמן הבדיקה - בלי כפתור למטה
            ring.spin()
            cardInto("גרסה מותקנת" to mine)
            val started = System.currentTimeMillis()
            Thread {
                var failed = false
                val l = try { Update.lookup() } catch (_: Exception) { null }
                if (l == null) failed = true
                // האנימציה מוצגת לפחות שנייה וחצי - שלא "יקפוץ" ישר לתוצאה
                val wait = 1500 - (System.currentTimeMillis() - started)
                if (wait > 0) Thread.sleep(wait)
                runOnUiThread {
                    if (isDestroyed || page != 2) return@runOnUiThread
                    ring.stop(); content.removeAllViews()
                    when {
                        l != null && l.n > Update.current(this) -> {
                            // ---- יש גרסה חדשה ----
                            head("#162038", R.drawable.ic_upd_new, blue, "גרסה חדשה זמינה – 1.${l.n}",
                                if (l.date > 0) "פורסמה " + dateFmt.format(java.util.Date(l.date)) else "")
                            cardInto("הגרסה שלך" to mine, "גרסה חדשה" to "1.${l.n}",
                                *(if (l.size > 0) arrayOf("גודל" to "%.1f MB".format(java.util.Locale.US, l.size / 1048576.0)) else arrayOf<Pair<String, String>>()))
                            // מצב ההורדה וההתקנה מתחת לכרטיס
                            val st = text("", 11f, MUTED2).apply { setPadding(dp(32), 0, dp(32), dp(14)) }
                            content.addView(st)
                            fun refresh() = runOnUiThread {
                                st.text = Update.status; st.visibility = if (Update.status.isEmpty()) View.GONE else View.VISIBLE
                                pillBusy(Update.busy)
                            }
                            Update.status = ""; refresh()
                            pills("התקן עכשיו", two = true) { Update.download(this, l) { refresh() } }
                        }
                        failed -> {
                            // ---- אין חיבור ----
                            head("#2C2C2E", R.drawable.ic_upd_offline, MUTED, "לא ניתן לבדוק כרגע", "בדוק את החיבור לאינטרנט ונסה שוב")
                            cardInto("גרסה מותקנת" to mine)
                            pills("בדוק אם יש עדכונים") { check() }
                        }
                        else -> {
                            // ---- מעודכן ----
                            head("#162038", R.drawable.ic_upd_ok, blue, "הגרסה שלך מעודכנת", "")
                            val at = try { dateFmt.format(java.util.Date(packageManager.getPackageInfo(packageName, 0).lastUpdateTime)) } catch (_: Exception) { "" }
                            cardInto("גרסה מותקנת" to mine, "עודכנה בטלפון" to at)
                            pills("בדוק אם יש עדכונים") { check() }
                        }
                    }
                }
            }.start()
        }
        check()
    }

    // ---- הגדרות תצוגה - בדיוק כמו עמוד "תצוגה" בצופר חירום ----

    /** כפתור בחירה כמו בסמסונג: טבעת, ובנבחר נקודה כחולה */
    private fun radio(sel: Boolean) = android.widget.FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), Color.parseColor(if (sel) "#3E82F7" else "#6E6E73")) }
        if (sel) addView(View(this@MainActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#3E82F7")) }
        }, android.widget.FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
    }
    /**
     * שורה כמו ouiRow באפליקציה: שם, מצב בכחול מתחת, ובצד כפתור בחירה או מתג.
     * onToggle: לחיצה על השורה פותחת עמוד, והמתג לבד (מהקו ועד הקצה) מדליק/מכבה
     */
    private fun orow(c: LinearLayout, title: String, sub: String = "", radio: Boolean? = null, on: Boolean? = null,
                     onToggle: (() -> Unit)? = null, click: () -> Unit) {
        if (c.childCount > 0 && c.getChildAt(c.childCount - 1).tag == "row") divider(c)
        val r = LinearLayout(this).apply {
            tag = "row"; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { click() }
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(text(title, 16f).apply { includeFontPadding = false })
        if (sub.isNotEmpty()) t.addView(text(sub, 12f, BLUE).apply { includeFontPadding = false; setPadding(0, dp(4), 0, 0) })
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        if (radio != null) r.addView(radio(radio), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(10) })
        if (on != null) {
            val z = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(dp(12), 0, dp(18), 0)
                if (onToggle != null) { isClickable = true; setOnClickListener { onToggle() } }
                addView(View(this@MainActivity).apply { setBackgroundColor(SEPV) }, LinearLayout.LayoutParams(dp(1), dp(20)).apply { marginEnd = dp(12) })
                addView(OuiSwitch(this@MainActivity, on, LIGHT).apply { isClickable = false })
            }
            r.addView(z, LinearLayout.LayoutParams(-2, -1).apply { marginEnd = -dp(18) })
        }
        c.addView(r, LinearLayout.LayoutParams(-1, dp(58)))
    }
    private fun cardTitle(c: LinearLayout, t: String) = c.addView(text(t, 16f).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
    private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    private fun secLabel(sec: Int) = when {
        sec < 60 -> "$sec שניות"; sec == 60 -> "דקה"; sec % 60 == 0 -> "${sec / 60} דקות"
        else -> "%d:%02d דקות".format(sec / 60, sec % 60)
    }
    private fun themeTimeLabel() = if (Display.themeTimeMode(this) == 0) "משקיעה עד זריחה" else "${hm(Display.darkFrom(this))} – ${hm(Display.darkTo(this))}"
    private fun statusModeLabel() = when (Display.statusMode(this)) {
        Display.STATUS_HIDDEN -> "לא מוצגת"
        Display.STATUS_TIMED -> "${secLabel(Display.statusSeconds(this))} אחרי פתיחה"
        else -> "מוצגת קבוע"
    }
    /** ערכת צבעים חדשה - מתחלפת מיד */
    private fun setColors(mode: Int) {
        Display.setThemeMode(this, mode)
        applyTheme(Display.dark(this)); show()
    }
    private fun pageStart(title: String) {
        root.removeAllViews(); circleViews = null; state = null; clockView = null
        pills(null)
        header(title, null)
    }

    /** עמוד "תצוגה" */
    private fun displayPage() {
        pageStart("תצוגה")
        val mode = Display.themeMode(this)
        // ערכת צבעים: תצוגה מקדימה חשוך / בהיר + "לפי המערכת" + "לפי זמן"
        val theme = card()
        val pick = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(20), dp(10), dp(16)) }
        fun preview(light: Boolean, label: String, sel: Boolean, value: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply { setColor(Color.parseColor(if (light) "#D9D9DC" else "#2B2B2E")); cornerRadius = dp(12).toFloat() }
                listOf(12, 26, 40, 26).forEach { h ->
                    addView(View(this@MainActivity).apply {
                        background = GradientDrawable().apply { setColor(Color.parseColor(if (light) "#BDBDC2" else "#3E3E42")); cornerRadius = dp(6).toFloat() }
                    }, LinearLayout.LayoutParams(-1, dp(h)).apply { bottomMargin = dp(5) })
                }
            }, LinearLayout.LayoutParams(dp(76), dp(150)))
            addView(text(label, 13f, if (sel) BLUE else MUTED2, sel).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }, LinearLayout.LayoutParams(-1, -2))
            addView(radio(sel), LinearLayout.LayoutParams(dp(20), dp(20)))
            setOnClickListener { if (Display.themeMode(this@MainActivity) != value) setColors(value) }
        }
        pick.addView(preview(false, "חשוך", mode == 0, 0), LinearLayout.LayoutParams(0, -2, 1f))
        pick.addView(preview(true, "בהיר", mode == 1, 1), LinearLayout.LayoutParams(0, -2, 1f))
        theme.addView(pick)
        divider(theme)
        orow(theme, "לפי המערכת", on = mode == 2, onToggle = { setColors(if (mode == 2) 0 else 2) }) { setColors(if (mode == 2) 0 else 2) }
        orow(theme, "לפי זמן", themeTimeLabel(), on = mode == 3, onToggle = { setColors(if (mode == 3) 0 else 3) }) { go(4) }
        add(theme)
        note("לפי המערכת – בהיר או חשוך לפי הגדרת הטלפון\nלפי זמן – חשוך בלילה ובהיר ביום")
        // שורת החיוויים במסך הראשי
        val ind = card()
        val sm = Display.statusMode(this)
        orow(ind, "שורת חיוויים", statusModeLabel(), on = sm != Display.STATUS_HIDDEN, onToggle = {
            Display.setStatusMode(this, if (sm == Display.STATUS_HIDDEN) Display.STATUS_ALWAYS else Display.STATUS_HIDDEN); show() }) { go(5) }
        add(ind)
        note("פועל · שרתים · רשת · חשמל · גרסה – מעל כרטיס המצב במסך הראשי")
        // שעון ותאריך
        val more = card()
        orow(more, "שעון", listOf(if (Display.showSeconds(this)) "שניות" else "", if (Display.blinkColon(this)) "נקודתיים מהבהבות" else "")
            .filter { it.isNotEmpty() }.joinToString(" · ")) { go(6) }
        orow(more, "תאריך", TimeFormat.dayOptions()[Display.dayStyle(this)], on = Display.showDate(this), onToggle = {
            Display.setShowDate(this, !Display.showDate(this)); show() }) { go(7) }
        add(more)
        note("שעון ותאריך בכרטיס המצב במסך הראשי")
    }

    /** "לפי זמן": משקיעה עד זריחה, או לוח זמנים מותאם */
    private fun themeTimePage() {
        pageStart("לפי זמן")
        val c = card()
        val tm = Display.themeTimeMode(this)
        fun set(v: Int) { Display.setThemeTimeMode(this, v); applyTheme(Display.dark(this)); show() }
        orow(c, "משקיעה עד זריחה", radio = tm == 0) { set(0) }
        orow(c, "לוח זמנים מותאם", radio = tm == 1) { set(1) }
        if (tm == 1) {
            orow(c, "שעת התחלה", hm(Display.darkFrom(this))) {
                pickTime("שעת התחלה", Display.darkFrom(this)) { Display.setDarkFrom(this, it); applyTheme(Display.dark(this)); show() } }
            orow(c, "שעת סיום", hm(Display.darkTo(this))) {
                pickTime("שעת סיום", Display.darkTo(this)) { Display.setDarkTo(this, it); applyTheme(Display.dark(this)); show() } }
        }
        add(c)
        note(if (tm == 0) "חשוך מהשקיעה ועד הזריחה, לפי ירושלים" else "חשוך משעת ההתחלה ועד שעת הסיום")
    }

    /** שורת חיוויים: מוצגת קבוע / לא מוצגת / מוצגת לפי זמן (וכמה זמן) */
    private fun statusModePage() {
        pageStart("שורת חיוויים")
        val c = card()
        val mode = Display.statusMode(this)
        listOf(Display.STATUS_ALWAYS to "מוצגת קבוע", Display.STATUS_HIDDEN to "לא מוצגת", Display.STATUS_TIMED to "מוצגת לפי זמן").forEach { (m, label) ->
            orow(c, label, radio = mode == m) { Display.setStatusMode(this, m); touchedAt = System.currentTimeMillis(); show() }
        }
        add(c)
        note("\"לפי זמן\" – השורה מוצגת כשפותחים את הממסר או נוגעים במסך, ונעלמת אחרי הזמן שבוחרים")
        if (mode == Display.STATUS_TIMED) {
            val t = card()
            cardTitle(t, "כמה זמן להציג")
            val fixed = intArrayOf(5, 10, 15, 30, 60)
            val cur = Display.statusSeconds(this)
            fixed.forEach { sec -> orow(t, secLabel(sec), radio = cur == sec) { Display.setStatusSeconds(this, sec); show() } }
            val custom = cur !in fixed
            orow(t, "מותאם", if (custom) secLabel(cur) else "", radio = custom) {
                pickTime("זמן מותאם (דקות : שניות)", if (custom) cur else 20, hourCount = 11) { v ->
                    Display.setStatusSeconds(this, v.coerceIn(3, 600)); show() }
            }
            add(t)
        }
    }

    /** שעון: שניות ונקודתיים מהבהבות */
    private fun clockPage() {
        pageStart("שעון")
        val c = card()
        orow(c, "הצגת שניות", on = Display.showSeconds(this)) { Display.setShowSeconds(this, !Display.showSeconds(this)); show() }
        orow(c, "נקודתיים מהבהבות", on = Display.blinkColon(this)) { Display.setBlinkColon(this, !Display.blinkColon(this)); show() }
        add(c)
        note("השינוי נראה מיד בשעון במסך הראשי")
    }

    /** תאריך: הצגה, סוג תאריך וסגנון יום */
    private fun datePage() {
        pageStart("תאריך")
        val sc = card()
        orow(sc, "הצגת תאריך", on = Display.showDate(this)) { Display.setShowDate(this, !Display.showDate(this)); show() }
        add(sc)
        note("התאריך מוצג מתחת לשעון במסך הראשי")
        val kinds = card()
        cardTitle(kinds, "סוג תאריך")
        val now = System.currentTimeMillis()
        listOf("לועזי" to TimeFormat.date(now), "עברי" to TimeFormat.hebrewDate(now),
            "לועזי ועברי" to TimeFormat.date(now) + " · " + TimeFormat.hebrewDate(now)).forEachIndexed { i, (label, ex) ->
            orow(kinds, label, ex, radio = Display.dateKind(this) == i) { Display.setDateKind(this, i); show() }
        }
        add(kinds)
        val styles = card()
        cardTitle(styles, "סגנון יום")
        TimeFormat.dayOptions().forEachIndexed { i, label ->
            orow(styles, label, radio = Display.dayStyle(this) == i) { Display.setDayStyle(this, i); show() }
        }
        add(styles)
    }

    /**
     * בחירת שעה בדיוק כמו "הגדר שעה" של סמסונג (כמו באפליקציה): חלון מעוגל בתחתית המסך,
     * גלגל שעות וגלגל דקות, ולמטה ביטול | סיום. hourCount = כמה ערכים בגלגל השמאלי
     */
    private fun pickTime(title: String, cur: Int, hourCount: Int = 24, done: (Int) -> Unit) {
        val white = if (LIGHT) Color.parseColor("#1C1C1E") else Color.WHITE
        val hours = TimeWheel(this, hourCount, cur / 60, white)
        val mins = TimeWheel(this, 60, cur % 60, white)
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; isClickable = true
            background = GradientDrawable().apply { setColor(if (LIGHT) Color.WHITE else Color.parseColor("#393939")); cornerRadius = dp(26).toFloat() }
        }
        card.addView(text(title, 17f, white, true).apply { setPadding(dp(30), dp(24), dp(30), 0) })
        card.addView(LinearLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(44), 0, dp(44), 0)
            addView(hours, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(":", 32f, white, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40), -2))
            addView(mins, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        val btns = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(t: String, run: () -> Unit) = text(t, 17f, white, true).apply { gravity = Gravity.CENTER; setOnClickListener { d.dismiss(); run() } }
        btns.addView(button("ביטול") {}, LinearLayout.LayoutParams(0, -1, 1f))
        btns.addView(View(this).apply { setBackgroundColor(Color.parseColor(if (LIGHT) "#D1D1D6" else "#535353")) }, LinearLayout.LayoutParams(dp(1), dp(16)))
        btns.addView(button("סיום") { done(hours.value * 60 + mins.value) }, LinearLayout.LayoutParams(0, -1, 1f))
        card.addView(btns, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(8) })
        val f = android.widget.FrameLayout(this).apply { setOnClickListener { d.dismiss() } }   // לחיצה מחוץ לחלון סוגרת
        f.addView(card, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(10)) })
        d.setContentView(f)
        d.window?.apply {
            setLayout(-1, -1)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.6f)
            setWindowAnimations(0)
        }
        d.show()
    }

    /** הפעלה ראשונה: הקלדת המפתח מהאתר ובדיקה מול השרת */
    private fun setup() {
        root.removeAllViews()
        pills(null)
        header("ממסר צופר", null)
        val c = card()
        c.addView(text("מפתח ממסר", 16f).apply { setPadding(dp(18), dp(14), dp(18), 0) })
        c.addView(text("מופיע באתר, בכרטיס \"ממסר בבית\"", 12f, MUTED).apply { setPadding(dp(18), dp(4), dp(18), 0) })
        val f = EditText(this).apply {
            hint = "XXXX-XXXX-XXXX"; setHintTextColor(MUTED); setTextColor(TEXT); textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_LTR; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor(if (LIGHT) "#E3E3E8" else "#2C2C2E")); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        keyField = f
        c.addView(f, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(18), dp(10), dp(18), dp(10)) })
        val msg = text(setupMsg, 13f, RED).apply { gravity = Gravity.CENTER }
        c.addView(msg)
        // כפתור כחול כמו בצופר חירום: גובה 46, עיגול מלא, טקסט 16 מודגש
        val btn = text("חבר", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; includeFontPadding = false
            background = GradientDrawable().apply { setColor(PILL); cornerRadius = dp(23).toFloat() }
        }
        btn.setOnClickListener {
            val k = f.text.toString().trim()
            if (k.isEmpty()) return@setOnClickListener
            btn.text = "בודק…"; msg.text = ""
            Thread {
                val code = Relay.pingPrimary(k)
                runOnUiThread {
                    btn.text = "חבר"
                    when (code) {
                        200 -> { Relay.setKey(this, k); Relay.setEnabled(this, true); setupMsg = ""; RelayService.start(this); keyField = null; build() }
                        401 -> msg.text = "המפתח שגוי – בדוק אותו באתר"
                        else -> msg.text = "אין חיבור לשרת – בדוק את ה‑Wi‑Fi"
                    }
                }
            }.start()
        }
        c.addView(btn, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(18), dp(6), dp(18), dp(16)) })
        add(c)
        note("הטלפון צריך להישאר מחובר לחשמל ול‑Wi‑Fi. הממסר עולה לבד אחרי הפעלה מחדש.")
    }

    private fun uptime(ms: Long): String {
        val m = ms / 60_000; val h = m / 60
        return if (h > 0) "$h שעות ${m % 60} דקות" else "$m דקות"
    }
    private fun batteryIntent() = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    private fun charging(): Boolean = (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    private fun battery(): Int = batteryIntent()?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
    private fun onWifi(): Boolean = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) { false }
}

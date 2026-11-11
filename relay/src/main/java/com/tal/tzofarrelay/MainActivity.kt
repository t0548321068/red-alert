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
    // אותם צבעים כמו בצופר חירום 4.0 (עיצוב כהה)
    private val GREEN = Color.parseColor("#30D158"); private val ORANGE = Color.parseColor("#FF9F0A")
    private val RED = Color.parseColor("#FF3B30"); private val MUTED = Color.parseColor("#8E8E93")
    private val TEXT = Color.parseColor("#F2F2F2"); private val MUTED2 = Color.parseColor("#AAAAAA")
    private val CARD = Color.parseColor("#1B1B1D"); private val DIV = Color.parseColor("#2A2A2C")
    private val SEPV = Color.parseColor("#5C5C5E"); private val BLUE = Color.parseColor("#5AA9FF")
    private val PILL = Color.parseColor("#4C7DFF"); private val LINE2 = Color.parseColor("#22FFFFFF")
    private val SOFT = Color.parseColor("#E6FFFFFF")
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
    private fun paint() {
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
        @Suppress("DEPRECATION") window.statusBarColor = Color.BLACK
        @Suppress("DEPRECATION") window.navigationBarColor = Color.BLACK
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(100)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK); isVerticalScrollBarEnabled = false; addView(root) }   // בלי פס גלילה
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
            setOnClickListener {
                if (checking) return@setOnClickListener
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
        setContentView(android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(pill, android.widget.FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.5f).toInt(), dp(46),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(20) })
        })
        if (Build_SDK >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
    }
    private fun pillBusy(b: Boolean) { pillLabel.visibility = if (b) View.INVISIBLE else View.VISIBLE; pillSpin.visibility = if (b) View.VISIBLE else View.GONE }
    private val Build_SDK get() = android.os.Build.VERSION.SDK_INT

    override fun onResume() { super.onResume(); RelayService.start(this); ui.post(tick); ui.post(anim) }
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
    private var settings = false
    @Deprecated("") override fun onBackPressed() { if (settings) { settings = false; build() } else @Suppress("DEPRECATION") super.onBackPressed() }

    @Volatile private var checking = false
    private var keyField: EditText? = null
    private var setupMsg = ""
    private fun build() {
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
        if (settings) settingsPage(now, on, charging, wifi, hasKey, primary, secondary) else mainPage(st, now, on, charging, wifi, primary, secondary)
    }

    /** מסך ראשי כמו בצופר חירום: כותרת עם גלגל שיניים, שורת חיוויים, וכרטיס המצב */
    private fun mainPage(st: St, now: Long, on: Boolean, charging: Boolean, wifi: Boolean, primary: Boolean, secondary: Boolean) {
        pill.visibility = View.GONE
        header("ממסר צופר") { settings = true; build() }
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
            ind(if (enabled) R.drawable.ic_shield_ind else R.drawable.ic_shield_ind_off, if (enabled) "פועל" else "כבוי", if (enabled) GREEN else MUTED) { toggleRelay() },
            ind(R.drawable.ic_antenna, srv.first, srv.second) { settings = true; build() },
            ind(R.drawable.ic_globe, if (wifi) "Wi‑Fi" else "לא Wi‑Fi", if (wifi) GREEN else ORANGE) { settings = true; build() },
            ind(R.drawable.ic_power, if (charging) "בטעינה" else "${battery()}%", if (charging) GREEN else ORANGE) { settings = true; build() },
            ind(R.drawable.ic_info, "1.${Update.current(this)}", TEXT) {
                if (!Update.busy) Update.checkAndInstall(this@MainActivity) { runOnUiThread { build() } } })
        inds.forEachIndexed { i, v ->
            if (i > 0) row.addView(View(this).apply { setBackgroundColor(LINE2) }, LinearLayout.LayoutParams(dp(1), dp(26)))
            row.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }
        add(row)
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
        c.addView(text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(now)), 46f, TEXT).apply {
            gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        c.addView(text(java.text.SimpleDateFormat("EEEE, d בMMMM", java.util.Locale("iw")).format(java.util.Date(now)), 19f, SOFT).apply { gravity = Gravity.CENTER })
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
    private fun ind(icon: Int, label: String, color: Int, click: () -> Unit) = text(label, 12f, TEXT).apply {
        getDrawable(icon)?.mutate()?.let { d ->
            d.setBounds(0, 0, (d.intrinsicWidth * 1.45f).toInt(), (d.intrinsicHeight * 1.45f).toInt())
            setCompoundDrawablesRelative(null, d, null, null)
        }
        compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
        compoundDrawablePadding = (dp(4) - (dp(18) * 0.15f).toInt()).coerceAtLeast(0)
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setOnClickListener { click() }
    }
    private fun toggleRelay() {
        val enabled = Relay.enabled(this)
        Relay.setEnabled(this, !enabled)
        if (!enabled) RelayService.start(this) else RelayService.stop(this)
        build()
    }

    /** הגדרות: הפעלה, שרשרת הגיבוי, מצב הטלפון, עדכונים והחלפת מפתח - עם כפתור "בדיקה" צף */
    private fun settingsPage(now: Long, on: Boolean, charging: Boolean, wifi: Boolean, hasKey: Boolean, primary: Boolean, secondary: Boolean) {
        pill.visibility = View.VISIBLE; circleViews = null
        header("הגדרות", null)
        val enabled = Relay.enabled(this)
        val top = card()
        top.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0)
            addView(text("ממסר", 16f).apply { includeFontPadding = false }, LinearLayout.LayoutParams(0, -2, 1f))
            // קו דק לפני המתג, כמו בצופר חירום
            addView(View(this@MainActivity).apply { setBackgroundColor(SEPV) },
                LinearLayout.LayoutParams(dp(1), dp(20)).apply { marginEnd = dp(12) })
            addView(OuiSwitch(this@MainActivity, enabled, false))
            setOnClickListener { toggleRelay() }
        }, LinearLayout.LayoutParams(-1, dp(58)))
        add(top)
        note("גיבוי שלישי: שולח לטלפונים רק כששני השרתים לא עונים")

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
        // בדיקת עדכונים - שורה רגילה כמו "עדכונים" בהגדרות של צופר חירום; המצב מוצג מתחת לשם
        row(upd, "בדיקת עדכונים", if (Update.busy) "בודק…" else Update.status, "", TEXT) {
            if (!Update.busy) Update.checkAndInstall(this@MainActivity) { runOnUiThread { build() } }
        }
        add(upd)
        val key = card()
        row(key, "החלפת מפתח", "מנתק את הממסר ומבקש מפתח חדש", "", TEXT) {
            RelayService.stop(this@MainActivity); Relay.setKey(this@MainActivity, ""); Relay.setEnabled(this@MainActivity, false); build()
        }
        add(key)
    }

    /** הפעלה ראשונה: הקלדת המפתח מהאתר ובדיקה מול השרת */
    private fun setup() {
        root.removeAllViews()
        pill.visibility = View.GONE
        header("ממסר צופר", null)
        val c = card()
        c.addView(text("מפתח ממסר", 16f).apply { setPadding(dp(18), dp(14), dp(18), 0) })
        c.addView(text("מופיע באתר, בכרטיס \"ממסר בבית\"", 12f, MUTED).apply { setPadding(dp(18), dp(4), dp(18), 0) })
        val f = EditText(this).apply {
            hint = "XXXX-XXXX-XXXX"; setHintTextColor(MUTED); setTextColor(TEXT); textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_LTR; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#2C2C2E")); cornerRadius = dp(14).toFloat() }
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

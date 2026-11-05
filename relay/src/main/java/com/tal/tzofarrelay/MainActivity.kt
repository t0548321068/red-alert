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
    private val GREEN = Color.parseColor("#30D158"); private val ORANGE = Color.parseColor("#FF9F0A")
    private val RED = Color.parseColor("#FF453A"); private val MUTED = Color.parseColor("#8E8E93")
    private val CARD = Color.parseColor("#1C1C1E"); private val BLUE = Color.parseColor("#3E7BFA")
    private lateinit var root: LinearLayout
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
    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(26).toFloat() }
    }
    private fun add(v: View) = root.addView(v, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(10)) })
    private fun divider(c: LinearLayout) = c.addView(View(this).apply { setBackgroundColor(Color.parseColor("#2C2C2E")) }, LinearLayout.LayoutParams(-1, 1))
    private fun row(c: LinearLayout, title: String, sub: String, end: String, endColor: Int) {
        if (c.childCount > 0) divider(c)
        c.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(13), dp(18), dp(13))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 15f))
                if (sub.isNotEmpty()) addView(text(sub, 12f, MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(end, 14f, endColor))
        })
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
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(24)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); isVerticalScrollBarEnabled = false; addView(root) })   // בלי פס גלילה
        if (Build_SDK >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
    }
    private val Build_SDK get() = android.os.Build.VERSION.SDK_INT

    override fun onResume() { super.onResume(); RelayService.start(this); ui.post(tick); ui.post(anim) }
    override fun onPause() { ui.removeCallbacks(tick); ui.removeCallbacks(anim); super.onPause() }

    private fun header(sub: String) {
        root.addView(text("ממסר צופר", 26f, bold = true).apply { setPadding(dp(22), dp(14), dp(22), dp(2)) })
        root.addView(text(sub, 13f, MUTED).apply { setPadding(dp(22), 0, dp(22), dp(14)) })
    }

    @Volatile private var checking = false
    private var keyField: EditText? = null
    private var setupMsg = ""
    private fun build() {
        if (Relay.key(this).isEmpty()) { state = null; circleViews = null; if (keyField == null) setup(); return }
        keyField = null
        root.removeAllViews()
        header("גיבוי שלישי: שולח לטלפונים רק כששני השרתים לא עונים")
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
        val color = st.c1; val title = st.title; val sub = st.sub
        val top = card()
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(22), 0, dp(18))
            val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            val icon = ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_antenna) }
            val circle = android.widget.FrameLayout(this@MainActivity).apply {
                background = bg
                addView(icon, android.widget.FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
            }
            addView(circle, LinearLayout.LayoutParams(dp(86), dp(86)))
            val tv = text(title, 20f, color, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
            addView(tv)
            circleViews = Triple(circle, bg, icon); titleView = tv
            paint()
            addView(text(sub, 12f, MUTED).apply { gravity = Gravity.CENTER })
        })
        divider(top)
        // מתג כמו בצופר חירום: נגיעה בשורה מדליקה / מכבה
        val enabled = Relay.enabled(this)
        top.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(text("ממסר", 15f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(OuiSwitch(this@MainActivity, enabled, false))
            setOnClickListener {
                Relay.setEnabled(this@MainActivity, !enabled)
                if (!enabled) RelayService.start(this@MainActivity) else RelayService.stop(this@MainActivity)
                build()
            }
        })
        add(top)

        // בדיקה עכשיו: שני השרתים, המקורות ו-Firebase, ומתי בוצעה הבדיקה האחרונה
        val chk = card()
        val (chkAt, chkRes) = Relay.lastCheck(this)
        val fmt = java.text.SimpleDateFormat(if (now - chkAt < 20 * 3600_000L) "HH:mm:ss" else "dd/MM HH:mm", java.util.Locale.US)
        row(chk, "בדיקה אחרונה", if (chkAt > 0) chkRes else "", if (chkAt > 0) fmt.format(java.util.Date(chkAt)) else "עוד לא בוצעה",
            if (chkAt == 0L) MUTED else if (chkRes == "הכול תקין") GREEN else RED)
        divider(chk)
        chk.addView(text(if (checking) "בודק…" else "בדוק עכשיו", 15f, if (checking) MUTED else BLUE, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(13), 0, dp(13))
            setOnClickListener {
                if (checking) return@setOnClickListener
                checking = true; build()
                Thread { Relay.checkNow(this@MainActivity); runOnUiThread { checking = false; build() } }.start()
            }
        })
        add(chk)

        // שרשרת הגיבוי
        val chain = card()
        row(chain, "שרת ראשי", if (on) "בדיקה אחרונה ${ago(Relay.lastCheckAt)}" else "", if (!on) "—" else if (primary) "מחובר" else "לא עונה",
            if (!on) MUTED else if (primary) GREEN else RED)
        row(chain, "שרת משני", "", if (!on) "—" else if (!secondary) "לא עונה" else if (Relay.secondaryActive) "פעיל" else "בהמתנה",
            if (!on) MUTED else if (!secondary) RED else if (Relay.secondaryActive) ORANGE else GREEN)
        row(chain, "ממסר (הטלפון הזה)", "", if (!on) "כבוי" else if (Relay.active) "פעיל" else "בהמתנה", if (!on) MUTED else if (Relay.active) ORANGE else GREEN)
        row(chain, "מפתח Firebase", "לשליחה לטלפונים", if (hasKey) "קיים ✓" else "חסר", if (hasKey) GREEN else ORANGE)
        add(chain)

        // המקורות - רק כשהממסר פעיל
        if (on && Relay.active) {
            val src = card()
            Relay.sources.forEach { s ->
                val ok = now - s.lastOk < s.everyMs * 3 + 10_000
                row(src, s.name, "בדיקה אחרונה ${ago(s.lastOk)}", if (ok) "מחובר" else s.error.ifEmpty { "מתחבר…" }, if (ok) GREEN else RED)
            }
            if (Relay.fcmError.isNotEmpty()) row(src, "Firebase", "", Relay.fcmError, RED)
            add(src)
        }

        val info = card()
        row(info, "התרעות שנשלחו היום", "", Relay.forwardedToday(this).toString(), Color.WHITE)
        row(info, "חשמל", "", if (charging) "בטעינה ✓" else "סוללה ${battery()}% – חבר לחשמל", if (charging) GREEN else ORANGE)
        row(info, "רשת", "", if (wifi) "Wi‑Fi ✓" else "לא Wi‑Fi", if (wifi) GREEN else ORANGE)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            row(info, "חיסכון בסוללה", "לחץ כדי לאשר שהממסר לא ייעצר", "לאשר", BLUE)
            (info.getChildAt(info.childCount - 1)).setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
            }
        }
        add(info)
        // עדכונים: גרסה נוכחית, ובדיקה + הורדה + התקנה מתוך הממסר
        val upd = card()
        row(upd, "גרסה", "", "1.${Update.current(this)}", Color.WHITE)
        if (Update.status.isNotEmpty()) row(upd, Update.status, "", "", MUTED)
        divider(upd)
        upd.addView(text(if (Update.busy) "בודק…" else "בדיקת עדכונים", 15f, if (Update.busy) MUTED else BLUE, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(13), 0, dp(13))
            setOnClickListener { Update.checkAndInstall(this@MainActivity) { runOnUiThread { build() } } }
        })
        add(upd)
        root.addView(text("החלפת מפתח", 13f, BLUE).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
            setOnClickListener { RelayService.stop(this@MainActivity); Relay.setKey(this@MainActivity, ""); Relay.setEnabled(this@MainActivity, false); build() }
        })
    }

    /** הפעלה ראשונה: הקלדת המפתח מהאתר ובדיקה מול השרת */
    private fun setup() {
        root.removeAllViews()
        header("גיבוי שלישי: שולח לטלפונים רק כששני השרתים לא עונים")
        val c = card()
        c.addView(text("מפתח ממסר", 15f).apply { setPadding(dp(18), dp(14), dp(18), 0) })
        c.addView(text("מופיע באתר, בכרטיס \"ממסר בבית\"", 12f, MUTED).apply { setPadding(dp(18), dp(2), dp(18), 0) })
        val f = EditText(this).apply {
            hint = "XXXX-XXXX-XXXX"; setHintTextColor(MUTED); setTextColor(Color.WHITE); textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_LTR; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#2C2C2E")); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        keyField = f
        c.addView(f, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(18), dp(10), dp(18), dp(10)) })
        val msg = text(setupMsg, 13f, RED).apply { gravity = Gravity.CENTER }
        c.addView(msg)
        val btn = text("חבר", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
            background = GradientDrawable().apply { setColor(BLUE); cornerRadius = dp(22).toFloat() }
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
        c.addView(btn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(18), dp(6), dp(18), dp(16)) })
        add(c)
        root.addView(text("הטלפון צריך להישאר מחובר לחשמל ול‑Wi‑Fi. הממסר עולה לבד אחרי הפעלה מחדש.", 12f, Color.parseColor("#6E6E73")).apply {
            setPadding(dp(26), 0, dp(26), 0) })
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

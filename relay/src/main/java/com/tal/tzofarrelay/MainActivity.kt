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
import android.widget.Switch
import android.widget.TextView

/** מסך אחד: הקלדת המפתח בפעם הראשונה, ואחר כך מצב הממסר (מתעדכן כל שנייה) */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val GREEN = Color.parseColor("#30D158"); private val ORANGE = Color.parseColor("#FF9F0A")
    private val RED = Color.parseColor("#FF453A"); private val MUTED = Color.parseColor("#8E8E93")
    private val CARD = Color.parseColor("#1C1C1E"); private val BLUE = Color.parseColor("#3E7BFA")
    private lateinit var root: LinearLayout
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
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) })
        if (Build_SDK >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
    }
    private val Build_SDK get() = android.os.Build.VERSION.SDK_INT

    override fun onResume() { super.onResume(); RelayService.start(this); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    private fun header(sub: String) {
        root.addView(text("ממסר צופר", 26f, bold = true).apply { setPadding(dp(22), dp(14), dp(22), dp(2)) })
        root.addView(text(sub, 13f, MUTED).apply { setPadding(dp(22), 0, dp(22), dp(14)) })
    }

    private var keyField: EditText? = null
    private var setupMsg = ""
    private fun build() {
        if (Relay.key(this).isEmpty()) { if (keyField == null) setup(); return }
        keyField = null
        root.removeAllViews()
        header("מעביר את התרעות פיקוד העורף לשרת מהאינטרנט הביתי")
        val now = System.currentTimeMillis()
        val on = Relay.enabled(this) && RelayService.isRunning
        val liveOk = now - Relay.sources[0].lastOk < 15_000
        val srvOk = now - Relay.serverOk < 90_000
        val charging = charging(); val wifi = onWifi()
        val (color, title, sub) = when {
            !on -> Triple(MUTED, "הממסר כבוי", "ההתרעות לא מועברות לשרת")
            !srvOk -> Triple(RED, "אין חיבור לשרת", Relay.serverError.ifEmpty { "מתחבר…" })
            !liveOk -> Triple(RED, "פיקוד העורף לא עונה", Relay.sources[0].error.ifEmpty { "מתחבר…" })
            !charging -> Triple(ORANGE, "פועל עם בעיה", "הטלפון לא מחובר לחשמל")
            Relay.sources.any { now - it.lastOk > it.everyMs * 3 + 10_000 } -> Triple(ORANGE, "פועל עם בעיה", "אחד המקורות לא עונה")
            else -> Triple(GREEN, "הממסר פעיל", "פועל " + uptime(now - Relay.startedAt))
        }
        val top = card()
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(22), 0, dp(18))
            addView(android.widget.FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))) }
                addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_antenna); setColorFilter(color) },
                    android.widget.FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(86), dp(86)))
            addView(text(title, 20f, color, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
            addView(text(sub, 12f, MUTED).apply { gravity = Gravity.CENTER })
        })
        divider(top)
        top.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(10))
            addView(text("ממסר", 15f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Switch(this@MainActivity).apply {
                isChecked = Relay.enabled(this@MainActivity)
                setOnCheckedChangeListener { _, v ->
                    Relay.setEnabled(this@MainActivity, v)
                    if (v) RelayService.start(this@MainActivity) else RelayService.stop(this@MainActivity)
                }
            })
        })
        add(top)

        val src = card()
        Relay.sources.forEach { s ->
            val ok = now - s.lastOk < s.everyMs * 3 + 10_000
            row(src, s.name, if (on) "בדיקה אחרונה ${ago(s.lastOk)}" else "", if (!on) "—" else if (ok) "מחובר" else s.error.ifEmpty { "מתחבר…" },
                if (!on) MUTED else if (ok) GREEN else RED)
        }
        row(src, "שרת התרעות", if (on) "תשובה אחרונה ${ago(Relay.serverOk)}" else "", if (!on) "—" else if (srvOk) "מחובר" else Relay.serverError.ifEmpty { "מתחבר…" },
            if (!on) MUTED else if (srvOk) GREEN else RED)
        add(src)

        val st = card()
        row(st, "התרעות שהועברו היום", "", Relay.forwardedToday(this).toString(), Color.WHITE)
        row(st, "חשמל", "", if (charging) "בטעינה ✓" else "סוללה ${battery()}% – חבר לחשמל", if (charging) GREEN else ORANGE)
        row(st, "רשת", "", if (wifi) "Wi‑Fi ✓" else "לא Wi‑Fi", if (wifi) GREEN else ORANGE)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            row(st, "חיסכון בסוללה", "לחץ כדי לאשר שהממסר לא ייעצר", "לאשר", BLUE)
            (st.getChildAt(st.childCount - 1)).setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
            }
        }
        add(st)
        root.addView(text("החלפת מפתח", 13f, BLUE).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
            setOnClickListener { RelayService.stop(this@MainActivity); Relay.setKey(this@MainActivity, ""); Relay.setEnabled(this@MainActivity, false); build() }
        })
    }

    /** הפעלה ראשונה: הקלדת המפתח מהאתר ובדיקה מול השרת */
    private fun setup() {
        root.removeAllViews()
        header("מעביר את התרעות פיקוד העורף לשרת מהאינטרנט הביתי")
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
                val code = try { Relay.post(k, "ping", "").first } catch (_: Exception) { -1 }
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

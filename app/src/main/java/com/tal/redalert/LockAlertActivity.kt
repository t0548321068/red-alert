package com.tal.redalert

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * כרטיס / ממוזער כשהטלפון נעול: המסך נדלק, ובראש מסך הנעילה רק הרצועה (ממוזער) או הכרטיס.
 * שאר מסך הנעילה נשאר גלוי ופעיל. ✕ סוגר; פתיחת הנעילה - ממשיכים כרגיל (ממוזער מעל אפליקציות / כרטיס במסך הראשי)
 */
class LockAlertActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    /** הנעילה נפתחה */
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // ממשיכים כמו בטלפון פתוח (אם ההתרעה עוד בתוקף)
            MiniOverlay.hideHosted(this@LockAlertActivity)
            if (AlertUi.expired(intent.getIntExtra("level", 0), intent.getLongExtra("firedAt", 0L))) finish() else asUnlocked()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        // חלון בגובה התוכן בלבד, בראש המסך: מגע מחוץ לו עובר למסך הנעילה (החלקה לפתיחה וכו')
        window.setLayout(-1, -2)
        window.setGravity(Gravity.TOP)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        // טלפון בלי נעילת מסך (או שהנעילה כבר נפתחה): אין מסך נעילה להציג מעליו - ממשיכים כמו בטלפון פתוח
        if (!(getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked) { asUnlocked(); return }
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT))
        show()
    }

    /** בלי נעילה / אחרי פתיחת הנעילה: נפתחת האפליקציה (המסך הראשי). בממוזער - גם החלון הקטן מעליה */
    private fun asUnlocked() {
        AlertActivity.shownFiredAt = intent.getLongExtra("firedAt", 0L)
        if (intent.getIntExtra("style", 2) == 3) MiniOverlay.pendingMain = Intent(intent)   // בתוך המסך הראשי
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (_: Exception) { }
        finish()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); show() }

    private fun show() {
        val root = FrameLayout(this)
        setContentView(root)
        tick?.let { ui.removeCallbacks(it) }; tick = null
        ui.removeCallbacksAndMessages(null)
        MiniOverlay.hide(this)
        if (intent.getIntExtra("style", 2) == 3) MiniOverlay.show(this, Intent(intent), into = root, closed = { finish() })
        else root.addView(card(), FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), top(), dp(14), dp(8)) })
        // נסגר לבד כשההתרעה כבר לא בתוקף (מקדימה 2 דק', סיום דקה)
        AlertUi.keepMs(intent.getIntExtra("level", 0))?.let { k ->
            ui.postDelayed({ finish() }, (intent.getLongExtra("firedAt", 0L) + k - System.currentTimeMillis()).coerceAtLeast(1000L)) }
    }

    private fun top(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return (if (id > 0) resources.getDimensionPixelSize(id) else dp(28)) + dp(8)
    }

    /** הכרטיס - כמו הכרטיס שמעל השעון במסך הראשי */
    private fun card(): View {
        val title = intent.getStringExtra("title") ?: ""
        val body = intent.getStringExtra("body") ?: ""
        val level = intent.getIntExtra("level", 0)
        val shelter = intent.getIntExtra("shelter", -1)
        val firedAt = intent.getLongExtra("firedAt", System.currentTimeMillis())
        fun tv(t: String, size: Float, bold: Boolean = false, alpha: String = "#FFFFFFFF") = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.parseColor(alpha)); gravity = Gravity.CENTER
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val (t1, t2) = AlertUi.head(title, level)
        val icon = ImageView(this)
        val label = tv("", 13f, alpha = "#E6FFFFFF")
        val timer = tv("", 36f, bold = true).apply { includeFontPadding = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(tv(t1, 24f, bold = true)); addView(tv(t2, 17f, alpha = "#E6FFFFFF"))
            if (body.isNotEmpty()) addView(tv(body, 18f, bold = true).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(16), dp(6), dp(16), dp(6))
                background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")); cornerRadius = dp(20).toFloat() }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            if (level == AlertService.LEVEL_ALERT) { addView(label); addView(timer) }
        }
        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_close); setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#40000000")) }
            setOnClickListener { Prefs.setAlertDismissed(this@LockAlertActivity, firedAt); finish() }
        }
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor(AlertUi.color(level))); cornerRadius = dp(28).toFloat() }
            elevation = dp(6).toFloat()
            addView(col, FrameLayout.LayoutParams(-1, -2))
            addView(close, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), dp(12), 0) })
        }
        val t = object : Runnable { override fun run() {
            AlertUi.setIcon(icon, AlertUi.stageIcon(title, level, shelter, firedAt), big = false)
            AlertUi.timer(level, shelter, firedAt)?.let { (l, v) ->
                label.text = l; label.visibility = if (l.isEmpty()) View.GONE else View.VISIBLE
                timer.textSize = if (l.isEmpty()) 20f else 36f; timer.text = v
            }
            ui.postDelayed(this, 500)
        } }
        tick = t; t.run()
        return frame
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlocked) } catch (_: Exception) { }
        ui.removeCallbacksAndMessages(null)
        MiniOverlay.hideHosted(this)
        super.onDestroy()
    }
}

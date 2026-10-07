package com.tal.redalert

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * חלון קטן למעלה אחרי "מזער": סוג ההתראה + ספירה לאחור (ואחריה זמן השהייה במרחב המוגן).
 * לחיצה - חוזר למסך המלא. ✕ - סוגר את החלון הקטן.
 */
object MiniOverlay {
    private var view: View? = null
    private val ui = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    private var bg: GradientDrawable? = null
    private var labelV: TextView? = null
    private var areaV: TextView? = null
    private var timerV: TextView? = null
    private var timerLabelV: TextView? = null

    fun isShowing() = view != null

    private const val RED = "#D50000"
    private const val ORANGE = "#B45309"   // נשארים במרחב המוגן
    private const val GREEN = "#2E7D32"    // האירוע הסתיים - ניתן לצאת

    fun canShow(c: Context) = Settings.canDrawOverlays(c)

    fun show(c: Context, full: Intent) {
        hide(c)
        val app = c.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = app.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val title = full.getStringExtra("title") ?: "צבע אדום"
        val area = full.getStringExtra("body") ?: ""
        val level = full.getIntExtra("level", AlertService.LEVEL_ALERT)
        val shelter = full.getIntExtra("shelter", -1)
        val firedAt = full.getLongExtra("firedAt", System.currentTimeMillis())
        val color = when (level) {
            AlertService.LEVEL_PRE -> "#F08C00"
            AlertService.LEVEL_END -> "#2E7D32"
            else -> "#D50000"
        }

        // סגנון One UI: גלולה - אייקון בעיגול · כותרת + אזור · זמן התגוננות + טיימר · ✕ בעיגול
        fun circle(icon: Int, size: Int) = android.widget.FrameLayout(app).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#38000000")) }
            addView(android.widget.ImageView(app).apply { setImageResource(icon) },
                android.widget.FrameLayout.LayoutParams(dp(size / 2), dp(size / 2), Gravity.CENTER))
        }
        val label = TextView(app).apply {
            setTextColor(Color.WHITE); textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val areaLine = TextView(app).apply {
            text = area; setTextColor(Color.parseColor("#E6FFFFFF")); textSize = 12f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = if (area.isEmpty()) View.GONE else View.VISIBLE
        }
        val texts = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            addView(label); addView(areaLine)
        }
        // הספירה - תמיד גלויה, גם כשהאזור ארוך
        val timerLabel = TextView(app).apply {
            setTextColor(Color.parseColor("#E6FFFFFF")); textSize = 10f; gravity = Gravity.CENTER
        }
        val timer = TextView(app).apply {
            setTextColor(Color.WHITE); textSize = 19f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD; includeFontPadding = false
        }
        val timerBox = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(8), 0)
            addView(timerLabel); addView(timer)
        }
        val closeX = circle(R.drawable.ic_close, 34).apply {
            setOnClickListener {
                hide(app)
                // אחרי סגירה - במסך הראשי נשאר כרטיס ההתרעה מעל השעון
                try {
                    app.startActivity(Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                } catch (_: Exception) { }
            }
        }
        val bar = LinearLayout(app).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(Color.parseColor(color)) }
                .also { bg = it }
            elevation = dp(6).toFloat()
            addView(circle(R.drawable.ic_warning, 38), LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(timerBox)
            addView(closeX, LinearLayout.LayoutParams(dp(34), dp(34)))
            setOnClickListener {
                hide(app)
                app.startActivity(Intent(full).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            x = 0; y = dp(36)
            horizontalMargin = 0.03f
        }
        try { wm.addView(bar, lp) } catch (_: Exception) { return }
        view = bar
        labelV = label; areaV = areaLine; timerV = timer; timerLabelV = timerLabel

        val t = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val enterEnd = firedAt + shelter.coerceAtLeast(0) * 1000L
                val stayEnd = enterEnd + Prefs.STAY_MS
                // ספירה לכניסה (אדום) -> שהייה במרחב המוגן (כתום) -> ממתינים לסיום (כתום). ירוק רק בהודעת סיום
                when {
                    level != AlertService.LEVEL_ALERT -> { label.text = title; timer.text = ""; timerLabel.text = "" }
                    shelter > 0 && now < enterEnd -> { label.text = title; timerLabel.text = "זמן התגוננות"; timer.text = mmss(enterEnd - now); bg?.setColor(Color.parseColor(RED)) }
                    now < stayEnd -> { label.text = title; timerLabel.text = "נשארים במרחב המוגן"; timer.text = mmss(stayEnd - now); bg?.setColor(Color.parseColor(ORANGE)) }
                    else -> { label.text = "ממתינים להודעת סיום"; timerLabel.text = ""; timer.text = ""; bg?.setColor(Color.parseColor(ORANGE)) }
                }
                timerBox.visibility = if (timer.text.isEmpty()) View.GONE else View.VISIBLE
                if (level == AlertService.LEVEL_ALERT && now < stayEnd) ui.postDelayed(this, 500)
            }
        }
        tick = t
        t.run()
    }

    private fun mmss(ms: Long): String { val s = (ms / 1000).toInt(); return "%d:%02d".format(s / 60, s % 60) }

    /** "האירוע הסתיים" בזמן שהחלון הקטן פתוח - הוא נהיה ירוק (במקום מסך מלא) */
    fun showEnd(area: String) {
        if (view == null) return
        tick?.let { ui.removeCallbacks(it) }; tick = null
        bg?.setColor(Color.parseColor(GREEN))
        labelV?.text = "האירוע הסתיים – ניתן לצאת"
        timerV?.text = ""; timerLabelV?.text = ""
        (timerV?.parent as? View)?.visibility = View.GONE
        if (area.isNotEmpty()) { areaV?.text = area; areaV?.visibility = View.VISIBLE }
    }

    fun hide(c: Context) {
        tick?.let { ui.removeCallbacks(it) }; tick = null
        bg = null; labelV = null; areaV = null; timerV = null; timerLabelV = null
        val v = view ?: return
        view = null
        try { (c.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) { }
    }
}

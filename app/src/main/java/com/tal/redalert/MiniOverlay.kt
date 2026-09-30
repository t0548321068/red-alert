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
            AlertService.LEVEL_PRE -> "#E65100"
            AlertService.LEVEL_END -> "#2E7D32"
            else -> "#D50000"
        }

        // שורה 1: סוג ההתראה · שורה 2: האזור
        val label = TextView(app).apply {
            setTextColor(Color.WHITE); textSize = 15f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val areaLine = TextView(app).apply {
            text = area; setTextColor(Color.parseColor("#E6FFFFFF")); textSize = 13f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = if (area.isEmpty()) View.GONE else View.VISIBLE
        }
        val texts = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            addView(label); addView(areaLine)
        }
        // הספירה - תמיד גלויה, גם כשהאזור ארוך
        val timer = TextView(app).apply {
            setTextColor(Color.WHITE); textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, dp(4), 0)
        }
        val closeX = TextView(app).apply {
            text = "✕"; setTextColor(Color.WHITE); textSize = 18f
            setPadding(dp(12), dp(8), dp(14), dp(8))
            setOnClickListener { hide(app) }
        }
        val bar = LinearLayout(app).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.parseColor(color)) }
            elevation = dp(6).toFloat()
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(timer)
            addView(closeX)
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

        val t = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val enterEnd = firedAt + shelter.coerceAtLeast(0) * 1000L
                val stayEnd = enterEnd + Prefs.STAY_MS
                when {
                    level != AlertService.LEVEL_ALERT -> { label.text = title; timer.text = "" }
                    shelter > 0 && now < enterEnd -> { label.text = "🚨 $title"; timer.text = mmss(enterEnd - now) }
                    now < stayEnd -> { label.text = "⏳ במרחב המוגן"; timer.text = mmss(stayEnd - now) }
                    else -> { label.text = "✅ אפשר לצאת מהמרחב המוגן"; timer.text = "" }
                }
                if (level == AlertService.LEVEL_ALERT && now < stayEnd) ui.postDelayed(this, 500)
            }
        }
        tick = t
        t.run()
    }

    private fun mmss(ms: Long): String { val s = (ms / 1000).toInt(); return "%d:%02d".format(s / 60, s % 60) }

    fun hide(c: Context) {
        tick?.let { ui.removeCallbacks(it) }; tick = null
        val v = view ?: return
        view = null
        try { (c.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) { }
    }
}

package com.tal.redalert

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.TimeZone

/**
 * מסך שבת פשוט: בזמן מצב שבת המסך הראשי מתחלף במסך שלא צריך לגעת בו -
 * רקע שחור, שעון גדול, תאריך עברי, צאת השבת, ו"מוגן · צליל ורטט פעילים".
 * בהתרעה - המסך בצבע ההתרעה עם הסוג, האזור והספירה לאחור, וחוזר לשעון לבד (לפי זמני התצוגה של כל צבע).
 * לחיצה ארוכה (3 שניות) - המסך הרגיל ל-5 דקות.
 */
internal class ShabbatScreen(private val a: MainActivity) {
    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    private fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(a).apply {
        textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private val root = FrameLayout(a).apply {
        setBackgroundColor(Color.BLACK)
        isClickable = true; isFocusable = true   // לא עובר למסך שמתחת
        visibility = View.GONE
        setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
    }

    // שעון
    private val clockBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    private val time = tv(96f, Color.WHITE, bold = true).apply { includeFontPadding = false; letterSpacing = 0.02f }
    private val date = tv(22f, Color.parseColor("#D8D8D8"))
    private val exit = tv(20f, Color.parseColor("#B0B0B0"))
    private val status = tv(17f, Color.parseColor("#34C759"), bold = true)

    // התרעה
    private val alertBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE }
    private val aIcon = ImageView(a)
    private val aTitle = tv(34f, Color.WHITE, bold = true)
    private val aSub = tv(24f, Color.parseColor("#E6FFFFFF"))
    private val aArea = tv(28f, Color.WHITE, bold = true).apply { maxLines = 4 }
    private val aLabel = tv(18f, Color.parseColor("#E6FFFFFF"))
    private val aTimer = tv(72f, Color.WHITE, bold = true).apply { includeFontPadding = false }
    private var aIconRes = 0
    private var alertKey = ""

    private var hiddenUntil = 0L
    private var downAt = 0L
    private val longPress = Runnable { hiddenUntil = System.currentTimeMillis() + 5 * 60_000L; update() }

    init {
        clockBox.addView(time)
        clockBox.addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        clockBox.addView(exit, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
        clockBox.addView(status.apply {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = GradientDrawable().apply { setColor(Color.parseColor("#1A34C759")); cornerRadius = dp(22).toFloat() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(36) })
        root.addView(clockBox, FrameLayout.LayoutParams(-1, -1))

        alertBox.setPadding(dp(24), 0, dp(24), 0)
        alertBox.addView(aIcon, LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(8) })
        alertBox.addView(aTitle)
        alertBox.addView(aSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        alertBox.addView(aArea, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
        alertBox.addView(aLabel, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(26) })
        alertBox.addView(aTimer)
        root.addView(alertBox, FrameLayout.LayoutParams(-1, -1))

        // אין לחיצות - רק לחיצה ארוכה של 3 שניות מחזירה זמנית את המסך הרגיל
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downAt = System.currentTimeMillis(); a.ui.postDelayed(longPress, 3000) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> a.ui.removeCallbacks(longPress)
            }
            true
        }
        (a.window.decorView as FrameLayout).addView(root, FrameLayout.LayoutParams(-1, -1))
    }

    /** כל חצי שנייה מהשעון של המסך הראשי */
    fun update() {
        val now = TimeSync.now(a)
        val show = System.currentTimeMillis() >= hiddenUntil && Shabbat.active(a)
        if (!show) { if (root.visibility != View.GONE) { root.visibility = View.GONE; lightBars(true) }; return }
        if (root.visibility != View.VISIBLE) { root.visibility = View.VISIBLE; root.requestApplyInsets(); lightBars(false) }

        val j = Prefs.activeAlert(a)
        val level = j?.optInt("level") ?: 0
        val firedAt = j?.optLong("firedAt") ?: 0L
        if (j != null && !AlertUi.expired(level, firedAt, System.currentTimeMillis())) { showAlert(j, level, firedAt); return }

        alertKey = ""
        alertBox.visibility = View.GONE; clockBox.visibility = View.VISIBLE
        root.setBackgroundColor(Color.BLACK)
        time.text = android.text.format.DateFormat.format("HH:mm", now)
        val end = try { Shabbat.endsAt(a, now) } catch (_: Exception) { null }
        // התאריך של יום היציאה - נכון לאורך כל השבת (גם בליל שבת, כשהתאריך העברי כבר התחלף)
        val ref = end?.let { it - 3600_000L } ?: now
        val sat = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem")).apply { timeInMillis = ref }
            .get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY
        date.text = (if (sat) "שבת קודש" else "חג שמח") + " · " + TimeFormat.hebrewDate(ref)
        exit.text = end?.let { (if (sat) "צאת השבת " else "צאת החג ") + android.text.format.DateFormat.format("HH:mm", it) } ?: ""
        exit.visibility = if (end == null) View.GONE else View.VISIBLE
        if (Prefs.enabled(a)) {
            status.text = "מוגן · צליל ורטט פעילים"; status.setTextColor(Color.parseColor("#34C759"))
            (status.background as GradientDrawable).setColor(Color.parseColor("#1A34C759"))
        } else {
            status.text = "צופר חירום כבוי – התרעות לא יגיעו"; status.setTextColor(Color.parseColor("#FF453A"))
            (status.background as GradientDrawable).setColor(Color.parseColor("#1AFF453A"))
        }
    }

    private fun showAlert(j: org.json.JSONObject, level: Int, firedAt: Long) {
        clockBox.visibility = View.GONE; alertBox.visibility = View.VISIBLE
        root.setBackgroundColor(Color.parseColor(AlertUi.color(level)))
        val key = j.toString()
        if (key != alertKey) {
            alertKey = key
            val (t1, t2) = AlertUi.head(j.optString("title"), level)
            aTitle.text = t1; aSub.text = t2
            aArea.text = j.optString("body")
            aArea.visibility = if (aArea.text.isEmpty()) View.GONE else View.VISIBLE
            aIconRes = 0
        }
        AlertUi.stageIcon(j.optString("title"), level, j.optInt("shelter", -1), firedAt).let { r ->
            if (r != aIconRes) { aIconRes = r; AlertUi.setIcon(aIcon, r) } }
        val t = AlertUi.timer(level, j.optInt("shelter", -1), firedAt)
        aLabel.visibility = if (t == null || t.first.isEmpty()) View.GONE else View.VISIBLE
        aTimer.visibility = if (t == null) View.GONE else View.VISIBLE
        if (t != null) {
            aLabel.text = t.first
            aTimer.textSize = if (t.first.isEmpty()) 26f else 72f
            aTimer.text = t.second
        }
    }

    /** סמלי שורת המצב לבנים על הרקע השחור, וחזרה למה שהיה כשהמסך נסגר */
    private var savedBars: Int? = null
    @Suppress("DEPRECATION")
    private fun lightBars(restore: Boolean) {
        val d = a.window.decorView
        if (!restore) {
            savedBars = d.systemUiVisibility
            d.systemUiVisibility = d.systemUiVisibility and
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        } else savedBars?.let { d.systemUiVisibility = it; savedBars = null }
    }

    fun destroy() {
        a.ui.removeCallbacks(longPress)
        try { (a.window.decorView as FrameLayout).removeView(root) } catch (_: Exception) { }
    }
}

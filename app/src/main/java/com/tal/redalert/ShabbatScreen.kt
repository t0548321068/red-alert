package com.tal.redalert

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.TimeZone

/**
 * מסך שבת: בזמן מצב שבת המסך הראשי מתחלף במסך שלא מגיב לנגיעות.
 * "שעון במסך" דלוק - המסך נשאר דלוק עם שעון (אפשר עם שניות), תאריך עברי, צאת השבת ומצב ההגנה.
 * כבוי - המסך שחור ונכבה לבד (כמו תמיד), ונדלק רק בהתרעה.
 * בהתרעה - המסך בצבע ההתרעה עם הסוג, האזור והספירה לאחור.
 * יציאה - כפתור בלחיצה ארוכה של 5 שניות (עד סוף השבת הנוכחית).
 */
internal class ShabbatScreen(private val a: MainActivity) {
    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    private fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(a).apply {
        textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private val root = FrameLayout(a).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        setOnTouchListener { _, _ -> true }   // לא מגיב לנגיעות, וגם לא מעביר למסך שמתחת
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

    // כפתור יציאה: מתמלא בזמן הלחיצה, ויוצא רק אחרי 5 שניות רצופות
    private val fill = ClipDrawable(GradientDrawable().apply { setColor(Color.parseColor("#55FFFFFF")); cornerRadius = dp(24).toFloat() },
        Gravity.RIGHT, ClipDrawable.HORIZONTAL)
    private val exitBtn = tv(15f, Color.parseColor("#9A9A9A")).apply {
        text = "יציאה – לחיצה ארוכה 5 שניות"
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = LayerDrawable(arrayOf(GradientDrawable().apply {
            setColor(Color.parseColor("#1AFFFFFF")); cornerRadius = dp(24).toFloat() }, fill))
    }
    private var press: ValueAnimator? = null

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

        exitBtn.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> press = ValueAnimator.ofInt(0, 10000).apply {
                    duration = 5000
                    addUpdateListener { fill.level = it.animatedValue as Int; if (it.animatedFraction >= 1f) leave() }
                    start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { press?.cancel(); press = null; fill.level = 0 }
            }
            true
        }
        root.addView(exitBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        (a.window.decorView as FrameLayout).addView(root, FrameLayout.LayoutParams(-1, -1))
    }

    /**
     * יציאה ממסך השבת עד סוף השבת הנוכחית (במצב "דלוק" ידני - עד שמשנים את המצב).
     * הופעל מכפתור השעון - חוזרים למצב השבת שהיה לפני
     */
    private fun leave() {
        press?.cancel(); press = null; fill.level = 0
        val prev = Prefs.shabbatQuick(a)
        if (prev >= 0) Prefs.setShabbatMode(a, prev)
        if (Shabbat.active(a, cached = false)) {
            val end = try { Shabbat.endsAt(a) } catch (_: Exception) { null }
            Prefs.setShabbatExitUntil(a, end ?: Long.MAX_VALUE)
        }
        update()
    }

    /** שעון במסך: לפי ההגדרה, ותמיד כשהופעל מכפתור השעון */
    private fun clockOn() = Prefs.shabbatClock(a) || Prefs.shabbatQuick(a) >= 0

    /** כל חצי שנייה מהשעון של המסך הראשי */
    fun update() {
        val now = TimeSync.now(a)
        val show = Shabbat.active(a) && System.currentTimeMillis() >= Prefs.shabbatExitUntil(a)
        if (!show) { if (root.visibility != View.GONE) { root.visibility = View.GONE; window(false) }; return }
        if (root.visibility != View.VISIBLE) { root.visibility = View.VISIBLE; root.requestApplyInsets() }
        window(true)

        val j = Prefs.activeAlert(a)
        val level = j?.optInt("level") ?: 0
        val firedAt = j?.optLong("firedAt") ?: 0L
        if (j != null && !AlertUi.expired(level, firedAt, System.currentTimeMillis())) { showAlert(j, level, firedAt); return }

        alertKey = ""
        alertBox.visibility = View.GONE
        root.setBackgroundColor(Color.BLACK)
        // בלי "שעון במסך" - מסך שחור, והטלפון מכבה אותו לבד
        clockBox.visibility = if (clockOn()) View.VISIBLE else View.INVISIBLE
        exitBtn.visibility = View.VISIBLE
        if (!clockOn()) return
        time.text = android.text.format.DateFormat.format(if (Prefs.shabbatSeconds(a)) "HH:mm:ss" else "HH:mm", now)
        time.textSize = if (Prefs.shabbatSeconds(a)) 72f else 96f
        // התאריך של היום עצמו: בליל שבת - של יום שישי, ומ-00:00 - של שבת
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem")).apply { timeInMillis = now }
        val days = arrayOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        date.text = (if (dow == Calendar.SATURDAY) "שבת" else "יום " + days[dow - 1]) + ", " + TimeFormat.hebrewDate(now)
        val end = try { Shabbat.endsAt(a, now) } catch (_: Exception) { null }
        val sat = end != null && Calendar.getInstance(TimeZone.getTimeZone("Asia/Jerusalem"))
            .apply { timeInMillis = end }.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY
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
        clockBox.visibility = View.GONE; alertBox.visibility = View.VISIBLE; exitBtn.visibility = View.VISIBLE
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

    /**
     * החלון בזמן מסך השבת: סמלי שורת המצב לבנים על השחור; עם "שעון במסך" - המסך נשאר דלוק
     * ומופיע גם מעל מסך הנעילה. ביציאה - הכול חוזר למה שהיה
     */
    private var savedBars: Int? = null
    private var keepOn = false
    @Suppress("DEPRECATION")
    private fun window(on: Boolean) {
        val d = a.window.decorView
        if (on && savedBars == null) {
            savedBars = d.systemUiVisibility
            d.systemUiVisibility = d.systemUiVisibility and
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        } else if (!on) savedBars?.let { d.systemUiVisibility = it; savedBars = null }
        val want = on && clockOn()
        if (want == keepOn) return
        keepOn = want
        if (want) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 27) a.setShowWhenLocked(want)
    }

    fun destroy() {
        press?.cancel(); press = null
        try { (a.window.decorView as FrameLayout).removeView(root) } catch (_: Exception) { }
    }
}

/** כפתור השעון: שעון לילה או מצב שבת (הפעלה מהירה - עם שעון; היציאה מחזירה את המצב שהיה) */
internal fun MainActivity.chooseClock() {
    AppSheet.show(this, "איזה שעון להפעיל?", "", "מצב שבת", onOk = {
        if (!Shabbat.active(this, cached = false)) {
            val prev = Prefs.shabbatMode(this)
            Prefs.setShabbatMode(this, Shabbat.ON)
            Prefs.setShabbatQuick(this, prev)
        } else Prefs.setShabbatExitUntil(this, 0L)   // כבר שבת - רק מחזירים את המסך
        shabbatScreen?.update()
    }, cancel = "שעון לילה", onCancel = { startActivity(android.content.Intent(this, ClockActivity::class.java)) },
        content = { card, _ -> card.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(1, (8 * resources.displayMetrics.density).toInt())) })
}

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
    /** נגד צריבת מסך: השעון מחשיך עד שחור ומאיר שוב (10 שניות לכל כיוון), כמו שעון הלילה - בלי לזוז */
    private var breathe: ValueAnimator? = null
    /** וגם תזוזה איטית למעלה ולמטה (5 דקות לכל כיוון) - השעון לא נשאר באותו מקום */
    private var drift: ValueAnimator? = null
    private fun breathing(on: Boolean) {
        if (!on) {
            breathe?.cancel(); breathe = null; clockBox.alpha = 1f; exitBtn.alpha = 1f
            drift?.cancel(); drift = null; clockBox.translationY = 0f; return
        }
        if (drift == null) drift = ValueAnimator.ofFloat(-dp(90).toFloat(), dp(90).toFloat()).apply {
            duration = 5 * 60_000L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            addUpdateListener { clockBox.translationY = it.animatedValue as Float }
            start()
        }
        if (breathe != null) return
        breathe = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 10_000
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            // בזמן לחיצה על היציאה - הכפתור נשאר גלוי
            addUpdateListener { val v = it.animatedValue as Float; clockBox.alpha = v; exitBtn.alpha = if (press != null) 1f else v }
            start()
        }
    }

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

    private fun clockOn() = Prefs.shabbatClock(a)

    /** השורות מוסתרות - ריווח רק סביב החור/הנוץ' של המצלמה, ככה שום דבר לא מוסתר */
    private fun fitBars() {
        val cut = if (android.os.Build.VERSION.SDK_INT >= 28) a.window.decorView.rootWindowInsets?.displayCutout else null
        val l = cut?.safeInsetLeft ?: 0; val t = cut?.safeInsetTop ?: 0
        val r = cut?.safeInsetRight ?: 0; val b = cut?.safeInsetBottom ?: 0
        if (root.paddingLeft != l || root.paddingTop != t || root.paddingRight != r || root.paddingBottom != b) root.setPadding(l, t, r, b)
    }

    /** כל חצי שנייה מהשעון של המסך הראשי */
    fun update() {
        val now = TimeSync.now(a)
        val show = Shabbat.active(a) && System.currentTimeMillis() >= Prefs.shabbatExitUntil(a)
        if (!show) { if (root.visibility != View.GONE) { root.visibility = View.GONE; window(false); breathing(false) }; return }
        if (root.visibility != View.VISIBLE) { root.visibility = View.VISIBLE; root.requestApplyInsets() }
        window(true)
        fitBars()

        val j = Prefs.activeAlert(a)
        val level = j?.optInt("level") ?: 0
        val firedAt = j?.optLong("firedAt") ?: 0L
        if (j != null && !AlertUi.expired(level, firedAt, System.currentTimeMillis())) { breathing(false); showAlert(j, level, firedAt); return }

        alertKey = ""
        alertBox.visibility = View.GONE
        root.setBackgroundColor(Color.BLACK)
        // בלי "שעון במסך" - מסך שחור, והטלפון מכבה אותו לבד
        clockBox.visibility = if (clockOn()) View.VISIBLE else View.INVISIBLE
        exitBtn.visibility = View.VISIBLE
        breathing(clockOn())
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
     * החלון בזמן מסך השבת: מסך מלא בלי שורת המצב ושורת הניווט; עם "שעון במסך" - המסך נשאר דלוק
     * ומופיע גם מעל מסך הנעילה. ביציאה - הכול חוזר למה שהיה
     */
    private var savedBars: Int? = null
    private var keepOn = false
    @Suppress("DEPRECATION")
    private fun window(on: Boolean) {
        val d = a.window.decorView
        // מסך מלא: שורת המצב ושורת כפתורי הניווט מוסתרות (מופיעות לרגע רק בהחלקה מהקצה), וחוזרות ביציאה
        if (on) {
            if (savedBars == null) {
                savedBars = d.systemUiVisibility
                // רק לאורך - המסך לא מסתובב בזמן מצב שבת
                a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            // בכל עדכון - גם אחרי חזרה לאפליקציה או החלקה מהקצה השורות נעלמות שוב
            if (android.os.Build.VERSION.SDK_INT >= 30) a.window.insetsController?.let {
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(android.view.WindowInsets.Type.systemBars())
            } else if (d.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) d.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else if (!on) savedBars?.let {
            if (android.os.Build.VERSION.SDK_INT >= 30) a.window.insetsController?.show(android.view.WindowInsets.Type.systemBars())
            d.systemUiVisibility = it; savedBars = null
            a.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED   // שוב מסתובב כרגיל
        }
        val want = on && clockOn()
        if (want == keepOn) return
        keepOn = want
        if (want) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 27) a.setShowWhenLocked(want)
    }

    fun destroy() {
        press?.cancel(); press = null; breathe?.cancel(); breathe = null; drift?.cancel(); drift = null
        try { (a.window.decorView as FrameLayout).removeView(root) } catch (_: Exception) { }
    }
}

/**
 * כפתור השעון: שעון לילה או מצב שבת. מצב שבת מופעל מיד (היציאה מחזירה את המצב שהיה);
 * בלי "שעון במסך" - המסך ננעל (הרשאת "נעילת מסך", מאשרים פעם אחת)
 */
internal fun MainActivity.chooseClock() {
    AppSheet.show(this, "איזה שעון להפעיל?", "", "מצב שבת", onOk = {
        if (!Shabbat.active(this, cached = false)) {
            val prev = Prefs.shabbatMode(this)
            Prefs.setShabbatMode(this, Shabbat.ON)
            Prefs.setShabbatQuick(this, prev)
        } else Prefs.setShabbatExitUntil(this, 0L)   // כבר שבת - רק מחזירים את המסך
        shabbatScreen?.update()
        if (!Prefs.shabbatClock(this)) lockScreen()
    }, cancel = "שעון לילה", onCancel = { startActivity(android.content.Intent(this, ClockActivity::class.java)) },
        content = { card, _ -> card.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(1, (8 * resources.displayMetrics.density).toInt())) })
}

private fun MainActivity.lockScreen() {
    val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
    val admin = android.content.ComponentName(this, LockAdmin::class.java)
    if (dpm.isAdminActive(admin)) { try { dpm.lockNow() } catch (_: Exception) { }; return }
    AppSheet.show(this, "נעילת מסך", "כדי שהפעלת מצב שבת תנעל את המסך מיד, צריך לאשר פעם אחת את ההרשאה \"נעילת מסך\".\n\nבלי ההרשאה המסך יישאר שחור ויכבה לבד.",
        "אישור", onOk = {
            try {
                startActivity(android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "רק נעילת המסך כשמפעילים מצב שבת"))
                pendingLock = true
            } catch (_: Exception) { }
        }, cancel = "לא עכשיו")
}

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
    private var iconV: android.widget.ImageView? = null
    private var areaV: TextView? = null
    private var timerV: TextView? = null
    private var timerLabelV: TextView? = null

    fun isShowing() = view != null && host == null
    /** כשהרצועה מוצגת בתוך מסך הנעילה (LockAlertActivity) ולא כחלון מעל אפליקציות */
    private var host: android.view.ViewGroup? = null
    private var onClosed: (() -> Unit)? = null

    /** סגירה אוטומטית לפי הצבע: מקדימה אחרי 2 דקות, סיום אחרי דקה (ירי - עד הסיום) */
    private var autoHide: Runnable? = null
    private fun scheduleHide(app: Context, at: Long) {
        autoHide?.let { ui.removeCallbacks(it) }
        val r = Runnable { hide(app) }
        autoHide = r
        ui.postDelayed(r, (at - System.currentTimeMillis()).coerceAtLeast(1000L))
    }

    private const val RED = "#D50000"
    private const val GREEN = "#2E7D32"    // האירוע הסתיים - ניתן לצאת

    fun canShow(c: Context) = Settings.canDrawOverlays(c)

    /** ההתרעה שמוצגת עכשיו בחלון (כדי להעביר אותה בין החלון שמעל אפליקציות לבין המסך הראשי) */
    var lastFull: Intent? = null; private set
    /** התרעה בממוזער שמחכה שהמסך הראשי ייפתח - מוצגת בתוכו (במקום הכרטיס) */
    @Volatile var pendingMain: Intent? = null
    /** מוצג בתוך המסך הזה (המסך הראשי / מסך הנעילה) ולא כחלון מעל אפליקציות */
    fun hostedIn(g: android.view.ViewGroup) = view != null && host === g

    @Suppress("NAME_SHADOWING")
    fun show(c: Context, full: Intent, into: android.view.ViewGroup? = null, inline: Boolean = false, closed: (() -> Unit)? = null) {
        // המסך הראשי מוצג עכשיו - נכנסים ישר לתוכו (במקום הכרטיס), בלי להופיע קודם כחלון ולקפוץ
        val m = if (into == null) MainActivity.shown?.takeIf { it.hasWindowFocus() && !Shabbat.active(it) } else null
        val into = into ?: m?.miniHost
        val inline = inline || m != null
        val closed = closed ?: m?.let { a -> { a.miniHost.visibility = View.GONE; a.updateAlertCard() } }
        hide(c)
        lastFull = full
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
        AlertUi.keepMs(level)?.let { scheduleHide(app, firedAt + it) }

        // סגנון One UI: גלולה - כותרת + אזור · זמן התגוננות + טיימר · ✕ בעיגול
        fun circle(icon: Int, size: Int) = android.widget.FrameLayout(app).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#38000000")) }
            addView(android.widget.ImageView(app).apply { setImageResource(icon) },
                android.widget.FrameLayout.LayoutParams(dp(size / 2), dp(size / 2), Gravity.CENTER))
        }
        val label = TextView(app).apply {
            setTextColor(Color.parseColor("#E6FFFFFF")); textSize = 12f   // כותרת קטנה - האזור הוא העיקר
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val areaLine = TextView(app).apply {
            text = area; setTextColor(Color.WHITE); textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = if (area.isEmpty()) View.GONE else View.VISIBLE
        }
        val icon = android.widget.ImageView(app).apply {
            AlertUi.setIcon(this, AlertUi.stageIcon(title, level, shelter, firedAt), big = false)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
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
                // ✕ סוגר לגמרי: בלי לפתוח את המסך הראשי, וגם הכרטיס שם לא יופיע להתרעה הזו
                val cb = onClosed
                hide(app)
                Prefs.setAlertDismissed(app, firedAt)
                cb?.invoke()
            }
        }
        val bar = LinearLayout(app).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(Color.parseColor(color)) }
                .also { bg = it }
            elevation = dp(6).toFloat()
            // אייקון סוג ההתרעה (משתנה לפי השלב: היכנסו → נשארים → ממתינים)
            addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(6) })
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(timerBox)
            addView(closeX, LinearLayout.LayoutParams(dp(34), dp(34)))
            // לחיצה על החלון לא פותחת מסך מלא - בממוזער החלון הוא התצוגה עצמה (סגירה ב-✕)
        }
        // מתחת לשורת המצב של הטלפון (הגובה שלה שונה בין טלפונים - עם מצלמה במסך היא גבוהה יותר),
        // ומתחת לשורת החיוויים של המסך הראשי אם ידוע איפה היא
        val sb = app.resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { app.resources.getDimensionPixelSize(it) } ?: dp(28)
        // מתחת לשורת החיוויים: אותו רווח כמו בין הכרטיסים במסך הראשי (12), מתחת לשורת המצב - 6
        val top = Prefs.miniY(app).takeIf { it > sb }?.let { it + dp(12) } ?: (sb + dp(6))
        // ברוחב שורת החיוויים בדיוק (אם ידוע), אחרת כמעט כל רוחב המסך
        val boxX = Prefs.miniX(app); val boxW = Prefs.miniW(app)
        val lp = WindowManager.LayoutParams(
            if (boxW > 0) boxW else WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (boxW > 0) { gravity = Gravity.TOP or Gravity.LEFT; x = boxX } else { gravity = Gravity.TOP; x = 0; horizontalMargin = 0.03f }
            y = top
        }
        if (into != null) {
            // על מסך נעול: בתוך המסך הקטן, בראשו
            // במסך הראשי: חלק מהמסך, במקום הכרטיס (בלי שוליים - המקום כבר בשורה של הכרטיסים).
            // במסך הנעילה: בדיוק כמו הכרטיס - בראש המסך, מתחת לשורת המצב, באותם שוליים
            into.addView(bar, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                if (!inline) setMargins(dp(14), sb + dp(8), dp(14), 0) })
            host = into; onClosed = closed
            m?.let { a -> a.miniHost.visibility = View.VISIBLE; a.updateAlertCard() }
        } else try { wm.addView(bar, lp) } catch (_: Exception) { return }
        view = bar
        bar.post { if (bar.height > 0) Prefs.setMiniH(app, bar.height) }   // המסך הראשי שומר לו מקום בגובה הזה
        labelV = label; areaV = areaLine; timerV = timer; timerLabelV = timerLabel; iconV = icon

        val t = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val enterEnd = firedAt + shelter.coerceAtLeast(0) * 1000L
                val stayEnd = enterEnd + Prefs.STAY_MS
                // ספירה לכניסה -> שהייה במרחב המוגן -> ממתינים לסיום: הכל באדום. ירוק רק בהודעת סיום
                when {
                    // מקדימה / סיום: כיתוב קצר וקבוע במקום הנוסח המלא
                    level == AlertService.LEVEL_PRE -> { label.text = "התרעה מקדימה"; timerLabel.text = ""; timer.textSize = 12f; timer.text = "בדקות הקרובות צפויות\nלהתקבל התרעות באזורך" }
                    level == AlertService.LEVEL_END -> { label.text = "האירוע הסתיים"; timerLabel.text = ""; timer.textSize = 12f; timer.text = "ניתן לצאת\nמהמרחב המוגן" }
                    level != AlertService.LEVEL_ALERT -> { label.text = title; timer.text = ""; timerLabel.text = "" }
                    shelter > 0 && now < enterEnd -> { label.text = title; timerLabel.text = "זמן התגוננות"; timer.textSize = 19f; timer.text = mmss(enterEnd - now); bg?.setColor(Color.parseColor(RED)) }
                    now < stayEnd -> { label.text = title; timerLabel.text = "נשארים במרחב המוגן"; timer.textSize = 19f; timer.text = mmss(stayEnd - now); bg?.setColor(Color.parseColor(RED)) }
                    // אחרי 10 הדקות: "ממתינים להודעת סיום" בצד שמאל, במקום הטיימר
                    else -> { label.text = title; timerLabel.text = ""; timer.textSize = 14f; timer.text = "ממתינים להודעת סיום"; bg?.setColor(Color.parseColor(RED)) }
                }
                AlertUi.setIcon(icon, AlertUi.stageIcon(title, level, shelter, firedAt, now), big = false)
                timerBox.visibility = if (timer.text.isEmpty()) View.GONE else View.VISIBLE
                // כיתוב ריק תופס שורה ודוחף את המלל למטה - מסתירים אותו כדי שהמלל יהיה באמצע הגובה
                timerLabel.visibility = if (timerLabel.text.isEmpty()) View.GONE else View.VISIBLE
                if (level == AlertService.LEVEL_ALERT && now < stayEnd) ui.postDelayed(this, 500)
            }
        }
        tick = t
        t.run()
    }

    // מעגלים למעלה - כמו במסך המלא ובכרטיס (1:30 ולא 1:29 בשנייה הראשונה)
    private fun mmss(ms: Long): String { val s = ((ms + 999) / 1000).toInt(); return "%d:%02d".format(s / 60, s % 60) }

    /** "האירוע הסתיים" בזמן שהחלון הקטן פתוח - הוא נהיה ירוק (במקום מסך מלא) */
    fun showEnd(area: String) {
        if (view == null) return
        tick?.let { ui.removeCallbacks(it) }; tick = null
        bg?.setColor(Color.parseColor(GREEN))
        labelV?.text = "האירוע הסתיים"
        AlertUi.setIcon(iconV, R.drawable.ic_list_end, big = false)
        timerLabelV?.text = ""; timerLabelV?.visibility = View.GONE
        timerV?.textSize = 12f; timerV?.text = "ניתן לצאת\nמהמרחב המוגן"
        (timerV?.parent as? View)?.visibility = View.VISIBLE
        if (area.isNotEmpty()) { areaV?.text = area; areaV?.visibility = View.VISIBLE }
        view?.context?.let { scheduleHide(it.applicationContext, System.currentTimeMillis() + (AlertUi.keepMs(AlertService.LEVEL_END) ?: 60_000L)) }
    }

    /** סוגר רק את הרצועה שבתוך מסך הנעילה (לא את החלון שמעל אפליקציות) */
    fun hideHosted(c: Context) { if (host != null) hide(c) }

    fun hide(c: Context) {
        tick?.let { ui.removeCallbacks(it) }; tick = null
        autoHide?.let { ui.removeCallbacks(it) }; autoHide = null
        bg = null; labelV = null; areaV = null; timerV = null; timerLabelV = null; iconV = null
        val v = view ?: return
        view = null
        val h = host; host = null; onClosed = null
        if (h != null) { h.removeView(v); return }
        try { (c.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) { }
    }
}

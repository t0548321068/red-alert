package com.tal.redalert

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.tal.redalert.MainActivity.C

// חלון "בדיקת עדכונים": נפתח בלחיצה על "בדוק אם יש עדכונים" (או על מספר הגרסה).
// קודם אנימציה של בדיקה, ואז אחד משלושה: גרסה חדשה (פרטים + התקנה) / מעודכן (פרטי הגרסה שלך) / לא ניתן לבדוק

/** עיגול עם טבעת: מסתובבת בזמן הבדיקה, ומלאה בצבע התוצאה כשהיא מסתיימת */
class UpdateRing(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; color = Color.parseColor("#2A2A2C") }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private var angle = 0f
    private var spinning = true
    private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }
    var color: Int = Color.parseColor("#4C7DFF")
        set(v) { field = v; invalidate() }

    fun spin() { spinning = true; if (!anim.isRunning) anim.start() }
    fun stop() { spinning = false; anim.cancel(); invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (spinning) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val r = RectF(arc.strokeWidth, arc.strokeWidth, width - arc.strokeWidth, height - arc.strokeWidth)
        arc.color = color
        if (spinning) { c.drawOval(r, track); c.drawArc(r, angle - 90f, 90f, false, arc) }
        else c.drawOval(r, arc)   // סיום: טבעת מלאה בצבע התוצאה
    }
}

internal fun MainActivity.showUpdateCheck() = ouiPage("בדיקת עדכונים", refreshOnFocus = false) { body, _ ->
    val blue = Color.parseColor("#4C7DFF"); val orange = Color.parseColor("#FF9F0A"); val green = Color.parseColor("#30D158")
    val dark = !C.LIGHT

    // ---- ראש העמוד: עיגול, כותרת ושורת משנה ----
    val circleBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    val ring = UpdateRing(this)
    val icon = text("⬇", 40f, C.TEXT).apply { gravity = Gravity.CENTER }
    val circle = FrameLayout(this).apply {
        background = circleBg
        addView(ring, FrameLayout.LayoutParams(-1, -1))
        addView(icon, FrameLayout.LayoutParams(-1, -1))
    }
    val headline = text("", 20f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER }
    val sub = text("", 14f, C.MUTED).apply { gravity = Gravity.CENTER }
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(22), dp(24), dp(18))
        addView(circle, LinearLayout.LayoutParams(dp(110), dp(110)))
        addView(headline, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    })
    // מה שמתחת לראש העמוד - נבנה מחדש בכל מצב
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(content)

    fun head(bg: String, bgLight: String, sym: String, symColor: Int, ringColor: Int, title: String, titleColor: Int, subText: String) {
        circleBg.setColor(Color.parseColor(if (dark) bg else bgLight))
        icon.text = sym; icon.setTextColor(symColor)
        ring.color = ringColor
        headline.text = title; headline.setTextColor(titleColor)
        sub.text = subText
    }
    val channel = if (Updater.isBetaBuild(this) || Prefs.betaUpdates(this)) "בטא" else "רגיל"
    val dateFmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US)
    fun installedAt() = try { dateFmt.format(java.util.Date(packageManager.getPackageInfo(packageName, 0).lastUpdateTime)) } catch (_: Exception) { "" }
    fun notesCard(title: String, notes: List<String>) {
        if (notes.isEmpty()) return
        ouiNote(content, title)
        val c = ouiCard()
        ouiText(c, notes.joinToString("\n") { "• $it" })
        ouiAdd(content, c)
    }
    fun bigButton(label: String, onClick: () -> Unit) = content.addView(text(label, 16f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14))
        background = GradientDrawable().apply { setColor(blue); cornerRadius = dp(26).toFloat() }
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(24), dp(10), dp(24), 0) })
    fun plainButton(label: String, onClick: () -> Unit) = content.addView(text(label, 15f, C.TEXT).apply {
        gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(24), dp(6), dp(24), 0) })

    var check: () -> Unit = {}
    check = {
        // ---- בודק ----
        content.removeAllViews()
        head("#162038", "#E3EBFF", "⬇", C.TEXT, blue, "בודק אם יש גרסה חדשה…", C.TEXT, "מתחבר לשרת העדכונים")
        ring.spin()
        val c = ouiCard()
        ouiRow(c, "גרסה מותקנת", end = Updater.versionLabel(this), endColor = C.MUTED)
        ouiDivider(c)
        ouiRow(c, "ערוץ", end = channel, endColor = C.MUTED)
        ouiAdd(content, c)
        val started = System.currentTimeMillis()
        Thread {
            var failed = false
            val u = try { Updater.lookup(this) } catch (_: Exception) { failed = true; null }
            val det = u?.let { try { Updater.details(it) } catch (_: Exception) { null } }
            // האנימציה מוצגת לפחות שנייה וחצי - שלא "יקפוץ" ישר לתוצאה
            val wait = 1500 - (System.currentTimeMillis() - started)
            if (wait > 0) Thread.sleep(wait)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                ring.stop()
                content.removeAllViews()
                when {
                    u != null -> {
                        // ---- יש גרסה חדשה ----
                        val beta = u.key.startsWith("beta")
                        val when_ = det?.date?.takeIf { it > 0 }?.let { "פורסמה " + dateFmt.format(java.util.Date(it)) } ?: ""
                        head("#3A2A0A", "#FFF1DC", "🆕", C.TEXT, orange,
                            (if (beta) "גרסת בטא זמינה – " else "גרסה חדשה זמינה – ") + u.ver, orange, when_)
                        val cd = ouiCard()
                        ouiRow(cd, "הגרסה שלך", end = Updater.versionLabel(this), endColor = C.MUTED)
                        ouiDivider(cd)
                        ouiRow(cd, "גרסה חדשה", end = u.label, endColor = C.MUTED)
                        det?.size?.takeIf { it > 0 }?.let {
                            ouiDivider(cd); ouiRow(cd, "גודל", end = "%.1f MB".format(java.util.Locale.US, it / 1048576.0), endColor = C.MUTED)
                        }
                        ouiAdd(content, cd)
                        notesCard("מה חדש בגרסה", det?.notes ?: emptyList())
                        if (beta) ouiNote(content, "גרסת בטא – לפני שחרור לכולם, יכולות להיות בה תקלות")
                        bigButton("התקן עכשיו") { Updater.install(this, u) }
                        plainButton("מאוחר יותר") { Updater.skip(this, u); pages.lastOrNull()?.dismiss() }
                    }
                    failed -> {
                        // ---- אין חיבור ----
                        head("#2C2C2E", "#E5E5EA", "!", C.MUTED, C.MUTED, "לא ניתן לבדוק כרגע", C.TEXT, "בדוק את החיבור לאינטרנט ונסה שוב")
                        val cd = ouiCard()
                        ouiRow(cd, "גרסה מותקנת", end = Updater.versionLabel(this), endColor = C.MUTED)
                        ouiAdd(content, cd)
                        plainButton("נסה שוב") { check() }
                    }
                    else -> {
                        // ---- מעודכן ----
                        head("#12301A", "#E3F4E5", "✓", green, green, "הגרסה שלך מעודכנת", green, "נבדק עכשיו")
                        val cd = ouiCard()
                        ouiRow(cd, "גרסה מותקנת", end = Updater.versionLabel(this), endColor = C.MUTED)
                        ouiDivider(cd)
                        ouiRow(cd, "עודכנה בטלפון", end = installedAt(), endColor = C.MUTED)
                        ouiDivider(cd)
                        ouiRow(cd, "ערוץ", end = channel, endColor = C.MUTED)
                        ouiAdd(content, cd)
                        notesCard("מה חדש בגרסה שלך", Updater.currentNotes(this))
                        plainButton("בדוק שוב") { check() }
                    }
                }
                updateVerInd()   // החיווי מעל מספר הגרסה (כתום / ירוק) לפי התוצאה
            }
        }.start()
    }
    check()
}

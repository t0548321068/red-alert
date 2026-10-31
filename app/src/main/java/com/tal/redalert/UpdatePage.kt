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
import android.widget.TextView
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

internal fun MainActivity.showUpdateCheck() {
    // הכפתור הכחול למטה - בדיוק כמו "בדוק אם יש עדכונים": "בדוק אם יש עדכונים", וכשיש גרסה חדשה - "התקן עכשיו"
    var pillLabel: TextView? = null
    var pillAction: (done: () -> Unit) -> Unit = { it() }
    var checking = false
    ouiPage("בדיקת עדכונים", refreshOnFocus = false,
        button = "בדוק אם יש עדכונים" to { done -> pillAction(done) },
        buttonLabel = { l ->
            pillLabel = l
            // הכפתור נבנה אחרי שהבדיקה הראשונה כבר התחילה - מסתירים אותו מיד אם עדיין בודקים
            ui.post { if (checking) (l.parent as? View)?.visibility = View.GONE }
        }) { body, _ ->
    val blue = Color.parseColor("#4C7DFF")
    val dark = !C.LIGHT

    // ---- ראש העמוד: עיגול, כותרת ושורת משנה ----
    val circleBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    val ring = UpdateRing(this)
    val icon = android.widget.ImageView(this)
    val circle = FrameLayout(this).apply {
        background = circleBg
        addView(ring, FrameLayout.LayoutParams(-1, -1))
        addView(icon, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER))
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

    /** sym = אייקון המצב, בצבע symColor */
    fun head(bg: String, bgLight: String, sym: Int, symColor: Int, ringColor: Int, title: String, titleColor: Int, subText: String) {
        circleBg.setColor(Color.parseColor(if (dark) bg else bgLight))
        icon.setImageResource(sym)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(symColor)
        ring.color = ringColor
        headline.text = title; headline.setTextColor(titleColor)
        sub.text = subText
        sub.visibility = if (subText.isEmpty()) View.GONE else View.VISIBLE   // בלי שורה ריקה
    }
    val channel = if (Updater.isBetaBuild(this) || Prefs.betaUpdates(this)) "בטא" else "רגיל"
    val dateFmt = java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.US)
    fun installedAt() = try { dateFmt.format(java.util.Date(packageManager.getPackageInfo(packageName, 0).lastUpdateTime)) } catch (_: Exception) { "" }
    fun notesCard(title: String, notes: List<String>) {
        if (notes.isEmpty()) return
        ouiNote(content, title)
        val c = ouiCard()
        ouiText(c, notes.joinToString("\n") { "• $it" })
        ouiAdd(content, c)
    }

    var check: (() -> Unit) -> Unit = {}
    fun pill(label: String, action: (done: () -> Unit) -> Unit) { pillLabel?.text = label; pillAction = action }
    var later: TextView? = null
    /** הכפתור הכחול למטה: מוסתר בזמן הבדיקה, מופיע עם התוצאה */
    fun showPill(on: Boolean) {
        (pillLabel?.parent as? View)?.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) later?.visibility = View.GONE
    }
    // כשיש גרסה חדשה: שני כפתורים צפים אחד ליד השני - "התקן עכשיו" משמאל, "מאוחר יותר" מימין
    fun twoButtons(on: Boolean, onLater: () -> Unit = {}) {
        val btn = pillLabel?.parent as? View ?: return
        val frame = btn.parent as? FrameLayout ?: return
        val w = resources.displayMetrics.widthPixels
        val lp = btn.layoutParams as FrameLayout.LayoutParams
        if (on) {
            val l = later ?: text("מאוחר יותר", 16f, C.TEXT, bold = true).apply {
                gravity = Gravity.CENTER; includeFontPadding = false
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (dark) "#3A3A3C" else "#E5E5EA")); cornerRadius = dp(23).toFloat() }
            }.also { later = it; frame.addView(it, FrameLayout.LayoutParams(0, dp(46))) }
            l.setOnClickListener { onLater() }
            l.visibility = View.VISIBLE
            val each = (w * 0.42f).toInt(); val side = (w * 0.06f).toInt()
            lp.width = each; lp.gravity = Gravity.BOTTOM or Gravity.LEFT; lp.leftMargin = side; lp.rightMargin = 0
            (l.layoutParams as FrameLayout.LayoutParams).apply {
                width = each; height = dp(46); gravity = Gravity.BOTTOM or Gravity.RIGHT; rightMargin = side; bottomMargin = lp.bottomMargin }
            l.requestLayout()
        } else {
            later?.visibility = View.GONE
            lp.width = (w * 0.5f).toInt(); lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.leftMargin = 0
        }
        btn.layoutParams = lp
    }
    check = { finished ->
        // ---- בודק ----
        content.removeAllViews()
        head("#162038", "#E3EBFF", R.drawable.ic_upd_checking, C.TEXT, blue, "בודק אם יש גרסה חדשה…", C.TEXT, "מתחבר לשרת העדכונים")
        pill("בדוק אם יש עדכונים") { done -> check(done) }
        twoButtons(false)
        checking = true
        showPill(false)   // בזמן הבדיקה - בלי כפתור למטה
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
                finished()   // הכפתור למטה חוזר מטעינה למלל
                if (isDestroyed) return@runOnUiThread
                checking = false
                showPill(true)
                ring.stop()
                content.removeAllViews()
                when {
                    u != null -> {
                        // ---- יש גרסה חדשה ----
                        val beta = u.key.startsWith("beta")
                        val when_ = det?.date?.takeIf { it > 0 }?.let { "פורסמה " + dateFmt.format(java.util.Date(it)) } ?: ""
                        head("#162038", "#E3EBFF", R.drawable.ic_upd_new, C.TEXT, blue,
                            (if (beta) "גרסת בטא זמינה – " else "גרסה חדשה זמינה – ") + u.ver, C.TEXT, when_)
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
                        pill("התקן עכשיו") { done -> done(); Updater.install(this, u) }
                        twoButtons(true) { Updater.skip(this, u); pages.lastOrNull()?.dismiss() }
                    }
                    failed -> {
                        // ---- אין חיבור ----
                        head("#2C2C2E", "#E5E5EA", R.drawable.ic_upd_offline, C.MUTED, C.MUTED, "לא ניתן לבדוק כרגע", C.TEXT, "בדוק את החיבור לאינטרנט ונסה שוב")
                        val cd = ouiCard()
                        ouiRow(cd, "גרסה מותקנת", end = Updater.versionLabel(this), endColor = C.MUTED)
                        ouiAdd(content, cd)
                    }
                    else -> {
                        // ---- מעודכן ----
                        head("#162038", "#E3EBFF", R.drawable.ic_upd_ok, C.TEXT, blue, "הגרסה שלך מעודכנת", C.TEXT, "")
                        val cd = ouiCard()
                        ouiRow(cd, "גרסה מותקנת", end = Updater.versionLabel(this), endColor = C.MUTED)
                        ouiDivider(cd)
                        ouiRow(cd, "עודכנה בטלפון", end = installedAt(), endColor = C.MUTED)
                        ouiDivider(cd)
                        ouiRow(cd, "ערוץ", end = channel, endColor = C.MUTED)
                        ouiAdd(content, cd)
                        notesCard("מה חדש בגרסה שלך", Updater.currentNotes(this))
                    }
                }
                updateVerInd()   // החיווי מעל מספר הגרסה (כתום / ירוק) לפי התוצאה
            }
        }.start()
    }
    check {}
    }
}

package com.tal.redalert
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// המסך הראשי: רענון התצוגה - מצב ההגנה, השעון, האזורים, ההיסטוריה

internal fun MainActivity.toggle() {
    val on = !Prefs.enabled(this)
    Prefs.setEnabled(this, on)
    if (on) AlertService.start(this, manual = true) else AlertService.stop(this)
    AlertWidget.updateAll(this)
    refresh()
}

internal fun MainActivity.evenClockGaps() {
    if (circleLabel.height == 0 || clockTime.height == 0) return
    // אזור ההתרעה יושב בין "מוגן" לשעון - הרווחים קבועים, לא מיישרים
    if (clockArea.childCount > 0) {
        val lp0 = circleLabel.layoutParams as LinearLayout.LayoutParams
        if (lp0.bottomMargin != dp(2)) { lp0.bottomMargin = dp(2); circleLabel.post { circleLabel.layoutParams = lp0 } }
        return
    }
    val target = if (greeting.visibility == View.VISIBLE)
        (haloOuter.top + (haloOuter.height - dp(42)) / 2) - (greeting.top + greeting.baseline)
    else dp(10)
    val b = android.graphics.Rect()
    clockTime.paint.getTextBounds("0", 0, 1, b)
    val digitTop = clockTime.top + clockTime.baseline + b.top
    val gap = digitTop - (circleLabel.top + circleLabel.baseline)
    val diff = target - gap
    if (kotlin.math.abs(diff) < 2) return
    val lp = circleLabel.layoutParams as LinearLayout.LayoutParams
    lp.bottomMargin += diff
    circleLabel.post { circleLabel.layoutParams = lp }
}

/** אנימציית מוגן: זוהר שמאיר ונכבה + שני גלים - רק כשמוגן ורק כשהמסך מוצג */
internal fun MainActivity.setSweep(on: Boolean) {
    if (!on) {
        pulseAnims.forEach { it.cancel() }; pulseAnims.clear()
        glow.alpha = 0f; ring1.alpha = 0f
        return
    }
    if (pulseAnims.isNotEmpty()) return
    // מחזור אחד מתואם: המגן מאיר, ובשיא הזוהר יוצא ממנו גל רדאר אחד שמתרחב ונעלם בזמן שהזוהר דועך
    val max = 56f / 42f
    pulseAnims += android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { va ->
            val t = va.animatedValue as Float
            glow.alpha = if (t < 0.35f) t / 0.35f else (1f - (t - 0.35f) / 0.45f).coerceAtLeast(0f)
            val r = (t - 0.3f) / 0.6f
            if (r in 0f..1f) {
                val e = 1f - (1f - r) * (1f - r)   // מאט לקראת הסוף
                ring1.scaleX = 1f + (max - 1f) * e; ring1.scaleY = ring1.scaleX
                ring1.alpha = 0.75f * (1f - r)
            } else ring1.alpha = 0f
        }
        start()
    }
}

internal fun MainActivity.refresh() {
    val sched = Prefs.enabled(this) && Prefs.scheduleOffNow(this)   // פעיל, אבל עכשיו מחוץ לתזמון
    val on = Prefs.enabled(this) && !sched
    val color = if (on) C.GREEN else C.OFF
    // כרטיס כהה, מסגרת ירוקה כשמוגן / אפורה כשלא
    // מוגן: רקע ירוק כהה (בבהיר - ירוק בהיר) עם מסגרת ירוקה; לא מוגן - הכרטיס הרגיל עם מסגרת אפורה
    // מוגן אבל אין אינטרנט: כרטיס כתום - ההתרעות לא יגיעו עד שהרשת תחזור
    val noNet = on && network()?.second != true
    val dark = Prefs.darkTheme(this@refresh)
    val off = !Prefs.enabled(this)   // כבוי לגמרי (לא לפי תזמון): כרטיס אדום
    circle.background = graphite(24).apply {
        when {
            noNet -> setColor(Color.parseColor(if (dark) "#33240A" else "#FDEFD9"))
            off -> setColor(Color.parseColor(if (dark) "#3A1214" else "#FBE3E4"))
            on -> setColor(Color.parseColor(if (dark) "#12301A" else "#E3F4E5"))
        }
        setStroke(dp(2), Color.parseColor(if (noNet) "#FF9F0A" else if (on) "#4CAF50" else if (off) "#FF453A" else "#555555")) }
    shieldIcon.setImageResource(if (on && !noNet) R.drawable.ic_shield_green else R.drawable.ic_shield_outline)
    shieldIcon.imageTintList = if (noNet) android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9F0A"))
        else if (off) android.content.res.ColorStateList.valueOf(Color.parseColor("#FF453A")) else null
    haloOuter.background = GradientDrawable().apply { shape = GradientDrawable.OVAL
        setColor(Color.parseColor(if (noNet) "#0FFF9F0A" else if (on) "#0F69F0AE" else if (off) "#0FFF453A" else "#00000000")) }
    haloInner.background = GradientDrawable().apply { shape = GradientDrawable.OVAL
        setColor(Color.parseColor(if (noNet) "#24FF9F0A" else if (on) "#2469F0AE" else if (off) "#24FF453A" else "#10FFFFFF")) }
    circleLabel.text = if (noNet) "אין רשת – התרעות לא יגיעו" else if (on) "מוגן" else if (sched) "כבוי לפי תזמון" else "לא מוגן"
    circleLabel.setTextColor(Color.parseColor(if (noNet) "#FF9F0A" else if (on) "#69F0AE" else if (off) "#FF453A" else "#AAAAAA"))
    setSweep(on && !noNet)
    updateIndicators()

    renderChips()
    renderHistory()
}

/** אזורי התרעה: פס בולט עם המיקום שלי, ומתחתיו האזורים הנוספים */
internal fun MainActivity.renderChips() {
    chips.removeAllViews()
    val list = Prefs.cities(this)
    val nearOn = Prefs.nearMe(this)
    val near = if (nearOn) Prefs.nearbyAreas(this) else emptyList()
    val muted = C.MUTED2
    val light = C.SOFT

    // אזור התרעה (לפי המיקום) - מוצג בכרטיס השעון
    clockArea.removeAllViews()
    val top = clockArea
    top.addView(text("אזור התרעה", 17f, C.SOFT))
    top.addView(text(if (!nearOn) "כבוי" else near.firstOrNull() ?: "מאתר…", 26f, C.TEXT, bold = true)
        .apply { setPadding(0, dp(2), 0, 0) })
    val sec = if (nearOn && near.isNotEmpty()) AreaData.shelterSeconds(this, near.take(1)) else null
    if (sec != null) top.addView(text("⏱ זמן התגוננות: ${AreaData.shelterText(sec)}", 17f, light)
        .apply { setPadding(0, dp(2), 0, 0) })
    for (i in 0 until top.childCount) (top.getChildAt(i) as? TextView)?.gravity = Gravity.CENTER
    // קו מתחת לאזור ההתרעה - מפריד בינו לבין השעון
    clockArea.addView(View(this).apply { setBackgroundColor(Color.parseColor("#33FFFFFF")) },
        LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10) })

    shelterLine.visibility = View.GONE   // הזמן מוצג עכשיו בפס המיקום
    renderExtraCard()
}

/** כרטיס במסך הראשי: היישובים והאזורים הנוספים (תצוגה בלבד) */
internal fun MainActivity.renderExtraCard() {
    areasSlot.removeAllViews()
    val (towns, dists) = extraSelection()
    val dmap = AreaData.districtMap(this)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = graphite(28); clipToOutline = true
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(0, dp(14), 0, dp(10))
    }
    // תצוגה בלבד - העריכה בהגדרות התרעות → אזורים נוספים
    card.addView(text("יישובים ואזורים נוספים", 16f, C.TEXT, bold = true).apply { setPadding(dp(18), 0, dp(18), dp(6)) })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun row(name: String, sub: String) = list.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(5), dp(18), dp(5))
        addView(text(name, 15f, C.TEXT).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (sub.isNotEmpty()) addView(text(sub, 12f, C.MUTED).apply { setSingleLine() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
    })
    fun section(title: String, names: List<String>, sub: (String) -> String) {
        if (names.isEmpty()) return
        list.addView(text(title, 12f, C.MUTED2).apply { setPadding(dp(18), dp(6), dp(18), dp(2)) })
        names.forEach { row(it, sub(it)) }
    }
    section("יישובים", towns) { if (it == AreaData.ALL_COUNTRY) "" else dmap[it] ?: "" }
    section("אזורים", dists) { "" }
    if (towns.isEmpty() && dists.isEmpty())
        list.addView(text("לא נבחרו", 14f, C.MUTED).apply { setPadding(dp(18), dp(4), dp(18), dp(6)) })
    // הרשימה נגללת בתוך הכרטיס כשהיא ארוכה; הכרטיס לא חורג מהמקום שנשאר במסך
    card.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) },
        LinearLayout.LayoutParams(-1, -2, 1f))
    areasSlot.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
}

/** במסך הראשי: שורת סיכום של ההתראה האחרונה */
internal fun MainActivity.renderHistory() {
    historyBox.removeAllViews()
    val e = Prefs.history(this).firstOrNull()
    val muted = C.MUTED2
    historyBox.addView(text(
        if (e == null) "אין התרעות עדיין"
        else (if (e.level == AlertService.LEVEL_PRE) "התרעה מקדימה" else e.title) +
            (if (e.ts > 0) " · " + dayLabel(e.ts) + " " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.ts)) else ""),
        12f, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
}

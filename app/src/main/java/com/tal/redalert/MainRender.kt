package com.tal.redalert
import com.tal.redalert.MainActivity.C
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
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
    // כרטיס כהה, מסגרת ירוקה כשמוגן / אפורה כשלא
    // מוגן: רקע ירוק כהה (בבהיר - ירוק בהיר) עם מסגרת ירוקה; לא מוגן - הכרטיס הרגיל עם מסגרת אפורה
    // מוגן אבל אין אינטרנט: כרטיס כתום - ההתרעות לא יגיעו עד שהרשת תחזור
    val noNet = on && network()?.second != true
    val off = !Prefs.enabled(this)   // כבוי לגמרי (לא לפי תזמון): כרטיס אדום
    // מצב ההגנה מוצג בכרטיס 1 (מגן + כיתוב). כרטיס השעון - כרטיס רגיל
    circle.background = graphite(26)
    // מוגן: מגן ירוק עם וי. לא מוגן: מגן אדום עם איקס. אין רשת: מגן כתום עם סימן קריאה (מעריכת צופר). לפי תזמון: מגן ריק
    shieldIcon.setImageResource(if (on && !noNet) R.drawable.ic_shield_green else if (noNet) R.drawable.ic_shield_alert
        else if (off) R.drawable.ic_shield_x else R.drawable.ic_shield_outline)
    shieldIcon.imageTintList = null
    // רק המגן: בלי עיגולי ההילה ובלי האנימציה
    haloOuter.background = null
    haloInner.background = null
    circleLabel.text = if (noNet) "אין רשת – התרעות לא יגיעו" else if (on) "אתה מוגן" else if (sched) "כבוי לפי תזמון" else "לא מוגן"
    circleLabel.setTextColor(if (noNet) C.ORANGE else if (on) C.TEXT else if (off) C.RED else C.MUTED)
    setSweep(false)   // בלי אנימציית מוגן
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

    // כרטיס 1: אזור התרעה (ימין) | זמן התגוננות (שמאל). שלוש שורות - אייקונים, כותרות, ערכים - כל שורה באותו גובה בשני הצדדים
    clockArea.removeAllViews()
    val sec = if (nearOn && near.isNotEmpty()) AreaData.shelterSeconds(this, near.take(1)) else null
    val area = if (!nearOn) "כבוי" else near.firstOrNull() ?: "מאתר…"
    val time = if (sec != null) AreaData.shelterText(sec) else "—"
    // שתי עמודות (אייקון, כותרת, ערך) וקו באמצע. בתוך LinearLayout אופקי הקו מקבל בדיוק את גובה העמודות
    // (FrameLayout עם קו MATCH_PARENT בתוך מסך נגלל מתח את הכרטיס עד סוף המסך)
    // iconRes = 0: האימוג'י ⏱ (כמו שהיה) במקום אייקון מצויר, באותו גודל
    fun column(iconRes: Int, title: String, v: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(if (iconRes != 0) android.widget.ImageView(this@renderChips).apply { setImageResource(iconRes) }
            else text("⏱", 30f, C.TEXT).apply { gravity = Gravity.CENTER; includeFontPadding = false },
            LinearLayout.LayoutParams(dp(40), dp(44)))
        addView(text(title, 14f, C.MUTED).apply { gravity = Gravity.CENTER; setSingleLine() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(text(v, 21f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }
    clockArea.addView(LinearLayout(this).apply {
        isBaselineAligned = false
        addView(column(R.drawable.ic_area_pin, "אזור התרעה", area), LinearLayout.LayoutParams(0, -2, 1f))
        addView(View(this@renderChips).apply { setBackgroundColor(C.DIV) },
            LinearLayout.LayoutParams(dp(1), -1).apply { marginStart = dp(10); marginEnd = dp(10) })
        addView(column(0, "זמן התגוננות", time), LinearLayout.LayoutParams(0, -2, 1f))
    }, LinearLayout.LayoutParams(-1, -2))

    shelterLine.visibility = View.GONE   // הזמן מוצג עכשיו בפס המיקום
    renderExtraCard()
}

/** כרטיס 4: היישובים והאזורים הנוספים (תצוגה בלבד) */
internal fun MainActivity.renderExtraCard() {
    areasSlot.removeAllViews()   // המקום נשאר ריק כדי שהפיד יישב למטה
    extraBox.removeAllViews()
    val (towns, dists) = extraSelection()
    val dmap = AreaData.districtMap(this)
    // תצוגה בלבד - העריכה בהגדרות התרעות → אזורים נוספים
    extraBox.addView(text("יישובים ואזורים נוספים", 17f, C.INK, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6)) })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun row(name: String, sub: String) = list.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(5), dp(2), dp(5))
        addView(text(name, 15f, C.INK).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (sub.isNotEmpty()) addView(text(sub, 12f, C.MUTED).apply { setSingleLine() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
    })
    fun section(title: String, names: List<String>, sub: (String) -> String) {
        if (names.isEmpty()) return
        list.addView(text(title, 12f, C.MUTED2).apply { setPadding(dp(2), dp(6), dp(2), dp(2)) })
        names.forEach { row(it, sub(it)) }
    }
    section("יישובים", towns) { if (it == AreaData.ALL_COUNTRY) "" else dmap[it] ?: "" }
    section("אזורים", dists) { "" }
    if (towns.isEmpty() && dists.isEmpty())
        list.addView(text("לא נבחרו", 15f, C.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(6)) })
    // רשימה ארוכה נגללת בתוך הכרטיס - שהכרטיס לא ידחוף את הפיד מהמסך
    val maxH = dp(170)
    extraBox.addView(object : ScrollView(this) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
    }.apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, -2))
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

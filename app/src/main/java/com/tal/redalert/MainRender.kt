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
    shieldIcon.setImageResource(if (on && !noNet) PROT_ICONS[Prefs.protIcon(this)] else if (noNet) R.drawable.ic_shield_alert
        else if (off) R.drawable.ic_shield_x else R.drawable.ic_shield_outline)
    shieldIcon.imageTintList = null
    // כרטיס "אתה מוגן" בסגנון שנבחר בהגדרות, בצבע המצב (ירוק מוגן · כתום אין רשת · אדום כבוי). לפי תזמון - כרטיס רגיל
    val tone = if (noNet) 1 else if (on) 0 else if (off) 2 else -1
    applyProtCard(on, noNet, off)
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
    // האייקון: ⏱ של המערכת (כמו שהיה), וסיכה מצוירת בקו דק באותו סגנון - שניהם באותו גובה
    fun column(emoji: String, title: String, v: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(if (emoji.isEmpty()) FrameLayout(this@renderChips).apply {
                addView(android.widget.ImageView(this@renderChips).apply {
                    setImageResource(R.drawable.ic_area_pin_line)
                    imageTintList = android.content.res.ColorStateList.valueOf(C.TEXT)
                }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            } else text(emoji, 30f, C.TEXT).apply {
                gravity = Gravity.CENTER; includeFontPadding = false
                // ⏱ של המערכת יוצא קטן מהסיכה (22dp): מגדילים את הגופן עד שגובה הציור עצמו = 22dp
                val b = android.graphics.Rect(); paint.getTextBounds(emoji, 0, emoji.length, b)
                if (b.height() > 0) textSize = 30f * dp(22) / b.height()
            },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        // מעריכת צופר: בכהה AAAAAA (בהיר יותר מהאפור הרגיל)
        addView(text(title, 15f, if (Prefs.darkTheme(this@renderChips)) Color.parseColor("#AAAAAA") else C.MUTED).apply { gravity = Gravity.CENTER; setSingleLine() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(text(v, 25f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }
    clockArea.addView(LinearLayout(this).apply {
        isBaselineAligned = false
        addView(column("", "אזור התרעה", area), LinearLayout.LayoutParams(0, -2, 1f))
        addView(View(this@renderChips).apply { setBackgroundColor(C.DIV) },
            LinearLayout.LayoutParams(dp(1), -1).apply { marginStart = dp(10); marginEnd = dp(10) })
        addView(column("⏱", "זמן התגוננות", time), LinearLayout.LayoutParams(0, -2, 1f))
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
    extraBox.addView(text("יישובים ואזורים נוספים", 20f, C.INK, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6)) })
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun row(name: String, sub: String) = list.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(5), dp(2), dp(5))
        // מעריכת צופר: 18, מודגש, ממורכז לגובה
        addView(text(name, 18f, C.INK, bold = true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL },   // ימין (התחלת השורה בעברית)
            LinearLayout.LayoutParams(0, -2, 1f))
        if (sub.isNotEmpty()) addView(text(sub, 12f, C.MUTED).apply { setSingleLine() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
    })
    fun section(title: String, names: List<String>, sub: (String) -> String) {
        if (names.isEmpty()) return
        list.addView(text(title, 15f, C.MUTED2).apply { setPadding(dp(2), dp(6), dp(2), dp(2)) })
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

/** סידור המסך הראשי (הגדרות ← תצוגה): איזה כרטיס למעלה, והברכה עוברת איתו לראש הכרטיס */
internal fun MainActivity.applyMainLayout() {
    val clockFirst = Prefs.mainLayout(this) == 1
    val col = card1.parent as? LinearLayout ?: return
    (greeting.parent as? android.view.ViewGroup)?.removeView(greeting)
    (if (clockFirst) clockCard else card1).addView(greeting, 0, LinearLayout.LayoutParams(-1, -2))
    // בלי ברכה מעליו - המגן צמוד יותר לראש הכרטיס
    (haloOuter.layoutParams as? LinearLayout.LayoutParams)?.let { it.topMargin = if (clockFirst) 0 else dp(10); haloOuter.layoutParams = it }
    val i = minOf(col.indexOfChild(card1), col.indexOfChild(clockCard))
    if (i < 0) return
    val lp1 = card1.layoutParams; val lp2 = clockCard.layoutParams
    col.removeView(card1); col.removeView(clockCard)
    if (clockFirst) { col.addView(clockCard, i, lp2); col.addView(card1, i + 1, lp1) }
    else { col.addView(card1, i, lp1); col.addView(clockCard, i + 1, lp2) }
}

/** כרטיס "אתה מוגן" בסגנון שנבחר בהגדרות, בצבע המצב (ירוק מוגן · כתום אין רשת · אדום כבוי). לפי תזמון - כרטיס רגיל */
internal fun MainActivity.applyProtCard(on: Boolean, noNet: Boolean, off: Boolean) {
    val tone = if (noNet) 1 else if (on) 0 else if (off) 2 else -1
    if (tone < 0) protStyle(card1, 0, 0, 0, 26) else protStyle(card1, Prefs.protBg(this), Prefs.protFrame(this), tone, 26)
}

/** רק כרטיס ההגנה, בלי לגעת בשאר המסך (מעמוד ההגדרות) */
internal fun MainActivity.refreshProtCard() {
    val sched = Prefs.enabled(this) && Prefs.scheduleOffNow(this)
    val on = Prefs.enabled(this) && !sched
    val noNet = on && network()?.second != true
    applyProtCard(on, noNet, !Prefs.enabled(this))
    if (on && !noNet) shieldIcon.setImageResource(PROT_ICONS[Prefs.protIcon(this)])
}

/** האייקונים של כרטיס ההגנה במצב מוגן (אין רשת / לא מוגן - תמיד האייקון הרגיל) */
internal val PROT_ICONS = intArrayOf(R.drawable.ic_shield_green, R.drawable.ic_prot_outline, R.drawable.ic_prot_solid,
    R.drawable.ic_prot_circle, R.drawable.ic_prot_home, R.drawable.ic_prot_bell)

/** רקע עם זוהר מסביב: הזוהר מצויר מחוץ לגבולות הכרטיס (ההורים לא חותכים אותו) */
private class GlowBg(val inner: android.graphics.drawable.Drawable, glow: Int, fill: Int, blur: Float, val r: Float) :
    android.graphics.drawable.Drawable() {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = fill; setShadowLayer(blur, 0f, 0f, glow)
    }
    override fun draw(c: android.graphics.Canvas) {
        c.drawRoundRect(android.graphics.RectF(bounds), r, r, p)
        inner.bounds = bounds; inner.draw(c)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: android.graphics.ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    override fun getOutline(o: android.graphics.Outline) { o.setRoundRect(bounds, r) }
}

/** ההורים לא חותכים את הזוהר - עד גלילה (לא כולל), כדי שהתוכן לא יגלוש מעל הכותרת */
private fun View.unclipParents() {
    var g = parent as? android.view.ViewGroup
    while (g != null && g !is ScrollView) {
        g.clipChildren = false; g.clipToPadding = false
        g = g.parent as? android.view.ViewGroup
    }
}

/** סגנון כרטיס ההגנה (רקע + מסגרת). tone: 0 ירוק · 1 כתום · 2 אדום. משמש גם לתצוגה המקדימה בהגדרות */
internal fun MainActivity.protStyle(v: View, bg: Int, frame: Int, tone: Int, radius: Int, glow: Boolean = true) {
    val dark = !C.LIGHT
    fun c(h: String) = Color.parseColor(h)
    val t = c(arrayOf("#30D158", "#FF9F0A", "#FF453A")[tone])
    val base = (graphite(radius) as? GradientDrawable)?.color?.defaultColor ?: C.CHIP
    val fill = c(if (dark) arrayOf("#0F2A18", "#2E1F08", "#2E0F0E")[tone] else arrayOf("#E6F7EA", "#FFF1DD", "#FDE7E6")[tone])
    val mid = c(if (dark) arrayOf("#14532D", "#5A3A0A", "#5A1A16")[tone] else arrayOf("#BFE8CB", "#FFDDA8", "#F8C4BF")[tone])
    val r = dp(radius).toFloat()
    val px = resources.displayMetrics.density
    val w = when (frame) { 1 -> 1f; 2 -> 3f; 3, 4 -> 1.5f; else -> 0f }
    val d = GradientDrawable().apply { cornerRadius = r }
    when (bg) {
        1 -> d.setColor(fill)
        2 -> { d.orientation = GradientDrawable.Orientation.TOP_BOTTOM; d.colors = intArrayOf(mid, base, base, base) }
        4 -> { d.gradientType = GradientDrawable.RADIAL_GRADIENT; d.setGradientCenter(0.5f, 0.35f)
            d.gradientRadius = dp(170).toFloat(); d.colors = intArrayOf((t and 0xFFFFFF) or 0x73000000, base) }
        5 -> { d.orientation = GradientDrawable.Orientation.BOTTOM_TOP; d.colors = intArrayOf(mid, base, base) }
        else -> d.setColor(base)
    }
    if (w > 0f) d.setStroke((w * px).toInt().coerceAtLeast(1), t)
    val bgd = if (bg == 3 || bg == 6) {
        // פס ירוק למעלה / למטה: שכבה בצבע המצב, ומעליה הכרטיס מוזז בפס
        val bar = GradientDrawable().apply { cornerRadius = r; setColor(t) }
        android.graphics.drawable.LayerDrawable(arrayOf(bar, d)).apply {
            if (bg == 3) setLayerInset(1, 0, dp(5), 0, 0) else setLayerInset(1, 0, 0, 0, dp(5))
        }
    } else d
    v.elevation = 0f
    // זוהר ירוק: הילה בצבע המצב מסביב לכרטיס
    v.background = if (frame == 3 && glow) {
        v.unclipParents(); v.post { v.unclipParents() }
        GlowBg(bgd, (t and 0xFFFFFF) or 0xB3000000.toInt(), base, dp(12).toFloat(), r)
    } else bgd
}

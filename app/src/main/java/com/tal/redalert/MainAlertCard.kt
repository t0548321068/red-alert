package com.tal.redalert
import com.tal.redalert.MainActivity.C
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

// המסך הראשי: כרטיס ההתרעה מעל השעון וצביעת הרקע לפי ההתרעה

internal fun MainActivity.applyMainTint(level: Int?) {
    val c = if (level == null) C.BG else {
        val a = Color.parseColor(AlertUi.color(level)); val f = if (Prefs.darkTheme(this)) 0.30f else 0.18f
        Color.rgb((Color.red(a) * f + Color.red(C.BG) * (1 - f)).toInt(),
            (Color.green(a) * f + Color.green(C.BG) * (1 - f)).toInt(),
            (Color.blue(a) * f + Color.blue(C.BG) * (1 - f)).toInt())
    }
    if (c == mainTint) return
    mainTint = c
    window.decorView.setBackgroundColor(c)
    @Suppress("DEPRECATION")
    window.statusBarColor = c
    @Suppress("DEPRECATION")
    window.navigationBarColor = c
}

/** כרטיס ההתרעה מעל השעון: נבנה כשההתרעה משתנה, והטיימר מתעדכן כל שנייה */
internal fun MainActivity.updateAlertCard() {
    val j = previewAlert ?: Prefs.activeAlert(this)
    val level = j?.optInt("level") ?: 0
    val firedAt = j?.optLong("firedAt") ?: 0L
    // ממוזער: במסך הראשי ההתרעה מוצגת כחלון הקטן (בתוך המסך, במקום הכרטיס) - לפי מצב ההתרעה עצמה,
    // ולא לפי מה שנשלח למסך: כך תמיד ממוזער ולעולם לא כרטיס (גם אחרי חזרה לאפליקציה)
    MiniOverlay.pendingMain = null
    val shab = Shabbat.active(this)
    val st = if (j == null || shab) -1 else Prefs.alertStyleFor(this, level)
    val wantMini = j != null && previewAlert == null && !shab &&
        !AlertUi.expired(level, firedAt) && Prefs.alertDismissed(this) != firedAt && AlertActivity.open == 0 &&
        (st == 3 || (st in 0..1 && Prefs.afterClose(this) == 1 && AlertActivity.shownFiredAt == firedAt))
    if (wantMini && !MiniOverlay.hostedIn(miniHost)) {
        val f = Intent().putExtra("title", j.optString("title")).putExtra("body", j.optString("body"))
            .putExtra("level", level).putExtra("shelter", j.optInt("shelter", -1)).putExtra("firedAt", firedAt)
            .putExtra("source", j.optString("source")).putExtra("mapAreas", j.optString("mapAreas"))
        MiniOverlay.show(this, f, into = miniHost, inline = true) { miniHost.visibility = View.GONE; updateAlertCard() }
    } else if (!wantMini && previewAlert == null && MiniOverlay.hostedIn(miniHost) &&
        MiniOverlay.lastFull?.getStringExtra("source") != "תצוגה מקדימה") MiniOverlay.hide(this)   // תצוגה מקדימה - נסגרת בעצמה
    val inMain = MiniOverlay.hostedIn(miniHost)
    miniHost.visibility = if (inMain) View.VISIBLE else View.GONE
    val mini = inMain || wantMini || (!shab && MiniOverlay.isShowing())
    val v = AlertUi.cardView(
        hasAlert = j != null, active = j != null && !AlertUi.expired(level, firedAt) && Prefs.alertDismissed(this) != firedAt,
        mini = mini, screensOpen = AlertActivity.open, shownBefore = AlertActivity.shownFiredAt == firedAt,
        cardStyle = !Shabbat.active(this) && Prefs.alertStyleFor(this, level) == 2, preview = previewAlert != null,
        ageMs = System.currentTimeMillis() - firedAt)
    if (!v.first) {
        applyMainTint(if (v.second) level else null)
        if (alertWrap.visibility != View.GONE) alertWrap.visibility = View.GONE
        setScrollMode(false)
        alertCardKey = ""; return
    }
    j ?: return
    applyMainTint(level)
    val key = j.toString()
    if (key != alertCardKey) {
        alertCardKey = key
        alertCard.removeAllViews()
        alertCard.background = GradientDrawable().apply {
            setColor(Color.parseColor(AlertUi.color(level))); cornerRadius = dp(28).toFloat() }
        val (t1, t2) = AlertUi.head(j.optString("title"), level)
        // אייקון מעל הכותרת לפי סוג ההתרעה
        alertCardIcon = android.widget.ImageView(this)
        alertCardIconRes = 0
        // גופנים גדולים: אייקון, כותרת, שורת המשנה ואזור ההתרעה (מודגש)
        alertCard.addView(alertCardIcon,
            LinearLayout.LayoutParams(dp(34), dp(34)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(2) })
        alertCard.addView(text(t1, 25f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
        alertCard.addView(text(t2, 19f, Color.parseColor("#E6FFFFFF")).apply { gravity = Gravity.CENTER })
        val body = j.optString("body")
        if (body.isNotEmpty()) alertCard.addView(text(body, 19f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")); cornerRadius = dp(20).toFloat() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); bottomMargin = dp(2) })
        alertCardLabel = null; alertCardTimer = null
        if (level == AlertService.LEVEL_ALERT) {
            alertCardLabel = text("", 13f, Color.parseColor("#E6FFFFFF")).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
            alertCardTimer = text("", 38f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; includeFontPadding = false }
            alertCard.addView(alertCardLabel); alertCard.addView(alertCardTimer)
        }
        val src = j.optString("source")
        if (src.isNotEmpty()) alertCard.addView(text("מקור ההתרעה: $src", 12f, Color.parseColor("#E6FFFFFF"))
            .apply { gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0) })
        alertWrap.visibility = View.VISIBLE
        setScrollMode(true)
    }
    // האייקון לפי השלב: בירי - סוג ההתרעה בזמן ההגעה, ואחריו בית (נשארים במרחב המוגן עד הודעה)
    AlertUi.stageIcon(j.optString("title"), level, j.optInt("shelter", -1), firedAt).let { r ->
        if (r != alertCardIconRes) { alertCardIconRes = r; AlertUi.setIcon(alertCardIcon, r, big = false) } }
    AlertUi.timer(level, j.optInt("shelter", -1), firedAt, title = j.optString("title"))?.let { (lbl, t) ->
        alertCardLabel?.text = lbl
        alertCardLabel?.visibility = if (lbl.isEmpty()) View.GONE else View.VISIBLE
        alertCardTimer?.textSize = if (lbl.isEmpty()) 20f else 38f
        alertCardTimer?.text = t
    }
}


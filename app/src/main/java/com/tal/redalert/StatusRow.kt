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
import com.tal.redalert.MainActivity.C
import com.tal.redalert.MainActivity.Row
import com.tal.redalert.MainActivity.Perm
import com.tal.redalert.MainActivity.SameHeight

// שורת החיוויים במסך הראשי (מוגן, צלילים, רשת, מיקום, מקורות, גרסה), הפיד הארצי ועמוד המקורות
// (חלק מהמסך הראשי - MainActivity; המצב עצמו נשמר שם)

/** חיווי: אייקון למעלה, טקסט מתחת */
internal fun MainActivity.indicator(icon: Int, onClick: (() -> Unit)?) = text("", 12f, C.TEXT).apply {
    setIndIcon(this, icon)
    maxLines = 1; ellipsize = TextUtils.TruncateAt.END   // 6 חיוויים בשורה - שורה אחת
    // האייקון גדל ב-15%, והרווח שמתחת לו קטן באותה מידה - גובה השורה לא משתנה
    compoundDrawablePadding = (dp(4) - (dp(18) * 0.15f).toInt()).coerceAtLeast(0)
    gravity = Gravity.CENTER
    if (onClick != null) setOnClickListener { onClick() }
}

/**
 * אייקון החיווי - מעט גדול מהגודל המקורי. אייקון שצויר גדול יותר (24dp) נשאר באותו גודל,
 * אבל תופס את אותו גובה כמו השאר - כדי שהטקסט מתחת לכל החיוויים יהיה באותו קו
 */
internal fun MainActivity.setIndIcon(v: TextView, icon: Int) {
    val d = getDrawable(icon)?.mutate() ?: return
    val dw = (d.intrinsicWidth * 1.45f).toInt(); val dh = (d.intrinsicHeight * 1.45f).toInt()
    val h = (dp(18) * 1.45f).toInt()
    val box = if (dh <= h) d.apply { setBounds(0, 0, dw, dh) } else SameHeight(d, dw, dh, h).apply { setBounds(0, 0, dw, h) }
    v.setCompoundDrawablesRelative(null, box, null, null)
}

/** חיווי צלילים: רמקול, ומסביבו קווי רטט כשהרטט פועל */
internal fun MainActivity.setSnd(label: String, color: Int, vib: Boolean) =
    setInd(sndInd, if (vib) R.drawable.ic_snd_vib else R.drawable.ic_set_sound, label, color)

/** האייקון בצבע המצב, הטקסט תמיד לבן-אפור */
internal fun MainActivity.setInd(v: TextView, icon: Int, label: String, color: Int) {
    // מתעדכן כל שנייה - בונים את האייקון מחדש רק כשמשהו בו השתנה
    val k = "$icon|$label|$color"
    if (v.tag == k) return
    v.tag = k
    setIndIcon(v, icon)
    v.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
    v.text = label
}

/** סוג החיבור: וויפי / נתונים / אחר, והאם יש אינטרנט בפועל */
internal fun MainActivity.network(): Pair<String, Boolean>? {
    val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
    val type = when {
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
        else -> "other"
    }
    return type to caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

internal fun MainActivity.locationOn(): Boolean {
    val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
    return hasLocationPerm() && lm.isLocationEnabled
}

/** הטקסט של פריט בפיד, עם אייקון סוג ההתרעה (בצבע הרמה) במקום תווי המקום */
internal fun MainActivity.feedSpan(it: FeedText.Item): CharSequence {
    val sp = android.text.SpannableString(it.text)
    val size = (feedLine.textSize * 1.25f).toInt()
    var i = it.text.indexOf('\uFFFC')
    while (i >= 0) {
        val d = getDrawable(it.icon)?.mutate()
        if (d != null) {
            d.setTint(Color.parseColor(it.color))
            val s = (size * AlertUi.scale(it.icon)).toInt()
            d.setBounds(0, 0, s, s)
            sp.setSpan(CenteredIcon(d), i, i + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        i = it.text.indexOf('\uFFFC', i + 1)
    }
    return sp
}

internal fun MainActivity.updateFeed() {
    val items = FeedText.recent(this)
    val win = Prefs.feedMinutes(this)
    val always = Prefs.feedAlways(this)
    if (items != feedItems || win != feedWin || always != feedAlwaysShown) {
        feedItems = items; feedWin = win; feedAlwaysShown = always; feedIdx = 0; feedTick = 0
        // הכרטיס מוצג כשיש התראות בטווח הזמן שנבחר, או תמיד (מתג בהגדרות › תצוגה)
        val first = items.firstOrNull()
        feedCard.visibility = if (first == null && !always) View.GONE else View.VISIBLE
        feedText = first?.text ?: ""
        feedLine.text = if (first != null) feedSpan(first)
            else FeedText.windowLabel(win).let { "אין התראות ב" + (if (it[0].isDigit()) "-" else "") + it }
        feedLine.setTextColor(C.TEXT)
        return
    }
    // כל 3 שניות - ההתראה הבאה עולה מלמטה
    if (items.size < 2 || ++feedTick < 3) return
    feedTick = 0
    feedIdx = (feedIdx + 1) % items.size
    val next = items[feedIdx]
    val h = feedLine.height.coerceAtLeast(dp(40)) / 2f
    feedLine.animate().translationY(-h).alpha(0f).setDuration(220).withEndAction {
        feedText = next.text; feedLine.text = feedSpan(next)
        feedLine.translationY = h
        feedLine.animate().translationY(0f).alpha(1f).setDuration(220).start()
    }.start()
}

internal fun MainActivity.updateIndicators() {
    updateFeed()
    updateVerInd()
    if (Prefs.enabled(this)) setInd(protInd, R.drawable.ic_shield_ind, "מוגן", C.GREEN)
    else setInd(protInd, R.drawable.ic_shield_ind_off, "לא מוגן", C.RED)
    // חיווי צלילים
    // רמקול = צלילים, קווי רטט מסביב = רטט; רק רטט - טלפון רוטט
    val vib = Prefs.vibesOn(this)
    when {
        Prefs.mutedNow(this) -> setInd(sndInd, R.drawable.ic_volume_off, "מושתק", C.GREEN)   // ההתרעות מגיעות - רק בלי צליל ורטט
        Shabbat.active(this) -> setSnd("שבת", Color.parseColor("#4C7DFF"), true)   // מצב שבת: תמיד עם צליל ורטט
        Prefs.loudSound(this) -> setSnd("חזק", C.GREEN, vib)
        !Prefs.soundsOn(this) && vib -> setInd(sndInd, R.drawable.ic_ind_vibrate, "רטט", C.ORANGE)
        !Prefs.soundsOn(this) -> setInd(sndInd, R.drawable.ic_volume_off, "כבוי", C.RED)
        Prefs.isQuietNow(this) -> setSnd("שקט", C.ORANGE, vib)
        else -> setSnd("צלילים", C.GREEN, vib)
    }
    if (Prefs.isQuietNow(this)) {
        quietLine.text = "🌙 שעות שקט עד ${hhmm(Prefs.quietTo(this))} · מקדימה וסיום בשקט"
        quietLine.setTextColor(C.ORANGE)
        quietLine.visibility = View.VISIBLE
    } else if (Prefs.quietOn(this)) {
        quietLine.text = "🌙 שעות שקט ${hhmm(Prefs.quietFrom(this))}–${hhmm(Prefs.quietTo(this))}"
        quietLine.setTextColor(C.MUTED)
        quietLine.visibility = View.VISIBLE
    } else {
        quietLine.text = ""
        quietLine.visibility = View.GONE
    }
    val net = network()
    // רשת: אייקון קבוע, מתחתיו סוג החיבור
    when {
        net == null -> setInd(netInd, R.drawable.ic_globe, "אין רשת", C.RED)
        net.first == "wifi" -> setInd(netInd, R.drawable.ic_globe, "Wi-Fi", if (net.second) C.GREEN else C.ORANGE)
        net.first == "cell" -> setInd(netInd, R.drawable.ic_globe, "נתונים", if (net.second) C.GREEN else C.ORANGE)
        else -> setInd(netInd, R.drawable.ic_globe, "מחובר", if (net.second) C.GREEN else C.ORANGE)
    }
    // מיקום: מתחתיו דיוק המיקום במטרים
    val accM = shownAccuracy()
    val acc = if (accM > 0) "$accM מ׳" else "—"
    if (locationOn()) setInd(locInd, R.drawable.ic_location, acc, if (hasPrecise()) C.GREEN else C.ORANGE)
    else setInd(locInd, R.drawable.ic_location, "כבוי", C.MUTED)

    if (!Prefs.enabled(this)) {
        setInd(srvInd, R.drawable.ic_antenna, "לא מחובר", C.MUTED); blinkServer(false)
    } else {
        // השרת שלנו מחובר - ירוק קבוע; מתחבר (עוד לא הגיע סימן חיים) - ירוק מהבהב;
        // השרת לא זמין והטלפון בודק בעצמו (גיבוי) - כתום "מחובר"; אין שום מקור - אדום
        val (label, color, blink) = when {
            AlertService.serverOk(this) -> Triple("מחובר", C.GREEN, false)
            serverConnecting() -> Triple("מתחבר…", C.GREEN, true)
            SourceHealth.upCount() > 0 -> Triple("מחובר", C.ORANGE, false)
            SourceHealth.anyConnecting() -> Triple("מתחבר…", C.ORANGE, true)
            else -> Triple("לא מחובר", C.RED, false)
        }
        setInd(srvInd, R.drawable.ic_antenna, label, color)
        blinkServer(blink)
    }
}

/** השרת שלנו מתחבר: עוד לא הגיע סימן חיים, וסימן החיים הראשון מגיע תוך 10 דקות מהפעלת ההאזנה */
internal fun MainActivity.serverConnecting(): Boolean = Prefs.enabled(this) && !AlertService.serverOk(this) &&
    AlertService.serverHbAt(this) == 0L && System.currentTimeMillis() - SourceHealth.startedAt() < 11 * 60 * 1000L

internal fun MainActivity.updateVerInd() {
    val up = Updater.hasUpdate(this)
    val label = "v${Updater.currentVersion(this)}" + if (Updater.isBetaBuild(this) && !Updater.currentVersion(this).endsWith("b")) "b" else ""
    if (verInd.text.toString() != label || (verBlink != null) != up) setInd(verInd, R.drawable.ic_info, label, if (up) C.ORANGE else C.GREEN)
    if (up && verBlink == null) {
        verBlink = android.animation.ValueAnimator.ofInt(255, 50).apply {
            duration = 600
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            addUpdateListener { a -> verInd.compoundDrawablesRelative[1]?.alpha = a.animatedValue as Int }
            start()
        }
    } else if (!up && verBlink != null) {
        verBlink?.cancel(); verBlink = null
        verInd.compoundDrawablesRelative[1]?.alpha = 255
    }
}

internal fun MainActivity.blinkServer(on: Boolean) {
    if (on && srvBlink == null) {
        srvBlink = android.animation.ObjectAnimator.ofFloat(srvInd, "alpha", 1f, 0.25f).apply {
            duration = 500
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            start()
        }
    } else if (!on && srvBlink != null) {
        srvBlink?.cancel(); srvBlink = null
        srvInd.alpha = 1f
    }
}

/** לחיצה על חיווי הרשת: הודעה קצרה עם סוג החיבור (כמו "מעדכן מיקום…") */
internal fun MainActivity.showNetworkInfo() {
    val net = network()
    val msg = when {
        net == null -> "אין רשת – התרעות לא יגיעו"
        !net.second -> "מחובר, אבל אין אינטרנט"
        net.first == "wifi" -> "מחובר ל-Wi-Fi"
        net.first == "cell" -> "מחובר לנתונים ניידים"
        else -> "מחובר לאינטרנט"
    }
    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.onLocationIndicator() {
    when {
        !hasLocationPerm() || !hasPrecise() -> onWeatherClick()
        !locationOn() -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        else -> {
            // לחיצות חוזרות בזמן שהמיקום עוד מתעדכן - לא מתחילים שוב
            val now = System.currentTimeMillis()
            if (now - locTapAt < 30_000L) return
            locTapAt = now
            android.widget.Toast.makeText(this, "מעדכן מיקום…", android.widget.Toast.LENGTH_SHORT).show()
            refreshNearby()
            updateWeather(force = true)
        }
    }
}

/** עמוד מקורות ההתרעה: כמה מחוברים, ומצב כל מקור - מתעדכן כל שנייה */
/** "לפני X שניות / דקות / שעות" */
internal fun MainActivity.agoText(t: Long): String {
    if (t <= 0) return "עוד לא"
    val sec = ((System.currentTimeMillis() - t) / 1000).coerceAtLeast(0)
    return when {
        sec < 3 -> "עכשיו"
        sec < 60 -> "לפני $sec שניות"
        sec < 120 -> "לפני דקה"
        sec < 3600 -> "לפני ${sec / 60} דקות"
        sec < 7200 -> "לפני שעה"
        else -> "לפני ${sec / 3600} שעות"
    }
}

internal fun MainActivity.showSourcesInfo() = ouiPage("מקורות התרעה",
    button = "בדיקה" to { done ->
        Thread {
            val up = PushService.checkServer()
            runOnUiThread {
                srvCheckAt = System.currentTimeMillis(); srvCheckUp = up
                // השרת ענה - מצב המקורות שלו מוצג מיד (סימן החיים שהוא שולח מגיע דרך Firebase)
                if (up != null) SourceHealth.viaServer(up, srvCheckAt)
                done()
            }
        }.start()
    }) { body, rebuild ->
    val on = Prefs.enabled(this)
    // השרת שלנו: בודק את כל המקורות כל שנייה ושולח לטלפון. בלי סימן חיים 35 דקות - הטלפון בודק בעצמו
    val hb = AlertService.serverHbAt(this)
    val srvOk = AlertService.serverOk(this)
    // סימן החיים הראשון מגיע תוך 10 דקות מהפעלת ההאזנה - עד אז "מתחבר"
    val srvConnecting = on && serverConnecting()
    // הבדיקה האחרונה (כפתור) נכשלה ואין סימן חיים חדש יותר
    val checkFailed = srvCheckAt > hb && srvCheckUp == null
    val sum = ouiCard()
    // 1. שרת התרעות
    val (srvLabel, srvColor) = when {
        !on -> "כבוי" to C.MUTED
        checkFailed -> "לא עונה" to C.RED
        srvOk -> "מחובר" to C.GREEN
        srvConnecting -> "מתחבר…" to C.ORANGE
        else -> "מנותק" to C.RED
    }
    ouiRow(sum, "שרת התרעות", sub = if (hb > 0) "סימן חיים ${agoText(hb)}" else "עוד לא התקבל סימן חיים",
        subColor = C.MUTED, end = srvLabel, endColor = srvColor)
    ouiDivider(sum)
    // 2. מקורות מחוברים - המספר בצבע: כולם ירוק, חלק כתום, אף אחד אדום
    val upN = SourceHealth.upCount(); val all = SourceHealth.total()
    ouiRow(sum, "מקורות מחוברים", end = if (on) "$upN/$all" else "—",
        endColor = if (!on) C.MUTED else if (upN == all) C.GREEN else if (upN > 0) C.ORANGE else C.RED)
    ouiDivider(sum)
    // 3. מקור הבדיקה: השרת או הטלפון
    ouiRow(sum, "מקור הבדיקה", end = if (!on) "—" else if (srvOk) "השרת" else if (srvConnecting) "הטלפון (זמנית)" else "הטלפון (גיבוי)",
        endColor = if (!on) C.MUTED else if (srvOk) C.GREEN else C.ORANGE)
    ouiDivider(sum)
    // 4. בדיקה אחרונה
    val last = maxOf(SourceHealth.lastAny(), hb, srvCheckAt)
    ouiRow(sum, "בדיקה אחרונה", end = if (on) agoText(last) else "—", endColor = C.MUTED)
    ouiAdd(body, sum)
    if (!on) ouiNote(body, "ההאזנה כבויה")
    else ouiNote(body, (if (srvOk) "השרת בודק את כל המקורות כל שנייה ושולח לטלפון רק כשיש התרעה – חוסך סוללה"
        else "כשהשרת לא זמין, הטלפון בודק את המקורות בעצמו") + "\nההתראה מוצגת מהמקור שהגיע הכי מהר, פעם אחת בלבד")
    val list = ouiCard()
    SourceHealth.NAMES.entries.forEachIndexed { i, (k, name) ->
        if (i > 0) ouiDivider(list)
        val (label, color) = when {
            !on -> "כבוי" to C.MUTED
            SourceHealth.isUp(k) -> "מחובר" to C.GREEN
            SourceHealth.connecting(k) -> "מתחבר…" to C.ORANGE
            else -> "מנותק" to C.RED
        }
        ouiRow(list, name, end = label, endColor = color)
    }
    ouiAdd(body, list)
    // מתעדכן בלייב כל שנייה כל עוד העמוד פתוח
    (body.tag as? Runnable)?.let { ui.removeCallbacks(it) }
    val live = Runnable { if (body.isAttachedToWindow) rebuild() }
    body.tag = live
    ui.postDelayed(live, 1000)
}

// ---- מה חדש ----

internal fun MainActivity.applyStatusRow() {
    ui.removeCallbacks(hideStatus)
    statusRow.visibility = if (Prefs.statusMode(this) == Prefs.STATUS_HIDDEN) View.GONE else View.VISIBLE
    if (Prefs.statusMode(this) == Prefs.STATUS_TIMED) ui.postDelayed(hideStatus, Prefs.statusSeconds(this) * 1000L)
}

internal fun MainActivity.statusModeLabel() = showModeLabel(Prefs.statusMode(this), Prefs.statusSeconds(this), female = true)

internal fun MainActivity.showModeLabel(mode: Int, sec: Int, female: Boolean = false) = when (mode) {
    Prefs.STATUS_HIDDEN -> if (female) "לא מוצגת" else "לא מוצג"
    Prefs.STATUS_TIMED -> "${secLabel(sec)} אחרי פתיחה"
    else -> if (female) "מוצגת קבוע" else "מוצג קבוע"
}

/** "10 שניות" / "דקה" / "1:30 דקות" */
internal fun MainActivity.secLabel(sec: Int) = when {
    sec < 60 -> "$sec שניות"
    sec == 60 -> "דקה"
    sec % 60 == 0 -> "${sec / 60} דקות"
    else -> "%d:%02d דקות".format(sec / 60, sec % 60)
}

internal fun MainActivity.chooseStatusMode(done: () -> Unit) = chooseShowMode("שורת חיוויים", { Prefs.statusMode(this) },
    { Prefs.setStatusMode(this, it) }, { Prefs.statusSeconds(this) }, { Prefs.setStatusSeconds(this, it) }, ::applyStatusRow,
    "\"לפי זמן\" – השורה מוצגת כשפותחים את האפליקציה או נוגעים במסך, ונעלמת אחרי הזמן שבוחרים", female = true, done = done)

/** בחירה משותפת (שורת חיוויים, כפתור צף): מוצג קבוע / לא מוצג / לפי זמן - ובלפי זמן כמה שניות, כולל מותאם */
internal fun MainActivity.chooseShowMode(title: String, mode: () -> Int, setMode: (Int) -> Unit, sec: () -> Int, setSec: (Int) -> Unit,
                           apply: () -> Unit, note: String, female: Boolean = false, done: () -> Unit) =
    ouiPage(title, onClose = done) { body, rebuild ->
        val c = ouiCard()
        listOf(Prefs.STATUS_ALWAYS to (if (female) "מוצגת קבוע" else "מוצג קבוע"),
            Prefs.STATUS_HIDDEN to (if (female) "לא מוצגת" else "לא מוצג"),
            Prefs.STATUS_TIMED to (if (female) "מוצגת לפי זמן" else "מוצג לפי זמן"))
            .forEachIndexed { i, (m, label) ->
                if (i > 0) ouiDivider(c)
                ouiRow(c, label, radio = mode() == m) { setMode(m); apply(); rebuild() }
            }
        ouiAdd(body, c)
        ouiNote(body, note)
        if (mode() == Prefs.STATUS_TIMED) {
            val t = ouiCard()
            t.addView(text("כמה זמן להציג", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
            val fixed = intArrayOf(5, 10, 15, 30, 60)
            val cur = sec()
            fixed.forEachIndexed { i, s ->
                if (i > 0) ouiDivider(t)
                ouiRow(t, secLabel(s), radio = cur == s) { setSec(s); apply(); rebuild() }
            }
            // מותאם: דקות ושניות בגלגלים (כמו בחירת הזמן של הפיד), עד 10 דקות
            val custom = cur !in fixed
            ouiDivider(t)
            ouiRow(t, "מותאם", if (custom) secLabel(cur) else "", radio = custom) {
                pickTime("זמן מותאם (דקות : שניות)", if (custom) cur else 20, hourCount = 11) { v ->
                    setSec(v.coerceIn(3, 600)); apply(); rebuild() }
            }
            ouiAdd(body, t)
        }
    }

internal fun MainActivity.applyTestFab() {
    ui.removeCallbacks(hideTestFab)
    val mode = Prefs.testFabMode(this)
    testFab.visibility = if (mode == Prefs.STATUS_HIDDEN) View.GONE else View.VISIBLE
    if (mode == Prefs.STATUS_TIMED) ui.postDelayed(hideTestFab, Prefs.testFabSeconds(this) * 1000L)
    // המקום בתחתית נשמר גם כשהכפתור נעלם לפי זמן - שהמסך לא יזוז
    mainCol.setPadding(mainCol.paddingLeft, mainCol.paddingTop, mainCol.paddingRight,
        dp(GAP) + if (mode != Prefs.STATUS_HIDDEN) dp(76) else 0)
}

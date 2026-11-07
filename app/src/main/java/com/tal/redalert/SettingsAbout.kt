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

// (חלק מהמסך הראשי - MainActivity; המצב עצמו נשמר שם)

// עמודי ההגדרות: עדכונים, אודות, מה חדש, יצירת קשר, תנאי שימוש, מקורות ורישיונות

/** מסך עדכונים: גרסה מותקנת + בדיקה, גרסאות בטא (למי שנפתח לו), ומה חדש */
internal fun MainActivity.showUpdates() = ouiPage("עדכונים",
    // חלון בדיקת העדכונים - אותו חלון כמו לחיצה על הגרסה בשורת החיווי
    button = "בדוק אם יש עדכונים" to { done -> done(); showUpdateCheck() }) { body, rebuild ->
    val ver = ouiCard()
    ouiRow(ver, "גרסה מותקנת", end = "v" + Updater.versionLabel(this), endColor = C.MUTED)
    ouiDivider(ver)
    // מתי האפליקציה עודכנה לאחרונה בטלפון
    val updatedAt = try {
        java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.US)
            .format(java.util.Date(packageManager.getPackageInfo(packageName, 0).lastUpdateTime))
    } catch (_: Exception) { "" }
    ouiRow(ver, "עודכן לאחרונה", end = updatedAt, endColor = C.MUTED)
    ouiDivider(ver)
    // מתי נבדק לאחרונה אם יש גרסה חדשה (גם הבדיקה האוטומטית)
    val checkedAt = Prefs.lastUpdateCheck(this).takeIf { it > 0 }?.let {
        java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.US).format(java.util.Date(it)) } ?: "עוד לא"
    ouiRow(ver, "בדיקה אחרונה", end = checkedAt, endColor = C.MUTED)
    ouiAdd(body, ver)
    ouiNote(body, "מהעדכון השני ההתקנה אוטומטית, בלי ללחוץ על \"התקן\"")

    // עדכון אוטומטי ברקע: כשהשרת מודיע על גרסה חדשה - יורדת ומותקנת לבד (לא באמצע אירוע)
    val au = ouiCard()
    val auto = Prefs.autoUpdate(this)
    ouiRow(au, "עדכון אוטומטי", sub = if (auto) "גרסה חדשה מותקנת לבד ברקע" else "כבוי – רק התראה על גרסה חדשה", on = auto) {
        Prefs.setAutoUpdate(this, !auto); rebuild() }
    ouiAdd(body, au)
    ouiNote(body, "לא מתעדכן באמצע אירוע – מחכה שיסתיים")

    // גרסאות בטא - רק למי שפתח אותן (7 לחיצות על הגרסה)
    if (Prefs.betaUnlocked(this) || Prefs.betaUpdates(this)) {
        val on = Prefs.betaUpdates(this)
        val beta = ouiCard()
        ouiRow(beta, "גרסאות בטא", sub = if (on) "מקבל גרסאות ניסיון" else "כבוי", on = on) {
            Prefs.setBetaUpdates(this, !on)
            Prefs.setSkippedVersion(this, "")
            if (Prefs.enabled(this)) AlertService.reloadPush(this)
            if (on) Prefs.setBetaUnlocked(this, false)   // כיבוי - האפשרות חוזרת להיות מוסתרת
            else showUpdateCheck()
            rebuild()
        }
        ouiAdd(body, beta)
        ouiNote(body, "גרסאות לפני שהן משוחררות לכולם · מזהה מכשיר: ${Prefs.deviceCode(this)}")
    }

    // מה חדש בגרסה המותקנת
    val news = ouiCard()
    news.addView(text("מה חדש", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), 0) })
    ouiText(news, whatsNewText(null) ?: "אין פירוט לגרסה הזו")
    ouiAdd(body, news)
}

/** עמוד אודות: אייקון, שם וגרסה, מקורות, רישיונות והבהרה */
/** נוסח ההבהרה - בעמוד האודות ובחלון שקופץ אחרי התקנה חדשה */
internal const val DISCLAIMER = "צופר חירום היא אפליקציה פרטית ולא רשמית שפותחה באופן עצמאי, והיא אינה קשורה לפיקוד העורף, לצה\"ל, למשרד הביטחון או לכל גוף ממשלתי אחר. " +
    "המידע על ההתרעות נאסף ממקורות ציבוריים – אתר פיקוד העורף, שירותי התרעה וערוצי טלגרם – ומוצג כפי שהתקבל. " +
    "מאחר שהאפליקציה תלויה ברשת, בשרתים ובמכשיר עצמו, ייתכנו עיכובים, תקלות, התרעות חסרות או התרעות שגויות. " +
    "גם הגדרות הטלפון, כמו חיסכון בסוללה, נא לא להפריע, השתקה או ביטול הרשאות, עלולות למנוע מההתרעה להישמע. " +
    "האפליקציה נועדה להשלים ולא להחליף את האפליקציה הרשמית של פיקוד העורף, את הצופרים ואת ההנחיות הרשמיות, ולכן מומלץ להשאיר מותקנת גם את האפליקציה הרשמית. " +
    "השימוש באפליקציה באחריות המשתמש בלבד, והמפתח אינו אחראי לכל נזק שייגרם כתוצאה מהסתמכות עליה. " +
    "בכל מקרה של ספק יש לפעול לפי ההנחיות הרשמיות של פיקוד העורף, שגוברות תמיד."

/**
 * אחרי התקנה חדשה בלבד (לא אחרי עדכון): חלון ההבהרה, ורק אחרי "הבנתי" - בקשות ההרשאות.
 * לפי זמן ההתקנה, כך שגם התקנה מחדש (אפילו אם ההגדרות שוחזרו מגיבוי) מציגה אותו שוב.
 * מחזיר true אם החלון הוצג
 */
internal fun MainActivity.disclaimerPending(): Boolean {
    val pi = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Exception) { return false }
    return pi.firstInstallTime == pi.lastUpdateTime && Prefs.disclaimerFor(this) != pi.firstInstallTime
}

internal fun MainActivity.showDisclaimerIfNew(then: () -> Unit): Boolean {
    if (!disclaimerPending()) return false
    val pi = packageManager.getPackageInfo(packageName, 0)
    AppSheet.show(this, "הבהרה", DISCLAIMER, "הבנתי", {
        Prefs.setDisclaimerFor(this, pi.firstInstallTime); then()
    }, cancelable = false)?.let { track(it) } ?: return false
    return true
}

internal fun MainActivity.showAbout() = ouiPage("אודות") { body, _ ->
    // ראש העמוד כמו "אודות" בסמסונג: אייקון גדול, שם וגרסה
    body.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(10), 0, dp(22))
        addView(android.widget.ImageView(this@showAbout).apply { setImageResource(R.mipmap.ic_launcher) },
            LinearLayout.LayoutParams(dp(88), dp(88)))
        addView(text("צופר חירום", 24f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        addView(text("גרסה " + Updater.versionLabel(this@showAbout), 14f, C.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        addView(text("התרעות פיקוד העורף בזמן אמת, גם כשהאפליקציה סגורה והמסך כבוי", 13f, C.MUTED2).apply {
            gravity = Gravity.CENTER; setPadding(dp(30), dp(10), dp(30), 0) })
    }, LinearLayout.LayoutParams(-1, -2))

    val note = ouiCard()
    note.addView(text("הבהרה", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), 0) })
    // פסקה רציפה, בלי נקודות
    ouiText(note, DISCLAIMER)
    ouiAdd(body, note)

    // תנאי שימוש, מקורות ורישיונות - כל אחד בעמוד משלו
    val more = ouiCard()
    ouiRow(more, "תנאי שימוש") { showTerms() }
    ouiDivider(more)
    ouiRow(more, "מקורות", "${SourceHealth.total()} מקורות") { showSourcesList() }
    ouiDivider(more)
    ouiRow(more, "רישיונות", "ספריות קוד ונתונים") { showLicenses() }
    ouiAdd(body, more)

    body.addView(text("נבנתה על ידי טל ששון · © ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} כל הזכויות שמורות",
        12f, C.MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(10), dp(20), 0) }, LinearLayout.LayoutParams(-1, -2))
}

/** אחרי עדכון: פעם אחת, כל מה שהשתנה מאז הגרסה הקודמת שהייתה מותקנת */
internal fun MainActivity.showWhatsNewIfUpdated() {
    val cur = Updater.currentVersion(this)
    val seen = Prefs.seenVersion(this)
    Prefs.setSeenVersion(this, cur)
    when {
        seen.isNotEmpty() && seen != cur -> showWhatsNew(sinceVersion = seen)
        // גרסאות ישנות לא שמרו את הערך - אם זו לא התקנה חדשה, מציגים את הגרסה הנוכחית
        seen.isEmpty() && (Prefs.enabled(this) || Prefs.history(this).isNotEmpty()) ->
            showWhatsNew(sinceVersion = null)
    }
}

/** הטקסט של "מה חדש". sinceVersion = null: רק הגרסה הנוכחית */
internal fun MainActivity.whatsNewText(sinceVersion: String?): String? {
    val log = try {
        org.json.JSONObject(assets.open("changelog.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    } catch (_: Exception) { return null }
    val cur = Updater.currentVersion(this)
    val versions = log.keys().asSequence()
        .filter { v -> if (sinceVersion == null) v == cur else Updater.isNewer(v, sinceVersion) && !Updater.isNewer(v, cur) }
        .sortedWith { a, b -> if (Updater.isNewer(a, b)) -1 else if (a == b) 0 else 1 }
        .toList()
    if (versions.isEmpty()) return null
    return versions.joinToString("\n\n") { v ->
        val items = log.getJSONArray(v)
        "גרסה $v\n" + (0 until items.length()).joinToString("\n") { "• " + items.getString(it) }
    }
}

internal fun MainActivity.showWhatsNew(sinceVersion: String?) {
    val text = whatsNewText(sinceVersion) ?: return
    AppSheet.show(this, "🆕 מה חדש", text, "הבנתי")?.let { track(it) }
}

/**
 * יצירת קשר: דיווח על בעיה ישירות למפתח (מגיע לאתר ולטלפון של טל).
 * אפשר לצרף פרטי מכשיר והגדרות - כך לא צריך להסביר בטלפון. התשובה מגיעה כהודעה באפליקציה
 */
internal fun MainActivity.showContact() {
    var draft = ""
    var attach = true
    lateinit var input: android.widget.EditText
    ouiPage("יצירת קשר", refreshOnFocus = false, button = "שלח למפתח" to { done ->
        val msg = input.text.toString().trim()
        if (msg.length < 3) { android.widget.Toast.makeText(this, "כתוב מה קרה", android.widget.Toast.LENGTH_SHORT).show(); done(); return@to }
        val info = if (attach) deviceInfo() else ""
        Thread {
            val ok = Reporter.sendReport(this, msg, info)
            runOnUiThread {
                done()
                if (ok) { input.setText(""); draft = "" }
                android.widget.Toast.makeText(this, if (ok) "✓ הדיווח נשלח – תודה!" else "השליחה נכשלה – בדוק את החיבור לאינטרנט", android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }) { body, rebuild ->
        val c = ouiCard()
        c.addView(text("דיווח על בעיה", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(8)) })
        input = android.widget.EditText(this).apply {
            hint = "מה קרה? למשל: לא קיבלתי התרעה בירושלים בשעה 14:30"
            setText(draft); textSize = 15f; minLines = 5; gravity = Gravity.TOP or Gravity.START
            setTextColor(C.TEXT); setHintTextColor(C.MUTED)
            background = GradientDrawable().apply { setColor(C.CARD2); cornerRadius = dp(18).toFloat() }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            filters = arrayOf(android.text.InputFilter.LengthFilter(1500))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { draft = s?.toString() ?: "" }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        }
        c.addView(input, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), 0, dp(14), dp(14)) })
        ouiAdd(body, c)

        val a = ouiCard()
        ouiRow(a, "צרף פרטי מכשיר והגדרות", sub = if (attach) "גרסה, דגם, אזורים, צלילים ומצב החיבור" else "כבוי", on = attach) {
            attach = !attach; rebuild() }
        ouiAdd(body, a)
        ouiNote(body, "הדיווח מגיע ישירות למפתח. התשובה תגיע כהודעה באפליקציה")
    }
}

/** פרטי המכשיר וההגדרות לדיווח (בלי מיקום מדויק ובלי מידע אישי) */
private fun MainActivity.deviceInfo(): String {
    val yes = { b: Boolean -> if (b) "כן" else "לא" }
    val lines = listOf(
        "גרסה" to Updater.versionLabel(this),
        "דגם" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
        "מוגן" to yes(Prefs.enabled(this) && !Prefs.scheduleOffNow(this)),
        "אזורים" to (Prefs.nearbyAreas(this) + Prefs.cities(this)).distinct().joinToString(", ").ifEmpty { "–" },
        "צלילים" to yes(Prefs.soundsOn(this)), "רטט" to yes(Prefs.vibesOn(this)),
        "מושתק" to yes(Prefs.mutedNow(this)), "צליל חזק" to yes(Prefs.loudSound(this)),
        "עקיפת שקט" to yes(Prefs.bypassSilent(this)), "שעות שקט" to yes(Prefs.quietOn(this)),
        "מצב שבת" to Shabbat.label(Prefs.shabbatMode(this)), "הקראה" to yes(Prefs.speakAlerts(this)),
        "תצוגה" to listOf("מסך מלא", "פופ-אפ", "כרטיס", "ממוזער").getOrElse(Prefs.alertStyle(this)) { Prefs.alertStyle(this).toString() },
        "רשת" to (network()?.first ?: "אין"),
    )
    return lines.joinToString("\n") { "${it.first}: ${it.second}" }
}

/** תנאי שימוש - נפתח מעמוד האודות. כרטיס לכל נושא */
internal fun MainActivity.showTerms() = ouiPage("תנאי שימוש") { body, _ ->
    listOf(
        "הסכמה לתנאים" to "השימוש באפליקציה מהווה הסכמה לתנאים אלה. מי שאינו מסכים לתנאים מתבקש שלא להשתמש באפליקציה.",
        "מהות השירות" to "צופר חירום היא אפליקציה פרטית ולא רשמית, שמציגה התרעות על סמך מידע ממקורות ציבוריים. " +
            "היא אינה קשורה לפיקוד העורף, לצה\"ל, למשרד הביטחון או לכל גוף ממשלתי, ואינה מהווה ערוץ התרעה רשמי.",
        "אין התחייבות לזמינות" to "השירות ניתן כמות שהוא (AS IS). אין התחייבות שההתרעות יגיעו תמיד, בזמן, במלואן או ללא טעויות. " +
            "תקלות ברשת, בשרתים, במקורות המידע או בטלפון, וכן הגדרות כמו חיסכון בסוללה, נא לא להפריע או השתקה, עלולות למנוע התרעה או לעכב אותה.",
        "אחריות המשתמש" to "האחריות לפעול לפי ההנחיות הרשמיות של פיקוד העורף מוטלת על המשתמש בלבד. " +
            "מומלץ להשאיר מותקנת גם את האפליקציה הרשמית ולהקשיב לצופרים. בכל סתירה – ההנחיות הרשמיות גוברות.",
        "הגבלת אחריות" to "המפתח לא יהיה אחראי לכל נזק, ישיר או עקיף, לגוף או לרכוש, שייגרם כתוצאה מהשימוש באפליקציה, " +
            "מהסתמכות עליה או מאי־הגעה של התרעה.",
        "מידע ופרטיות" to "האפליקציה לא מבקשת שם, טלפון או פרטים מזהים. לצורך תפעול נשלחים לשרת קוד מכשיר אקראי, דגם הטלפון וגרסת האפליקציה, " +
            "וכן דיווחי תקלות ופניות שנשלחו ממסך יצירת קשר. המיקום משמש להתרעות לפי מיקום, למזג האוויר ולזמני שבת, " +
            "ונשלח לשרת רק לצורך זה ובלי קוד המכשיר.",
        "עדכונים ושינויים" to "האפליקציה מתעדכנת מעת לעת, ותכונות עשויות להשתנות או להיפסק. " +
            "התנאים עשויים להתעדכן, והנוסח המעודכן יופיע תמיד בעמוד זה."
    ).forEach { (h, b) ->
        val c = ouiCard()
        c.addView(text(h, 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), 0) })
        ouiText(c, b)
        ouiAdd(body, c)
    }
}

/** מקורות ההתרעות - נפתח מעמוד האודות */
internal fun MainActivity.showSourcesList() = ouiPage("מקורות") { body, _ ->
    val src = ouiCard()
    SourceHealth.NAMES.values.forEachIndexed { i, n -> if (i > 0) ouiDivider(src); ouiRow(src, n) }
    ouiAdd(body, src)
    ouiNote(body, "ההתרעה מוצגת מהמקור שהגיע הכי מהר, פעם אחת בלבד")
}

/** רישיונות - נפתח מעמוד האודות */
internal fun MainActivity.showLicenses() = ouiPage("רישיונות") { body, _ ->
    val lic = ouiCard()
    listOf("מפה" to "Leaflet · BSD-2", "גבולות אזורים וזמני הגעה" to "oref_alert · MIT",
        "רשת" to "OkHttp · Apache 2.0", "מזג אוויר ושמות מקומות" to "Open-Meteo · OpenStreetMap")
        .forEachIndexed { i, (a, b) -> if (i > 0) ouiDivider(lic); ouiRow(lic, a, sub = b, subColor = C.MUTED) }
    ouiAdd(body, lic)
}

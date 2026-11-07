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

class MainActivity : Activity() {

    /** צבעים לפי ערכת הנושא (כהה / בהירה) */
    internal object C {
        var BG = 0; var CHIP = 0; var TEXT = 0; var MUTED = 0
        var GREEN = 0; var OFF = 0
        var BLUE = 0; var RED = 0; var ORANGE = 0
        var CARD2 = 0; var DIV = 0; var SEPV = 0; var SOFT = 0; var MUTED2 = 0; var LINE2 = 0
        var LIGHT = false

        fun apply(dark: Boolean) {
            fun c(d: String, l: String) = Color.parseColor(if (dark) d else l)
            BG = c("#000000", "#F2F2F7")
            CHIP = c("#2C2C2E", "#E5E5EA")
            TEXT = c("#F2F2F2", "#1C1C1E")
            MUTED = c("#8E8E93", "#6E6E73")
            GREEN = c("#30D158", "#248A3D")
            OFF = c("#636366", "#8E8E93")
            BLUE = c("#0A84FF", "#007AFF")
            RED = c("#FF3B30", "#E0352B")
            ORANGE = c("#FF9F0A", "#E08600")
            CARD2 = c("#1B1B1D", "#FFFFFF")      // כרטיסים (One UI)
            DIV = c("#2A2A2C", "#E0E0E5")        // קו בין שורות
            SEPV = c("#5C5C5E", "#C7C7CC")       // קו לפני מתג (לפי צילום סמסונג)
            SOFT = c("#E6FFFFFF", "#CC1C1C1E")   // טקסט משני רך
            MUTED2 = c("#AAAAAA", "#6E6E73")     // טקסט אפור
            LINE2 = c("#22FFFFFF", "#22000000")
            LIGHT = !dark
        }
    }

    internal lateinit var circle: FrameLayout
    internal lateinit var shieldIcon: android.widget.ImageView
    internal lateinit var circleLabel: TextView
    internal lateinit var haloOuter: FrameLayout
    internal lateinit var haloInner: FrameLayout
    internal lateinit var glow: View                // זוהר מאחורי המגן
    internal lateinit var ring1: View               // גל רדאר שיוצא מהמגן
    internal val pulseAnims = mutableListOf<android.animation.Animator>()
    internal lateinit var chips: LinearLayout
    internal lateinit var historyBox: LinearLayout
    internal lateinit var clockTime: TextView
    internal lateinit var greeting: TextView
    internal lateinit var clockDay: TextView
    internal lateinit var weatherLine: TextView
    internal lateinit var shelterLine: TextView
    internal lateinit var quietLine: TextView
    internal lateinit var feedLine: TextView
    internal lateinit var feedCard: FrameLayout
    internal lateinit var mainCol: LinearLayout
    internal lateinit var testFab: View
    internal var feedText = ""
    internal lateinit var netInd: TextView
    internal lateinit var protInd: TextView   // מוגן / לא מוגן
    internal lateinit var locInd: TextView
    internal lateinit var srvInd: TextView
    internal lateinit var verInd: TextView
    internal lateinit var statusRow: View   // שורת החיוויים - אפשר להסתיר בהגדרות התצוגה
    internal lateinit var sndInd: TextView   // צלילים: רגיל / חזק / שקט / מושתק / כבוי
    internal var weatherAt = 0L
    internal var weatherAccuracy = -1
    internal var weatherShown = false   // יש כבר מזג אוויר על המסך
    internal var previewAlert: org.json.JSONObject? = null   // תצוגה מקדימה של כרטיס ההתרעה (רק במסך, בלי לשמור)
    internal var locTapAt = 0L          // לחיצה אחרונה על חיווי המיקום
    internal var lastHistoryKey = ""

    internal fun granted(p: String) =
        checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    internal fun hasLocationPerm() =
        granted(android.Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
    internal fun hasPrecise() = granted(android.Manifest.permission.ACCESS_FINE_LOCATION)

    /** מספר ראשוני בפתיחה (נלקח פעם אחת, לא מתעדכן כל שנייה) - עד שיש מדידה */
    internal var initialAcc = -1
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) updateWeather(force = true)
        if (requestCode == 3 && hasPrecise()) toggleNearMe()
    }

    internal val ui = Handler(Looper.getMainLooper())
    /**
     * השעון: קל ומהיר, מתוזמן קודם כל - כך שתקלה או עיכוב בשאר המסך לא עוצרים את השניות וההבהוב.
     * רץ כל חצי שנייה (שנייה מלאה + אמצע השנייה להבהוב הנקודתיים).
     */
    internal val tick = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            val ms = TimeSync.now(this@MainActivity) % 1000   // לפי שעון השרת - זהה בכל הטלפונים
            ui.postDelayed(this, if (ms < 500) 500 - ms else 1000 - ms)
            try { updateClock() } catch (_: Exception) { }
            try { shabbatScreen?.update() } catch (_: Exception) { }   // מסך שבת פשוט (בזמן מצב שבת)
            // שאר המסך - פעם בשנייה (בתחילת השנייה), ובנפרד מהשעון
            if (ms < 500) ui.post(slowTick)
        }
    }
    internal val slowTick = Runnable {
        try {
            updateIndicators()
            updateWeather()
            updateAlertCard()
            // שאר המסך מתעדכן רק כשמשהו השתנה (ורק אחרי שנתוני היישובים נטענו ברקע)
            if (!AreaData.loaded()) return@Runnable
            val key = "${network()?.second == true}|${Prefs.enabled(this@MainActivity)}|${Prefs.history(this@MainActivity).firstOrNull()?.ts}|" +
                Prefs.nearbyAreas(this@MainActivity).joinToString()   // גם כשהאזורים הקרובים משתנים
            if (key != lastHistoryKey) { lastHistoryKey = key; refresh() }
        } catch (_: Exception) { }
    }

    /** מסך שבת פשוט - מעל המסך הראשי בזמן מצב שבת */
    internal var shabbatScreen: ShabbatScreen? = null
    /** חזרה מאישור "נעילת מסך" - נועלים את המסך (מצב שבת הופעל מכפתור השעון) */
    internal var pendingLock = false

    internal lateinit var alertCard: LinearLayout
    internal lateinit var alertWrap: FrameLayout
    internal lateinit var areasSlot: FrameLayout

    /** כשכרטיס ההתרעה מוצג - האזורים בגובה מלא והמסך נגלל; בלעדיו - האזורים ממלאים את מה שנשאר */
    internal lateinit var clockArea: LinearLayout
    internal var mainScroll: ScrollView? = null
    /** האם המסך הראשי עצמו נגלל (רק כשכרטיס ההתרעה מוצג) */
    internal var pageScrolls = false

    internal fun setScrollMode(want: Boolean) {
        // מסך מסובב (לרוחב): אין מספיק גובה - המסך הראשי תמיד נגלל
        val on = want || resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        pageScrolls = on
        if (!on) mainScroll?.scrollTo(0, 0)
        val lp = areasSlot.layoutParams as LinearLayout.LayoutParams
        val h = if (on) -2 else 0; val w = if (on) 0f else 1f
        if (lp.height != h || lp.weight != w) { lp.height = h; lp.weight = w; areasSlot.layoutParams = lp }
    }
    internal var alertCardKey = ""
    internal var alertCardIcon: android.widget.ImageView? = null
    internal var alertCardIconRes = 0
    internal var alertCardTimer: TextView? = null
    internal var alertCardLabel: TextView? = null

    /** רקע המסך הראשי נצבע לפי ההתרעה הפעילה (אדום / כתום / ירוק, בגוון כהה); null = הרקע הרגיל */
    internal var mainTint = 0
    internal fun updateClock() {
        val now = TimeSync.now(this)
        clockTime.text = TimeFormat.clock(this, now)
        clockDay.text = TimeFormat.dayLine(this, now)
        val g = Prefs.showGreeting(this)
        greeting.visibility = if (g) View.VISIBLE else View.GONE
        if (g) greeting.text = TimeFormat.greeting(now) + Prefs.greetingName(this).let { if (it.isEmpty()) "" else " $it" }
    }

    /** רווח אחיד בין כרטיס לכרטיס במסך הראשי */
    internal val GAP = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = Prefs.darkTheme(this)
        setTheme(if (dark) android.R.style.Theme_DeviceDefault_NoActionBar
                 else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        C.apply(dark)
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.decorView.setBackgroundColor(C.BG)
        @Suppress("DEPRECATION")
        window.statusBarColor = C.BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = C.BG
        // אייקונים כהים בשורת המצב כשהרקע בהיר
        if (Build.VERSION.SDK_INT >= 30) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(GAP))   // כותרת קרובה יותר לראש המסך
        }
        mainCol = col

        // כותרת + גלגל שיניים
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("צופר חירום", 24f, C.TEXT, bold = true).apply { setPadding(dp(4), 0, dp(4), 0) })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // אייקונים למעלה בשמאל (במקום התפריט התחתון): שעון · התרעות · הגדרות (הגדרות הכי שמאלי)
        fun headerIcon(res: Int, click: () -> Unit) = header.addView(android.widget.ImageView(this).apply {
            setImageResource(res)
            imageTintList = android.content.res.ColorStateList.valueOf(C.TEXT)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(C.LINE2), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        headerIcon(R.drawable.ic_nav_clock) { chooseClock() }
        headerIcon(R.drawable.ic_nav_alerts) { showRecent() }
        headerIcon(R.drawable.ic_gear) { showSettings() }
        col.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // שעון
        clockTime = text("", 44f, C.TEXT).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        clockDay = text("", 19f, C.TEXT).apply {
            gravity = Gravity.CENTER
        }
        greeting = text("", 20f, C.TEXT).apply { gravity = Gravity.CENTER }
        // כרטיס השעון: רקע גרפיט, הכל ממורכז, טקסט לבן
        val clockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#232526"), Color.parseColor("#414345"))).apply {
                cornerRadius = dp(24).toFloat()
            }
        }
        val white = C.TEXT
        val soft = C.SOFT
        greeting.setTextColor(soft)
        clockTime.setTextColor(white); clockTime.textSize = 46f
        clockDay.setTextColor(soft)
        clockCard.addView(greeting)
        clockCard.addView(clockTime)
        clockCard.addView(clockDay)
        // אזור ההתרעה (לפי המיקום) - בכרטיס השעון, מתחת ל"מוגן" (נכנס למקום אחרי שהמגן נוסף)
        clockArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        // כרטיס חיווים מעל השעון: רשת / מיקום / מקורות - שלוש עמודות שוות
        val status = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            // האייקונים הוגדלו - הריפוד קטן באותה מידה, כדי שגובה השורה יישאר כמו קודם
            val padV = ((7f - 22f * (1.3f - 1.15f) / 2f) * resources.displayMetrics.density).toInt()
            setPadding(dp(6), padV, dp(6), padV)
            background = graphite(28)
        }
        netInd = indicator(R.drawable.ic_globe) { showNetworkInfo() }   // לחיצה: עמוד הרשת (חיבור, שרת, שרשרת הגיבוי)
        protInd = indicator(R.drawable.ic_shield_ind) { toggle() }   // לחיצה: הפעלה / כיבוי (נראה כמו שאר החיוויים)
        locInd = indicator(R.drawable.ic_location) { onLocationIndicator() }   // לחיצה: רענון מיקום
        srvInd = indicator(R.drawable.ic_antenna) { showSourcesInfo() }   // לחיצה: רשימת המקורות
        sndInd = indicator(R.drawable.ic_set_sound) { showSoundSettings() }   // לחיצה: הגדרות צלילים ורטט
        // גרסה: 7 לחיצות מהירות - פותח את אפשרות הבטא (מוסתרת משאר המשתמשים)
        var taps = 0; var lastTap = 0L
        // לחיצה: חלון בדיקת העדכונים - בדיוק כמו "בדיקת עדכונים" בהגדרות.
        // נפתח רגע אחרי הלחיצה האחרונה, כדי שאפשר יהיה ללחוץ 7 פעמים ברצף (בטא) בלי שהחלון יסתיר את הכפתור
        val openUpdates = Runnable { if (taps < 7) showUpdateCheck() }
        verInd = indicator(R.drawable.ic_info) {
            val now = System.currentTimeMillis()
            val inSeries = now - lastTap < 1500
            taps = if (inSeries) taps + 1 else 1
            lastTap = now
            ui.removeCallbacks(openUpdates)
            ui.postDelayed(openUpdates, 600)
            if (taps >= 7 && !Prefs.betaUnlocked(this@MainActivity) && Prefs.betaAllowed(this@MainActivity)) {
                Prefs.setBetaUnlocked(this@MainActivity, true)
                android.widget.Toast.makeText(this@MainActivity, "🧪 גרסאות בטא נפתחו (⚙ ← עדכונים)",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            // מכשיר שלא ברשימת הבטא: 7 לחיצות מציגות את מזהה המכשיר (כדי להוסיף אותו לרשימה)
            if (taps == 7 && !Prefs.betaAllowed(this@MainActivity))
                android.widget.Toast.makeText(this@MainActivity, "מזהה מכשיר: ${Prefs.deviceCode(this@MainActivity)}",
                    android.widget.Toast.LENGTH_LONG).show()
        }
        updateVerInd()
        listOf(protInd, sndInd, netInd, locInd, srvInd, verInd).forEachIndexed { i, v ->
            if (i > 0) status.addView(View(this).apply { setBackgroundColor(C.LINE2) },
                LinearLayout.LayoutParams(dp(1), dp(26)))
            status.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(GAP) })
        statusRow = status
        applyStatusRow()
        weatherLine = text("", 19f, C.TEXT).apply {
            gravity = Gravity.CENTER
        }
        weatherLine.setTextColor(soft)
        clockCard.addView(weatherLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })


        // פיד ארצי - פס רץ עם כל ההתראות בארץ (בלי צליל)
        feedLine = text("", 14f, C.MUTED).apply {
            // התראה אחת בכל פעם, מתחלפות בגלילה כלפי מעלה
            // גובה קבוע של 3 שורות (שעה / סוג / אזורים) - הכרטיס לא קופץ בין התראה קצרה לארוכה
            minLines = 3; maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }
        // הכרטיס קבוע: כותרת "התרעות אחרונות", פס מפריד, ומתחת - רק המלל נגלל (נחתך מתחת לפס)
        val feedScroll = FrameLayout(this).apply {
            clipChildren = true; clipToPadding = true
            addView(feedLine, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        feedCard = FrameLayout(this).apply {
            background = graphite(20)   // כמו שאר הכרטיסים
            clipToOutline = true; clipChildren = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("התרעות אחרונות", 15f, C.TEXT).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(12), dp(12), dp(10))
                }, LinearLayout.LayoutParams(-1, -2))
                addView(View(this@MainActivity).apply { setBackgroundColor(C.LINE2) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(14); marginEnd = dp(14) })
                addView(feedScroll, LinearLayout.LayoutParams(-1, -2))
            }, FrameLayout.LayoutParams(-1, -2))
        }

        // כרטיס השעון הוא גם מצב ההגנה: ירוק עם פס אור שעובר לאט כשמוגן, גרפיט כשלא.
        // הפעלה/כיבוי - מהחיווי "מוגן" בשורת החיוויים
        // אנימציית מוגן: זוהר מאחורי המגן + גל רדאר שיוצא ממנו
        glow = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(20).toFloat()
                colors = intArrayOf(Color.parseColor("#AA69F0AE"), Color.TRANSPARENT)
            }
            alpha = 0f
        }
        fun ring() = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), Color.parseColor("#69F0AE")) }
            alpha = 0f
        }
        ring1 = ring()
        clockCard.background = null
        // בראש כרטיס השעון: מגן + "מוגן"/"לא מוגן" + עדכון אחרון (מה שהיה בכרטיס מוגן)
        // בראש הכרטיס: מגן בתוך עיגול עם הילה, ומתחתיו "מוגן" / "לא מוגן"
        shieldIcon = android.widget.ImageView(this)
        circleLabel = text("מוגן", 22f, C.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        haloOuter = FrameLayout(this)
        haloInner = FrameLayout(this).apply {
            clipToOutline = true   // הפס נחתך לצורת העיגול
            addView(glow, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            addView(shieldIcon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        haloOuter.addView(ring1, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        haloOuter.addView(haloInner, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        // סדר: ברכה למעלה, מתחתיה המגן ו"מוגן", ואז השעה
        (greeting.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(6)
        clockCard.addView(haloOuter, 1, LinearLayout.LayoutParams(dp(56), dp(56)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        clockCard.addView(circleLabel, 2, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        clockCard.addView(clockArea, 3, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(10) })
        // רווח שווה: מ"מוגן" עד ראש הספרות של השעה = מהברכה עד העיגול של המגן.
        // נמדד אחרי הפריסה (הספרות הגדולות מגיעות עם רווח פנימי משלהן)
        clockCard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> evenClockGaps() }
        circle = FrameLayout(this).apply {
            clipToOutline = true
            addView(clockCard, FrameLayout.LayoutParams(-1, -2))
        }
        // מצב שקט - מוצג רק כשהוא פעיל עכשיו
        quietLine = text("", 12f, C.ORANGE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { showQuietHours() }
        }
        col.addView(quietLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        // כרטיס התרעה פעילה - מעל השעון (מוסתר כשאין התרעה)
        alertCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { openActiveAlert() }
        }
        // ✕ בפינה: מסתיר את הכרטיס עד ההתרעה הבאה
        alertWrap = FrameLayout(this).apply {
            visibility = View.GONE
            addView(alertCard, FrameLayout.LayoutParams(-1, -2))
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_close)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#40000000")) }
                setOnClickListener {
                    if (previewAlert != null) { endCardPreview(); return@setOnClickListener }   // ✕ בתצוגה מקדימה - חזרה להגדרות
                    Prefs.activeAlert(this@MainActivity)?.let { Prefs.setAlertDismissed(this@MainActivity, it.optLong("firedAt")) }
                    updateAlertCard()
                }
            }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply { setMargins(dp(12), dp(12), dp(12), 0) })
        }
        col.addView(alertWrap, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(GAP) })
        // כרטיס השעה ומזג האוויר (וגם מצב ההגנה)
        col.addView(circle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(GAP) })

        // האזורים שלי
        // אזורי התרעה: אזור לפי מיקום + אזורים נוספים
        val areasCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = graphite(20)
            clipToOutline = true
        }
        chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        areasCard.addView(chips, LinearLayout.LayoutParams(-1, -2, 1f))
        shelterLine = text("", 13f, C.SOFT)
        areasCard.addView(shelterLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        // המסך לא נגלל: אזורי ההתרעה תופסים את מה שנשאר (לכל היותר), והרשימה שבתוכם נגללת
        // כרטיס האזורים לא מוצג במסך הראשי (אזורים נוספים עברו להגדרות) - המקום נשאר ריק כדי שהפיד יישב למטה
        areasSlot = FrameLayout(this)
        col.addView(areasSlot, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(GAP) })

        historyBox = LinearLayout(this)   // לא מוצג - נשאר לשימוש פנימי
        // הכפתור עבר לתפריט הצידי

        // הפיד הארצי - בתחתית המסך
        col.addView(feedCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(GAP) })

        val scroll = FrameLayout(this).apply {
            // נגלל כשאין מקום (למשל כשכרטיס ההתרעה מוצג מעל השעון)
            // המסך הראשי לא נגלל - רק רשימת האזורים הנוספים. נגלל רק כשכרטיס ההתרעה מוצג מעל השעון
            mainScroll = object : ScrollView(this@MainActivity) {
                override fun onInterceptTouchEvent(ev: android.view.MotionEvent) = pageScrolls && super.onInterceptTouchEvent(ev)
                @android.annotation.SuppressLint("ClickableViewAccessibility")
                override fun onTouchEvent(ev: android.view.MotionEvent) = pageScrolls && super.onTouchEvent(ev)
            }.apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(col, ViewGroup.LayoutParams(-1, -2))
            }
            addView(mainScroll, FrameLayout.LayoutParams(-1, -1))
            // כפתור צף: התראות בדיקה לכל סוגי ההתרעה
            testFab = FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#D50000")) }
                elevation = dp(6).toFloat()
                addView(android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_list_siren)
                    imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                setOnClickListener { chooseTest() }
            }
            addView(testFab, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.LEFT).apply {
                setMargins(dp(20), 0, dp(20), dp(20)) })
            applyTestFab()
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(scroll)
        // מסך שבת עם שעון: מופיע מעל מסך הנעילה; בכניסת השבת (מהתזמון) - גם מדליק את המסך
        if (android.os.Build.VERSION.SDK_INT >= 27 && Shabbat.active(this) && Prefs.shabbatClock(this)) {
            setShowWhenLocked(true)
            if (intent?.getBooleanExtra("shabbatClock", false) == true) setTurnScreenOn(true)
        }
        shabbatScreen = ShabbatScreen(this)
        // פתיחה מחדש (המסך נסגר ברקע): לא מציירים את המסך הריק - הטלפון ממשיך להציג את התמונה האחרונה
        // עד שנתוני היישובים נטענו והמסך מולא, ואז מוצג ישר המסך המלא (לכל היותר 1.5 שניות)
        var ready = false
        scroll.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (ready) { scroll.viewTreeObserver.removeOnPreDrawListener(this); return true }
                if (AreaData.loaded()) { ready = true; slowTick.run() }
                return false
            }
        })
        ui.postDelayed({ if (!ready) { ready = true; slowTick.run(); scroll.invalidate() } }, 1500)
        // התקנה חדשה: קודם ההבהרה, ואחרי "הבנתי" ההרשאות. לא בכל סיבוב / החלפת ערכת צבעים
        if (savedInstanceState == null && !showDisclaimerIfNew { askPermissions() }) askPermissions()
        showWhatsNewIfUpdated()
        // אחרי החלפת ערכת צבעים (האפליקציה נטענת מחדש) - חוזרים להגדרות
        if (reopenDisplay) { reopenDisplay = false; ui.post { showSettings() } }
        // טעינת נתוני האזורים ברקע, כדי שהמסך לא ייתקע בפעם הראשונה
        AreaData.preload(this)   // נתוני היישובים ברקע - המסך לא נתקע בפתיחה
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /** ערכת צבעים "לפי זמן": אם הגיע זמן ההחלפה - טוענים מחדש; וקובעים את ההחלפה הבאה בזמן שהמסך פתוח */
    private val themeSwitch = Runnable { scheduleThemeSwitch() }
    private fun scheduleThemeSwitch() {
        ui.removeCallbacks(themeSwitch)
        if (Prefs.themeMode(this) != 3) return
        val (dark, next) = Prefs.darkByTime(this)
        if (dark == C.LIGHT) { recreate(); return }   // הצבעים הנוכחיים לא תואמים לזמן
        ui.postDelayed(themeSwitch, (next - System.currentTimeMillis()).coerceIn(1000L, 6 * 3600_000L) + 1000L)
    }

    override fun onResume() {
        if (::statusRow.isInitialized) { applyStatusRow(); applyTestFab() }
        refreshAccuracy()   // "לפי זמן": מוצגים בכל פתיחה
        scheduleThemeSwitch()   // ערכת צבעים "לפי זמן": מחליפים כשמגיע הזמן
        try { Shabbat.refresh(this) } catch (_: Exception) { }   // זמני שבת וחג מהשרת
        Prefs.refreshBeta(this)   // מכשירי הבטא - הרשימה בשרת
        DataFiles.sync(this)      // הגדרות, יישובים והקלטות מהשרת (לכל היותר פעם בשעה)
        // קיצור דרך מאייקון האפליקציה (לחיצה ארוכה)
        intent?.getStringExtra("shortcut")?.let { sc ->
            intent.removeExtra("shortcut")
            ui.post {
                when (sc) {
                    "recent" -> showRecent()
                    "map" -> startActivity(Intent(this, MapActivity::class.java))
                    "clock" -> startActivity(Intent(this, ClockActivity::class.java))
                    "test" -> chooseTest()
                }
            }
        }
        // "הפעלה" מההתראה "לא מוגן"
        if (intent?.getBooleanExtra("enable", false) == true) {
            intent.removeExtra("enable")
            if (!Prefs.enabled(this)) ui.post { toggle() }
        }
        super.onResume()
        restoreScroll?.let { r -> restoreScroll = null; ui.post(r); ui.postDelayed(r, 250) }
        pulseAnims.forEach { it.resume() }
        verBlink?.resume(); srvBlink?.resume()
        // רשת ביטחון: אם ההאזנה אמורה לפעול - לוודא שהשירות באמת רץ (בטוח לקרוא גם אם כבר רץ)
        if (Prefs.enabled(this)) AlertService.start(this)
        ui.post(tick)
        Thread { try { TimeSync.sync(this) } catch (_: Exception) { } }.start()   // שעון אחיד עם השרת (לכל היותר פעם בשעה)
        askLockScreen()
        if (pendingLock) {
            pendingLock = false
            ui.postDelayed({
                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                if (dpm.isAdminActive(android.content.ComponentName(this, LockAdmin::class.java)) && Shabbat.active(this) && !Prefs.shabbatClock(this))
                    try { dpm.lockNow() } catch (_: Exception) { }
            }, 600)
        }
        refreshNearby()
        // בכל חזרה לאפליקציה (כולל לחיצה על התראת "גרסה חדשה"), לכל היותר פעם ב-10 דקות
        if (System.currentTimeMillis() - updateCheckedAt > 10 * 60 * 1000) {
            updateCheckedAt = System.currentTimeMillis()
            Updater.check(this, silent = true)
        }
    }

    override fun onPause() {
        ui.removeCallbacks(tick); ui.removeCallbacks(slowTick); ui.removeCallbacks(themeSwitch)
        pulseAnims.forEach { it.pause() }   // האנימציה לא רצה כשהאפליקציה ברקע
        verBlink?.pause(); srvBlink?.pause()
        ui.removeCallbacks(srvAlt); srvColors = null   // ההחלפה מתחדשת בחזרה לאפליקציה
        super.onPause()
    }

    /** המסך נסגר / נבנה מחדש (סיבוב, החלפת ערכת צבעים) - סוגרים עמודים וטיימרים שלא יישארו תלויים */
    override fun onDestroy() {
        // בלי להפעיל את פעולות הסגירה של העמודים (למשל טעינה מחדש אחרי החלפת ערכת צבעים)
        pages.toList().forEach { try { it.setOnDismissListener(null); it.dismiss() } catch (_: Exception) { } }
        pages.clear()
        ui.removeCallbacksAndMessages(null)
        pulseAnims.forEach { it.cancel() }
        verBlink?.cancel(); verBlink = null   // הבהובי שורת החיוויים - שלא יחזיקו את המסך הישן
        srvBlink?.cancel(); srvBlink = null
        recentList = null; recentSegView = null
        shabbatScreen?.destroy(); shabbatScreen = null
        super.onDestroy()
    }

    /** התראות אחרונות בסגנון One UI: מחולקות לימים, כל יום בכרטיס */
    internal var recentList: LinearLayout? = null
    internal var recentSegView: LinearLayout? = null
    /** המקום ברשימת ההתרעות לפני שנפתחה המפה - חוזרים אליו כשחוזרים */
    internal var restoreScroll: (() -> Unit)? = null

    /** סינון ברשימת ההתרעות: הכל / האזורים שלי (נזכר עד שסוגרים את האפליקציה) */
    internal var recentMine = false

    /**
     * הרשימה נבנית בחלקים: קודם ההתרעות הראשונות, וכשגוללים למטה - עוד. כך גם שנה של התרעות
     * (אלפי שורות) נפתחת מיד ולא נתקעת
     */
    internal var recentItems: List<Prefs.Entry> = emptyList()
    internal var recentShown = 0
    internal var recentDay: String? = null
    internal var recentCard: LinearLayout? = null
    internal val recentTimeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    internal val RECENT_CHUNK = 120

    /** נטען פעם אחת לכל פתיחה של האפליקציה */
    internal var archiveCache: List<Prefs.Entry>? = null

    /** עיגול בחירה כמו בבחירת קבצים בסמסונג: טבעת, ובנבחר עיגול כחול מלא עם וי */

    /** on != null - השורה מציגה מתג (לחיצה על השורה מדליקה/מכבה) */
    internal class Row(val icon: String, val title: String,
                      val on: (() -> Boolean)? = null, val toggle: (() -> Unit)? = null, val action: () -> Unit)

    /** קטגוריה פתוחה במגירת ההגדרות (נשמרת בין פתיחות) */
    internal var openCat = -1

    /** מסך מצב שבת: בחירת מצב, עיר וזמני כניסה ויציאה, והסבר מה המצב עושה */
    internal var shabbatLocAsked = 0L
    /** עמוד בדיקת התראה: שורה לכל סוג, לחיצה שולחת התראת בדיקה */
    /** הכפתור הצף מוצג / מוסתר לפי ההגדרה; כשמוצג - מקום בתחתית המסך כדי שלא יסתיר כרטיסים */
    internal val hideTestFab = Runnable { if (Prefs.testFabMode(this) == Prefs.STATUS_TIMED) testFab.visibility = View.GONE }
    /** כל העמודים הפתוחים (הגדרות, תת-עמודים, התרעות) - כדי שהתפריט התחתון יסגור את כולם במעבר */
    internal val pages = mutableListOf<android.app.Dialog>()

    companion object {
        internal var reopenDisplay = false
        /** סטטי - סיבוב המסך לא מפעיל בדיקת עדכונים חדשה */
        internal var updateCheckedAt = 0L
        /** סטטי - בקשת "מסך נעילה" לא חוזרת אחרי סיבוב */
        internal var lockAsked = false
    }

    /** ערכת הצבעים נשמרת מיד, ומוחלת (טעינה מחדש) רק ביציאה מעמוד התצוגה - בלי קפיצות בזמן הבחירה */
    internal var themeAtOpen = -1
    internal class Perm(val icon: String, val title: String, val note: String, val ok: Boolean, val open: () -> Unit)

    /** מצייר אייקון בגודלו, ממורכז בתוך גובה קבוע (בולט מעט למעלה ולמטה בלי להזיז את הטקסט) */
    internal class SameHeight(val d: android.graphics.drawable.Drawable, val dw: Int, val dh: Int, val h: Int) :
        android.graphics.drawable.Drawable() {
        override fun draw(c: android.graphics.Canvas) {
            val top = bounds.top + (h - dh) / 2
            d.setBounds(bounds.left, top, bounds.left + dw, top + dh); d.draw(c)
        }
        override fun setAlpha(a: Int) { d.alpha = a }
        override fun setColorFilter(f: android.graphics.ColorFilter?) { d.colorFilter = f }
        override fun setTintList(t: android.content.res.ColorStateList?) { d.setTintList(t) }
        override fun setTintMode(m: android.graphics.PorterDuff.Mode?) { if (m != null) d.setTintMode(m) }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = dw
        override fun getIntrinsicHeight() = h
    }

    /** הפיד הארצי: ההתראות בחלון הזמן מההגדרות, אחת בכל פעם, מתחלפות בגלילה כלפי מעלה */
    internal var feedItems: List<FeedText.Item> = emptyList()
    internal var feedIdx = 0
    internal var feedTick = 0
    internal var feedWin = -1
    internal var feedAlwaysShown: Boolean? = null

    /** חיווי הגרסה: מעודכן - אייקון ירוק; יש עדכון חדש - כתום ומהבהב (רק האייקון, לא מספר הגרסה) */
    internal var verBlink: android.animation.ValueAnimator? = null
    /** "מתחבר…" מהבהב עד שמתחבר */
    internal var srvBlink: android.animation.ObjectAnimator? = null
    /** חיווי החיבור: הכיתוב והצבעים הנוכחיים (צבע שני = מתחלפים כל חצי שנייה) */
    internal var srvLabel = ""
    internal var srvColors: Pair<Int, Int?>? = null
    internal var srvAltOn = false
    internal val srvAlt: Runnable = object : Runnable {
        override fun run() {
            val (c1, c2) = srvColors ?: return
            if (c2 == null) return
            srvAltOn = !srvAltOn
            setInd(srvInd, R.drawable.ic_antenna, srvLabel, if (srvAltOn) c2 else c1)
            ui.postDelayed(this, 500)
        }
    }
    /** תוצאת "בדוק את השרתים" האחרונה: מתי, ואילו מקורות תקינים בשרת (null = השרת לא ענה) */
    internal var srvCheckAt = 0L
    internal var srvCheckUp: List<String>? = null

    internal var testSpeaker: Speaker? = null

    // ---- שורת החיוויים: קבוע / לא מוצגת / כמה שניות אחרי פתיחה או נגיעה ----
    internal val hideStatus = Runnable { if (Prefs.statusMode(this) == Prefs.STATUS_TIMED) statusRow.visibility = View.GONE }
    /** "לפי זמן": כל נגיעה במסך הראשי מציגה את השורה שוב לאותו זמן */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN && ::statusRow.isInitialized) {
            if (Prefs.statusMode(this) == Prefs.STATUS_TIMED) applyStatusRow()
            if (Prefs.testFabMode(this) == Prefs.STATUS_TIMED) applyTestFab()
        }
        return super.dispatchTouchEvent(ev)
    }
}

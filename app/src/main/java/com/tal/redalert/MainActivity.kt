package com.tal.redalert

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
    private object C {
        var BG = 0; var CARD = 0; var CHIP = 0; var TEXT = 0; var MUTED = 0
        var GREEN = 0; var GREEN_BG = 0; var OFF = 0; var OFF_BG = 0
        var BLUE = 0; var RED = 0; var ORANGE = 0; var END = 0
        var CARD2 = 0; var PILL = 0; var DIV = 0; var SEPV = 0; var SOFT = 0; var MUTED2 = 0; var LINE = 0; var LINE2 = 0
        var LIGHT = false

        fun apply(dark: Boolean) {
            fun c(d: String, l: String) = Color.parseColor(if (dark) d else l)
            BG = c("#000000", "#F2F2F7")
            CARD = c("#1C1C1E", "#FFFFFF")
            CHIP = c("#2C2C2E", "#E5E5EA")
            TEXT = c("#F2F2F2", "#1C1C1E")
            MUTED = c("#8E8E93", "#6E6E73")
            GREEN = c("#30D158", "#248A3D")
            GREEN_BG = c("#0F2A17", "#E3F5E8")
            OFF = c("#636366", "#8E8E93")
            OFF_BG = c("#1C1C1E", "#FFFFFF")
            BLUE = c("#0A84FF", "#007AFF")
            RED = c("#FF3B30", "#E0352B")
            ORANGE = c("#FF9F0A", "#E08600")
            END = c("#2E7D32", "#2E7D32")
            CARD2 = c("#1B1B1D", "#FFFFFF")      // כרטיסים (One UI)
            PILL = c("#3A3A3E", "#E3E3E8")       // גלולות / כפתורים צפים
            DIV = c("#2A2A2C", "#E0E0E5")        // קו בין שורות
            SEPV = c("#3A3A3C", "#D1D1D6")       // קו לפני מתג
            SOFT = c("#E6FFFFFF", "#CC1C1C1E")   // טקסט משני רך
            MUTED2 = c("#AAAAAA", "#6E6E73")     // טקסט אפור
            LINE = c("#14FFFFFF", "#14000000")
            LINE2 = c("#22FFFFFF", "#22000000")
            LIGHT = !dark
        }
    }

    private lateinit var circle: FrameLayout
    private lateinit var shieldIcon: android.widget.ImageView
    private lateinit var circleLabel: TextView
    private lateinit var haloOuter: FrameLayout
    private lateinit var haloInner: FrameLayout
    private lateinit var glow: View                // זוהר מאחורי המגן
    private lateinit var ring1: View               // גל רדאר שיוצא מהמגן
    private val pulseAnims = mutableListOf<android.animation.Animator>()
    private lateinit var chips: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var clockTime: TextView
    private lateinit var greeting: TextView
    private lateinit var clockDay: TextView
    private lateinit var weatherLine: TextView
    private lateinit var shelterLine: TextView
    private lateinit var quietLine: TextView
    private lateinit var feedLine: TextView
    private var feedText = ""
    private lateinit var netInd: TextView
    private lateinit var protInd: TextView   // מוגן / לא מוגן
    private lateinit var locInd: TextView
    private lateinit var srvInd: TextView
    private var weatherAt = 0L
    private var weatherAccuracy = -1
    private var lastHistoryKey = ""

    private fun granted(p: String) =
        checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun hasLocationPerm() =
        granted(android.Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasPrecise() = granted(android.Manifest.permission.ACCESS_FINE_LOCATION)

    /** מתעדכן כל 15 דקות, או מיד כשמכריחים (לחיצה על השורה) */
    private fun updateWeather(force: Boolean = false) {
        if (!Prefs.showWeather(this)) { weatherLine.visibility = View.GONE; return }
        weatherLine.visibility = View.VISIBLE
        if (!hasLocationPerm()) {
            weatherLine.text = "📍 הצג מזג אוויר לפי מיקום"
            weatherLine.setTextColor(C.BLUE)
            return
        }
        if (!force && System.currentTimeMillis() - weatherAt < Prefs.weatherMinutes(this) * 60 * 1000L) return
        weatherAt = System.currentTimeMillis()
        if (force || weatherLine.text.isNullOrBlank()) {
            weatherLine.text = "מאתר מיקום מדויק…"
            weatherLine.setTextColor(C.MUTED)
        }
        Weather.freshLocation(this) { loc ->
            if (loc == null) {
                weatherLine.text = "📍 לא נמצא מיקום – לחץ לנסות שוב"
                weatherLine.setTextColor(C.MUTED)
                weatherAt = 0L
                return@freshLocation
            }
            Thread {
                val w = try { Weather.fetch(this, loc) } catch (_: Exception) { null }
                runOnUiThread {
                    if (w == null) { weatherAt = 0L; return@runOnUiThread }
                    val (icon, _) = Weather.describe(w.code, w.isDay)
                    val hint = if (hasPrecise()) "" else " (לחץ למיקום מדויק)"
                    weatherLine.text = listOf("$icon ${w.temp}°", w.place)
                        .filter { it.isNotBlank() }.joinToString(" · ") + hint
                    weatherLine.setTextColor(C.SOFT)
                    weatherAccuracy = w.accuracy
                }
            }.start()
        }
    }

    private fun onWeatherClick() {
        if (hasPrecise()) updateWeather(force = true)
        else requestPermissions(arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION), 2)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) updateWeather(force = true)
        if (requestCode == 3 && hasPrecise()) toggleNearMe()
    }

    private val ui = Handler(Looper.getMainLooper())
    /**
     * השעון: קל ומהיר, מתוזמן קודם כל - כך שתקלה או עיכוב בשאר המסך לא עוצרים את השניות וההבהוב.
     * רץ כל חצי שנייה (שנייה מלאה + אמצע השנייה להבהוב הנקודתיים).
     */
    private val tick = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            val ms = System.currentTimeMillis() % 1000
            ui.postDelayed(this, if (ms < 500) 500 - ms else 1000 - ms)
            try { updateClock() } catch (_: Exception) { }
            // שאר המסך - פעם בשנייה (בתחילת השנייה), ובנפרד מהשעון
            if (ms < 500) ui.post(slowTick)
        }
    }
    private val slowTick = Runnable {
        try {
            updateIndicators()
            updateWeather()
            updateAlertCard()
            // שאר המסך מתעדכן רק כשמשהו השתנה
            val key = "${Prefs.enabled(this@MainActivity)}|${Prefs.history(this@MainActivity).firstOrNull()?.ts}|" +
                Prefs.nearbyAreas(this@MainActivity).joinToString()   // גם כשהאזורים הקרובים משתנים
            if (key != lastHistoryKey) { lastHistoryKey = key; refresh() }
        } catch (_: Exception) { }
    }

    private lateinit var alertCard: LinearLayout
    private lateinit var alertWrap: FrameLayout
    private lateinit var areasSlot: FrameLayout

    /** כשכרטיס ההתרעה מוצג - האזורים בגובה מלא והמסך נגלל; בלעדיו - האזורים ממלאים את מה שנשאר */
    private lateinit var clockArea: LinearLayout
    private var mainScroll: ScrollView? = null
    /** האם המסך הראשי עצמו נגלל (רק כשכרטיס ההתרעה מוצג) */
    private var pageScrolls = false

    private fun setScrollMode(on: Boolean) {
        pageScrolls = on
        if (!on) mainScroll?.scrollTo(0, 0)
        val lp = areasSlot.layoutParams as LinearLayout.LayoutParams
        val h = if (on) -2 else 0; val w = if (on) 0f else 1f
        if (lp.height != h || lp.weight != w) { lp.height = h; lp.weight = w; areasSlot.layoutParams = lp }
    }
    private var alertCardKey = ""
    private var alertCardTimer: TextView? = null
    private var alertCardLabel: TextView? = null

    /** כרטיס ההתרעה מעל השעון: נבנה כשההתרעה משתנה, והטיימר מתעדכן כל שנייה */
    private fun updateAlertCard() {
        val j = Prefs.activeAlert(this)
        val level = j?.optInt("level") ?: 0
        val firedAt = j?.optLong("firedAt") ?: 0L
        if (j == null || AlertUi.expired(level, firedAt) || Prefs.alertDismissed(this) == firedAt) {
            if (alertWrap.visibility != View.GONE) alertWrap.visibility = View.GONE
            setScrollMode(false)
            alertCardKey = ""; return
        }
        val key = j.toString()
        if (key != alertCardKey) {
            alertCardKey = key
            alertCard.removeAllViews()
            alertCard.background = GradientDrawable().apply {
                setColor(Color.parseColor(AlertUi.color(level))); cornerRadius = dp(28).toFloat() }
            val (t1, t2) = AlertUi.head(j.optString("title"), level)
            // אייקון מעל הכותרת לפי סוג ההתרעה
            val icon = AlertUi.icon(j.optString("title"), level)
            alertCard.addView(android.widget.ImageView(this).apply { setImageResource(icon) },
                LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            alertCard.addView(text(t1, 19f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
            alertCard.addView(text(t2, 14f, Color.parseColor("#E6FFFFFF")).apply { gravity = Gravity.CENTER })
            val body = j.optString("body")
            if (body.isNotEmpty()) alertCard.addView(text(body, 12f, Color.WHITE).apply {
                gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = GradientDrawable().apply { setColor(Color.parseColor("#33FFFFFF")); cornerRadius = dp(14).toFloat() }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6); bottomMargin = dp(2) })
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
        AlertUi.timer(level, j.optInt("shelter", -1), firedAt)?.let { (lbl, t) ->
            alertCardLabel?.text = lbl
            alertCardLabel?.visibility = if (lbl.isEmpty()) View.GONE else View.VISIBLE
            alertCardTimer?.textSize = if (lbl.isEmpty()) 20f else 38f
            alertCardTimer?.text = t
        }
    }

    /** לחיצה על הכרטיס - מסך ההתרעה המלא */
    private fun openActiveAlert() {
        val j = Prefs.activeAlert(this) ?: return
        startActivity(Intent(this, AlertActivity::class.java)
            .putExtra("title", j.optString("title")).putExtra("body", j.optString("body"))
            .putExtra("level", j.optInt("level")).putExtra("shelter", j.optInt("shelter", -1))
            .putExtra("firedAt", j.optLong("firedAt")).putExtra("source", j.optString("source")))
    }

    private fun updateClock() {
        val now = System.currentTimeMillis()
        clockTime.text = TimeFormat.clock(this, now)
        clockDay.text = TimeFormat.dayLine(this, now)
        val g = Prefs.showGreeting(this)
        greeting.visibility = if (g) View.VISIBLE else View.GONE
        if (g) greeting.text = TimeFormat.greeting(now)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    /** רווח אחיד בין כרטיס לכרטיס במסך הראשי */
    private val GAP = 12
    private fun dlg() = Prefs.dialogTheme(this)

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

        // כותרת + גלגל שיניים
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("צבע אדום", 24f, C.TEXT, bold = true).apply { setPadding(dp(4), 0, dp(4), 0) })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // כפתור שעון בעיגול צף (הבדיקה עברה להגדרות התרעות)
        header.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_nav_clock)
            imageTintList = android.content.res.ColorStateList.valueOf(C.TEXT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.PILL) }
            setOnClickListener { startActivity(Intent(this@MainActivity, ClockActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
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
        clockTime.setTextColor(white); clockTime.textSize = 58f
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
        // בלי לחיצה על החיווים (רק 7 הלחיצות הנסתרות על הגרסה נשארו)
        // לחיצה: חלון האינטרנט של הטלפון (Wi-Fi ונתונים ניידים) בלי לצאת מהאפליקציה
        netInd = indicator(R.drawable.ic_globe) {
            try {
                startActivity(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                                     else Settings.ACTION_WIRELESS_SETTINGS))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Exception) { }
            }
        }
        protInd = indicator(R.drawable.ic_shield_ind) { toggle() }   // לחיצה: הפעלה / כיבוי (נראה כמו שאר החיוויים)
        locInd = indicator(R.drawable.ic_location) { onLocationIndicator() }   // לחיצה: רענון מיקום
        srvInd = indicator(R.drawable.ic_antenna) { showSourcesInfo() }   // לחיצה: רשימת המקורות
        // גרסה: 7 לחיצות מהירות - פותח את אפשרות הבטא (מוסתרת משאר המשתמשים)
        var taps = 0; var lastTap = 0L
        // לחיצה: בדיקת עדכונים מיד - בדיוק כמו "בדיקת עדכונים" בהגדרות.
        // לחיצות נוספות ברצף (ל-7 לחיצות של הבטא) לא בודקות שוב
        val verInd = indicator(R.drawable.ic_info) {
            val now = System.currentTimeMillis()
            val inSeries = now - lastTap < 1500
            taps = if (inSeries) taps + 1 else 1
            lastTap = now
            if (!inSeries) Updater.check(this@MainActivity, silent = false)
            if (taps >= 7 && !Prefs.betaUnlocked(this@MainActivity) && Prefs.betaAllowed(this@MainActivity)) {
                Prefs.setBetaUnlocked(this@MainActivity, true)
                android.widget.Toast.makeText(this@MainActivity, "🧪 גרסאות בטא נפתחו (⚙ ← בדיקת עדכונים)",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        setInd(verInd, R.drawable.ic_info, "v${Updater.currentVersion(this)}" +
            if (Updater.isBetaBuild(this) && !Updater.currentVersion(this).endsWith("b")) "b" else "", C.MUTED)
        listOf(protInd, netInd, locInd, srvInd, verInd).forEachIndexed { i, v ->
            if (i > 0) status.addView(View(this).apply { setBackgroundColor(C.LINE2) },
                LinearLayout.LayoutParams(dp(1), dp(26)))
            status.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(GAP) })
        weatherLine = text("", 19f, C.TEXT).apply {
            gravity = Gravity.CENTER
        }
        weatherLine.setTextColor(soft)
        clockCard.addView(weatherLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })


        // פיד ארצי - פס רץ עם כל ההתראות בארץ (בלי צליל)
        feedLine = text("", 14f, C.MUTED).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), dp(20))   // פיד ארצי - כרטיס גבוה יותר
            background = graphite(20)   // כמו שאר הכרטיסים
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
        col.addView(feedLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(GAP) })

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
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        val nav = bottomNav(0) { i -> openTab(i) }
        scroll.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
        // מקום לתפריט מתחת לפיד
        col.setPadding(col.paddingLeft, col.paddingTop, col.paddingRight, dp(GAP) + dp(86))   // גובה התפריט + המרווח שלו
        setContentView(scroll)
        askPermissions()
        showWhatsNewIfUpdated()
        // אחרי החלפת ערכת צבעים (האפליקציה נטענת מחדש) - חוזרים לעמוד התצוגה
        if (reopenDisplay) { reopenDisplay = false; ui.post { showSettings() } }
        // טעינת נתוני האזורים ברקע, כדי שהמסך לא ייתקע בפעם הראשונה
        Thread { try { AreaData.areas(this) } catch (_: Exception) { } }.start()
    }

    private var updateCheckedAt = 0L

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // לשונית שנבחרה בתפריט שבמסך השעון
        intent.getIntExtra("tab", -1).takeIf { it > 0 }?.let { t -> intent.removeExtra("tab"); ui.post { openTab(t) } }
    }

    override fun onResume() {
        // "הפעלה" מההתראה "לא מוגן"
        if (intent?.getBooleanExtra("enable", false) == true) {
            intent.removeExtra("enable")
            if (!Prefs.enabled(this)) ui.post { toggle() }
        }
        super.onResume()
        pulseAnims.forEach { it.resume() }
        // רשת ביטחון: אם ההאזנה אמורה לפעול - לוודא שהשירות באמת רץ (בטוח לקרוא גם אם כבר רץ)
        if (Prefs.enabled(this)) AlertService.start(this)
        ui.post(tick)
        askLockScreen()
        refreshNearby()
        // בכל חזרה לאפליקציה (כולל לחיצה על התראת "גרסה חדשה"), לכל היותר פעם ב-10 דקות
        if (System.currentTimeMillis() - updateCheckedAt > 10 * 60 * 1000) {
            updateCheckedAt = System.currentTimeMillis()
            Updater.check(this, silent = true)
        }
    }

    override fun onPause() {
        ui.removeCallbacks(tick); ui.removeCallbacks(slowTick)
        pulseAnims.forEach { it.pause() }   // האנימציה לא רצה כשהאפליקציה ברקע
        super.onPause()
    }

    private fun toggle() {
        val on = !Prefs.enabled(this)
        Prefs.setEnabled(this, on)
        if (on) AlertService.start(this, manual = true) else AlertService.stop(this)
        AlertWidget.updateAll(this)
        refresh()
    }

    private fun evenClockGaps() {
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
    private fun setSweep(on: Boolean) {
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

    private fun refresh() {
        val on = Prefs.enabled(this)
        val color = if (on) C.GREEN else C.OFF
        // כרטיס כהה, מסגרת ירוקה כשמוגן / אפורה כשלא
        circle.background = graphite(24).apply { setStroke(dp(2), Color.parseColor(if (on) "#4CAF50" else "#555555")) }
        shieldIcon.setImageResource(if (on) R.drawable.ic_shield_green else R.drawable.ic_shield_outline)
        haloOuter.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(if (on) "#0F69F0AE" else "#00000000")) }
        haloInner.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(if (on) "#2469F0AE" else "#10FFFFFF")) }
        circleLabel.text = if (on) "מוגן" else "לא מוגן"
        circleLabel.setTextColor(Color.parseColor(if (on) "#69F0AE" else "#AAAAAA"))
        setSweep(on)
        updateIndicators()

        renderChips()
        renderHistory()
    }

    /** אזורי התרעה: פס בולט עם המיקום שלי, ומתחתיו האזורים הנוספים */
    private fun renderChips() {
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

        // אזורים נוספים - עברו להגדרות התרעות (מתחת למרחק)
        shelterLine.visibility = View.GONE   // הזמן מוצג עכשיו בפס המיקום
    }

    /** במסך הראשי: שורת סיכום של ההתראה האחרונה */
    private fun renderHistory() {
        historyBox.removeAllViews()
        val e = Prefs.history(this).firstOrNull()
        val muted = C.MUTED2
        historyBox.addView(text(
            if (e == null) "אין התראות עדיין"
            else (if (e.level == AlertService.LEVEL_PRE) "התראה מקדימה" else e.title) +
                (if (e.ts > 0) " · " + dayLabel(e.ts) + " " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.ts)) else ""),
            12f, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
    }

    /**
     * מסך "התראות אחרונות": כל ההתראות שהיו בארץ (החודש האחרון, מהארכיון של פיקוד העורף),
     * בלי סינון. לחיצה על התראה - המפה על האזור שלה.
     */
    /** עמוד בסגנון One UI: כותרת גדולה, תוכן נגלל, והתפריט התחתון (navTab = הלשונית הנבחרת) */
    private fun onePage(title: String, navTab: Int, build: (LinearLayout) -> Unit) {
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val page = ScrollView(this).apply {
            setBackgroundColor(C.BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(body)
        }
        page.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        body.addView(text(title, 30f, C.TEXT, bold = true).apply { setPadding(dp(24), dp(64), dp(24), dp(12)) })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content)
        body.addView(View(this), LinearLayout.LayoutParams(-1, dp(90)))   // מקום לתפריט התחתון
        build(content)
        val frame = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        frame.addView(page, FrameLayout.LayoutParams(-1, -1))
        val nav = bottomNav(navTab) { i -> switchTab(d, i) }
        frame.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = navMargin() })
        frame.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            (nav.layoutParams as FrameLayout.LayoutParams).bottomMargin = insets.systemWindowInsetBottom + dp(10)
            nav.requestLayout()
            page.dispatchApplyWindowInsets(insets)
        }
        d.setContentView(frame)
        d.window?.setLayout(-1, -1)
        @Suppress("DEPRECATION")
        d.window?.statusBarColor = C.BG
        edgeToEdge(d)
        track(d)
        d.show()
    }

    /** התראות אחרונות בסגנון One UI: מחולקות לימים, כל יום בכרטיס */
    private fun showRecent() = onePage("התרעות אחרונות", 1) { c ->
        val cached = archiveCache
        if (cached != null) { fillRecent(c, cached); return@onePage }
        val loading = text("טוען את כל ההתראות…", 14f, C.MUTED2).apply { setPadding(dp(28), dp(12), dp(28), dp(12)) }
        c.addView(loading)
        Thread {
            val list = try { OrefArchive.fetch() } catch (_: Exception) { emptyList() }
                .ifEmpty { Prefs.feed(this) }   // אין חיבור לארכיון - מה שהאפליקציה שמרה
            runOnUiThread {
                archiveCache = list
                c.removeAllViews()
                fillRecent(c, list)
            }
        }.start()
    }

    private fun fillRecent(c: LinearLayout, items: List<Prefs.Entry>) {
        val muted = C.MUTED2
        if (items.isEmpty()) {
            c.addView(text("אין התראות עדיין", 14f, muted).apply { setPadding(dp(28), dp(12), dp(28), dp(12)) })
            return
        }
        items.groupBy { if (it.ts > 0) fullDayLabel(it.ts) else "" }.forEach { (day, list) ->
            if (day.isNotEmpty()) c.addView(text(day, 13f, muted).apply { setPadding(dp(28), dp(14), dp(28), dp(6)) })
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = graphite(28)
                clipToOutline = true
            }
            list.forEachIndexed { i, e ->
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(C.DIV) },
                    LinearLayout.LayoutParams(-1, dp(1)))
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    // לחיצה: המפה על האזורים של ההתראה
                    setOnClickListener {
                        startActivity(Intent(this@MainActivity, MapActivity::class.java)
                            .putExtra("focus", e.body).putExtra("focusTs", e.ts).putExtra("focusAll", true))
                    }
                }
                val col = when (e.level) {
                    AlertService.LEVEL_PRE -> "#F08C00"
                    AlertService.LEVEL_END -> "#2E7D32"
                    else -> "#E53935"
                }
                val icon = AlertUi.icon(e.title, e.level)
                row.addView(FrameLayout(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(col)) }
                    addView(android.widget.ImageView(this@MainActivity).apply { setImageResource(icon) },
                        FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(text(if (e.level == AlertService.LEVEL_PRE) "התראה מקדימה" else e.title, 15f, C.TEXT))
                texts.addView(text(e.body.replace("ברחבי הארץ", AreaData.ALL_COUNTRY), 12f, muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text(if (e.ts > 0) java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(java.util.Date(e.ts)) else e.time.take(5), 12f, muted),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                card.addView(row)
            }
            c.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), 0, dp(10), 0) })
        }
    }

    /** נטען פעם אחת לכל פתיחה של האפליקציה */
    private var archiveCache: List<Prefs.Entry>? = null

    /** "היום" / "אתמול" / תאריך */
    private fun dayLabel(ts: Long): String {
        val k = java.util.Calendar.getInstance()
        fun key(c: java.util.Calendar) = c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
        val today = key(k)
        val e = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        k.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return when (key(e)) {
            today -> "היום"
            key(k) -> "אתמול"
            else -> "%02d/%02d".format(e.get(java.util.Calendar.DAY_OF_MONTH), e.get(java.util.Calendar.MONTH) + 1)
        }
    }

    /** כותרת יום ב"התרעות אחרונות": כינוי (היום / אתמול / שלשום / לפני שבוע...) + תאריך מלא */
    private fun fullDayLabel(ts: Long): String {
        val date = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US).format(java.util.Date(ts))
        fun midnight(ms: Long) = java.util.Calendar.getInstance().apply {
            timeInMillis = ms
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        // הפרש בימים קלנדריים (עיגול - בגלל מעבר שעון קיץ/חורף)
        val days = Math.round((midnight(System.currentTimeMillis()) - midnight(ts)) / 86_400_000.0).toInt()
        val nick = when {
            days <= 0 -> "היום"
            days == 1 -> "אתמול"
            days == 2 -> "שלשום"
            days < 7 -> ""
            days < 14 -> "לפני שבוע"
            days < 21 -> "לפני שבועיים"
            days < 30 -> "לפני 3 שבועות"
            days < 60 -> "לפני חודש"
            days < 90 -> "לפני חודשיים"
            days < 120 -> "לפני 3 חודשים"
            else -> ""
        }
        return if (nick.isEmpty()) date else "$nick · $date"
    }

    /** עיגול בחירה כמו בבחירת קבצים בסמסונג: טבעת, ובנבחר עיגול כחול מלא עם וי */
    private inner class CheckDot(var on: Boolean) : View(this@MainActivity) {
        private val d = resources.displayMetrics.density
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.6f * d; color = Color.parseColor("#8E8E93") }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5B8AF5") }
        private val tick = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.4f * d; color = Color.BLACK   // וי שחור כמו בסמסונג
            strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
        override fun onDraw(c: android.graphics.Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - 1.5f * d
            if (!on) { c.drawCircle(cx, cy, r, ring); return }
            c.drawCircle(cx, cy, r, fill)
            val p = android.graphics.Path().apply {
                moveTo(cx - r * 0.42f, cy + r * 0.02f); lineTo(cx - r * 0.1f, cy + r * 0.34f); lineTo(cx + r * 0.44f, cy - r * 0.3f) }
            c.drawPath(p, tick)
        }
    }

    /** חלון אזורים נוספים: הוספת יישוב / הוספת אזור שלם */
    private fun showExtraAreas() = ouiPage("אזורים נוספים") { body, _ ->
        val c = ouiCard()
        ouiRow(c, "הוספת יישוב") { showAreaPicker(whole = false) }
        ouiDivider(c)
        ouiRow(c, "הוספת אזור שלם") { showAreaPicker(whole = true) }
        ouiAdd(body, c)

        // כרטיס האזורים שנבחרו: שם, ולידו בקטן האזור שהיישוב שייך אליו
        val P = AreaData.DISTRICT_PREFIX
        val dmap = AreaData.districtMap(this)
        val chosen = ouiCard()
        chosen.addView(text("אזורים שנבחרו", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
        val mine = Prefs.cities(this)
        if (mine.isEmpty()) ouiRow(chosen, "לא נבחרו אזורים")
        mine.forEachIndexed { i, city ->
            if (i > 0) ouiDivider(chosen)
            val isDistrict = city.startsWith(P)
            val name = city.removePrefix(P)
            val area = if (isDistrict) "אזור שלם" else dmap[name] ?: ""
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
            r.addView(text(name, 16f, C.TEXT))
            if (area.isNotEmpty()) r.addView(text(area, 12f, C.MUTED),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            chosen.addView(r, LinearLayout.LayoutParams(-1, dp(48)))
        }
        ouiAdd(body, chosen)
    }

    /**
     * בחירת אזורים נוספים בסגנון בחירת קבצים בסמסונג: "N נבחרו" למעלה, "הכל" (= כל הארץ) ו"ביטול",
     * חיפוש, ורשימה עם עיגול בחירה בכל שורה.
     * whole = בחירת אזורים שלמים (כל שורה אזור)
     */
    private fun showAreaPicker(whole: Boolean) {
        val dmap = AreaData.districtMap(this)
        val sel = LinkedHashSet(Prefs.cities(this))
        val P = AreaData.DISTRICT_PREFIX
        val allKey = P + AreaData.ALL_COUNTRY
        // "ברחבי הארץ" מאוחד עם "כל הארץ"
        val all = AreaData.areas(this).keys().asSequence().filter { !AreaData.isNational(it) }.toList()
        val districts = AreaData.districts(this)
        var query = ""
        // זמן ההתגוננות של אזור שלם: מהקצר לארוך מבין היישובים שבו
        val distTime: Map<String, String> = if (!whole) emptyMap() else
            all.groupBy { dmap[it] }.mapNotNull { (dist, names) ->
                val secs = names.mapNotNull { AreaData.shelterSeconds(this, it) }
                if (dist == null || secs.isEmpty()) null else {
                    val lo = secs.min(); val hi = secs.max()
                    dist to (if (lo == hi) AreaData.shelterText(lo) else AreaData.shelterText(lo) + " – " + AreaData.shelterText(hi))
                }
            }.toMap()

        fun covered(name: String) = allKey in sel ||
            (if (whole) (P + name) in sel else name in sel || (P + (dmap[name] ?: "")) in sel)
        lateinit var render: () -> Unit

        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL }

        // כותרת: [עיגול "הכל"] "N נבחרו"
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(20), dp(10)) }
        val allDot = CheckDot(false)
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(allDot, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(text("הכל", 12f, C.TEXT).apply { setPadding(0, dp(2), 0, 0) })
            setOnClickListener {
                // כל הארץ מכסה הכל - שאר הבחירות מיותרות
                if (!sel.remove(allKey)) { sel.clear(); sel.add(allKey) }
                render()
            }
        }, LinearLayout.LayoutParams(dp(44), -2))
        val count = text("", 26f, C.TEXT, bold = true)
        top.addView(count, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        root.addView(top)

        // חיפוש
        val search = EditText(this).apply {
            hint = if (whole) "חיפוש אזור" else "חיפוש יישוב"; setSingleLine(); textSize = 16f
            setTextColor(C.TEXT); setHintTextColor(C.MUTED)
            background = GradientDrawable().apply { setColor(C.CARD2); cornerRadius = dp(24).toFloat() }
            setPadding(dp(20), 0, dp(20), 0)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(12), 0, dp(12), dp(10)) })

        // הרשימה
        val items = ArrayList<String>()
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val name = items[p]
                val v = (cv as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(20), 0)
                    minimumHeight = dp(66)
                    addView(CheckDot(false), LinearLayout.LayoutParams(dp(22), dp(22)))
                    // שם היישוב ומתחתיו האזור; בסוף השורה "זמן התגוננות" ומתחתיו הזמן במילים
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text("", 17f, C.TEXT).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
                        addView(text("", 13f, C.MUTED).apply { setSingleLine(); setPadding(0, dp(2), 0, 0) })
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(18) })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                        addView(text("זמן התגוננות", 12f, C.MUTED))
                        addView(text("", 14f, C.TEXT).apply { setPadding(0, dp(2), 0, 0) })
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                }
                (v.getChildAt(0) as CheckDot).apply { on = covered(name); invalidate() }
                val names = v.getChildAt(1) as LinearLayout
                (names.getChildAt(0) as TextView).text = name
                (names.getChildAt(1) as TextView).apply {
                    text = if (whole) "" else dmap[name] ?: ""
                    visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                }
                val time = if (whole) distTime[name]
                    else AreaData.shelterSeconds(this@MainActivity, name)?.let { AreaData.shelterText(it) }
                val end = v.getChildAt(2) as LinearLayout
                end.visibility = if (time == null) View.GONE else View.VISIBLE
                (end.getChildAt(1) as TextView).text = time ?: ""
                return v
            }
        }
        val list = android.widget.ListView(this).apply {
            this.adapter = adapter; divider = null; selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false; setPadding(0, 0, 0, dp(120))
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        val fill = {
            val t = query.trim(); val k = Prefs.areaKey(t)
            items.clear()
            items.addAll((if (whole) districts else all).filter {
                t.isEmpty() || it.contains(t) || (k.isNotEmpty() && Prefs.areaKey(it).contains(k))
            }.sortedWith(compareBy<String>({ if (t.isNotEmpty() && it.startsWith(t)) 0 else 1 }, { it })))
            adapter.notifyDataSetChanged()
        }
        render = {
            count.text = if (sel.isEmpty()) "בחירת אזורים" else "${sel.size} נבחרו"
            allDot.on = allKey in sel; allDot.invalidate()
            adapter.notifyDataSetChanged()
        }
        list.setOnItemClickListener { _, _, p, _ ->
            val name = items[p]
            if (whole) {
                // אזור שלם: מבטלים מתוך "כל הארץ" - שאר האזורים נשארים
                if (sel.remove(allKey)) sel.addAll(districts.filter { it != name }.map { P + it })
                else if (!sel.remove(P + name)) { sel.add(P + name); sel.removeAll { !it.startsWith(P) && dmap[it] == name } }
            } else if (covered(name)) {
                if (name in sel) sel.remove(name)
                else {
                    // מבטלים יישוב מתוך אזור שלם שנבחר - שאר היישובים נשארים מסומנים
                    val dist = dmap[name]
                    if (sel.remove(allKey)) sel.addAll(districts.filter { it != dist }.map { P + it })
                    sel.remove(P + dist)
                    sel.addAll(all.filter { dmap[it] == dist && it != name })
                }
            } else sel.add(name)
            render()
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { query = e.toString(); fill() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        fill(); render()

        // גלולה צפה למטה כמו בשעון של סמסונג: ביטול | שמור
        val frame = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        val save = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            // כמו בשעון של סמסונג: גלולה כהה, טקסט לבן מודגש, קו מפריד דק
            background = GradientDrawable().apply { setColor(if (C.LIGHT) Color.parseColor("#E3E3E8") else Color.parseColor("#252525")); cornerRadius = dp(24).toFloat() }
            addView(text("ביטול", 17f, C.TEXT, bold = true).apply {
                gravity = Gravity.CENTER; setOnClickListener { d.dismiss() } }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(View(this@MainActivity).apply { setBackgroundColor(C.SEPV) }, LinearLayout.LayoutParams(dp(1), dp(20)))
            addView(text("שמור", 17f, C.TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { Prefs.setCities(this@MainActivity, sel.toList()); refresh(); d.dismiss() }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        frame.addView(save, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.47f).toInt(), dp(46),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = navMargin() })
        frame.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            root.setPadding(0, insets.systemWindowInsetTop, 0, 0)
            @Suppress("DEPRECATION")
            (save.layoutParams as FrameLayout.LayoutParams).bottomMargin = insets.systemWindowInsetBottom + dp(10)
            save.requestLayout()
            insets
        }
        d.setContentView(frame)
        d.window?.setLayout(-1, -1)
        @Suppress("DEPRECATION")
        d.window?.statusBarColor = C.BG
        edgeToEdge(d)
        track(d)
        d.show()
    }

    // ---- מסך הגדרות: כפתור לכל קטגוריה, וכל קטגוריה במסך משלה ----

    /** on != null - השורה מציגה מתג (לחיצה על השורה מדליקה/מכבה) */
    private class Row(val icon: String, val title: String, val value: () -> String,
                      val on: (() -> Boolean)? = null, val action: () -> Unit)

    // סגנון One UI: כרטיס אפור כהה אחיד עם פינות מעוגלות מאוד
    private fun graphite(@Suppress("UNUSED_PARAMETER") radius: Int) = GradientDrawable().apply {
        setColor(C.CARD2); cornerRadius = dp(28).toFloat() }
    /** גלולה צפה (כפתורים) */
    private fun pill() = GradientDrawable().apply { setColor(C.PILL); cornerRadius = dp(30).toFloat() }

    /** קטגוריה פתוחה במגירת ההגדרות (נשמרת בין פתיחות) */
    private var openCat = -1

    /** הגדרות: מגירה שנפתחת מצד ימין, כל קטגוריה נפתחת/נסגרת במקום */
    // ---- עמודי הגדרות בסגנון One UI - אותם רכיבים כמו בעמוד "תצוגה" ----
    private val OUI_BLUE get() = Color.parseColor("#5AA9FF")

    /** עמוד מלא: כותרת קטנה למעלה (כמו "תצוגה"), build נקרא מחדש בכל rebuild */
    private fun ouiPage(title: String, refreshOnFocus: Boolean = true, onClose: (() -> Unit)? = null,
                        button: Pair<String, () -> Unit>? = null,
                        build: (LinearLayout, () -> Unit) -> Unit) {
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(30)) }
        val page = ScrollView(this).apply {
            setBackgroundColor(C.BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(body)
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        lateinit var rebuild: () -> Unit
        rebuild = {
            body.removeAllViews()
            body.addView(text(title, 20f, C.TEXT, bold = true).apply { setPadding(dp(24), dp(14), dp(24), dp(14)) })
            build(body, rebuild)
        }
        rebuild()
        // התפריט התחתון קבוע גם בתת-עמודים
        val (frame, _) = withNav(d, page)
        body.setPadding(0, 0, 0, dp(if (button == null) 100 else 170))
        if (button == null) d.setContentView(frame)
        else {
            // כפתור כחול גדול קבוע מעל התפריט (כמו "בדוק אם יש עדכונים" בסמסונג)
            // מידות לפי הצילום מסמסונג: 60% רוחב, גובה 46, עיגול מלא, כחול #4C7DFF, טקסט 16 מודגש
            val btn = text(button.first, 16f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                background = GradientDrawable().apply { setColor(Color.parseColor("#4C7DFF")); cornerRadius = dp(23).toFloat() }
                setOnClickListener { button.second() }
            }
            val above = dp(78)   // מעל התפריט התחתון
            frame.addView(btn, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.6f).toInt(), dp(46),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = navMargin() + above })
            frame.viewTreeObserver.addOnGlobalLayoutListener {
                val want = navMargin() + above
                val lp = btn.layoutParams as FrameLayout.LayoutParams
                if (lp.bottomMargin != want) { lp.bottomMargin = want; btn.layoutParams = lp }
            }
            d.setContentView(frame)
        }
        d.window?.setLayout(-1, -1)
        @Suppress("DEPRECATION")
        d.window?.statusBarColor = C.BG
        edgeToEdge(d)
        if (refreshOnFocus) d.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { has -> if (has) rebuild() }
        track(d, onClose)
        d.show()
    }

    private fun ouiCard() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = graphite(26); clipToOutline = true }
    private fun ouiAdd(body: LinearLayout, c: View) =
        body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), 0, dp(10), dp(8)) })
    private fun ouiNote(body: LinearLayout, t: String) =
        body.addView(text(t, 11f, C.MUTED2).apply { setPadding(dp(26), 0, dp(26), dp(14)) })
    private fun ouiDivider(c: LinearLayout) = c.addView(View(this).apply { setBackgroundColor(C.DIV) },
        LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(18), 0, dp(18), 0) })

    /** כפתור בחירה כמו בסמסונג: טבעת, ובנבחר נקודה כחולה */
    private fun ouiRadio(sel: Boolean) = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), Color.parseColor(if (sel) "#3E82F7" else "#6E6E73"))
        }
        if (sel) addView(View(this@MainActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#3E82F7")) }
        }, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
    }

    /**
     * שורה בגובה קבוע (48, או 56 עם שורת מצב): שם, מצב בכחול מתחת,
     * ובצד: ערך (end), כפתור בחירה (radio) או מתג (on)
     */
    private fun ouiRow(c: LinearLayout, title: String, sub: String = "", subColor: Int = OUI_BLUE,
                       end: String? = null, endColor: Int = C.TEXT, endBig: Boolean = false,
                       radio: Boolean? = null, on: Boolean? = null, onToggle: (() -> Unit)? = null,
                       click: (() -> Unit)? = null) {
        val r = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            if (click != null) setOnClickListener { click() }
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(text(title, 16f, C.TEXT).apply { includeFontPadding = false })
        if (sub.isNotEmpty()) t.addView(text(sub, 12f, subColor).apply { includeFontPadding = false; setPadding(0, dp(4), 0, 0) })
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        if (end != null) r.addView(text(end, if (endBig) 22f else 14f, endColor, bold = endBig).apply { includeFontPadding = false },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        if (radio != null) r.addView(ouiRadio(radio), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(10) })
        // onToggle: לחיצה על השורה פותחת הגדרה, והמתג לבד מדליק/מכבה (כמו בעמוד תצוגה)
        if (on != null && onToggle != null) r.addView(View(this).apply { setBackgroundColor(C.SEPV) },
            LinearLayout.LayoutParams(dp(1), dp(26)).apply { setMargins(dp(12), 0, 0, 0) })
        if (on != null) r.addView(android.widget.Switch(this).apply {
            minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0)
            isChecked = on
            if (onToggle != null) setOnClickListener { onToggle() } else isClickable = false
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(if (on) "#3E82F7" else "#5A5A5E"))
            trackTintMode = android.graphics.PorterDuff.Mode.SRC
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        c.addView(r, LinearLayout.LayoutParams(-1, dp(if (sub.isEmpty()) 48 else 56)))
    }

    /** טקסט חופשי בתוך כרטיס (הסברים, מה חדש) */
    private fun ouiText(c: LinearLayout, t: String) =
        c.addView(text(t, 14f, C.TEXT).apply { setPadding(dp(18), dp(12), dp(18), dp(12)); setLineSpacing(dp(3).toFloat(), 1f) })

    /** מסך מצב שבת: בחירת מצב, עיר וזמני כניסה ויציאה, והסבר מה המצב עושה */
    private fun chooseShabbat() = ouiPage("מצב שבת") { body, rebuild ->
        // מצב
        val modes = ouiCard()
        listOf(Shabbat.OFF to "כבוי", Shabbat.ON to "דלוק", Shabbat.AUTO to "אוטומטי")
            .forEachIndexed { i, (mode, label) ->
                if (i > 0) ouiDivider(modes)
                ouiRow(modes, label, sub = if (mode == Shabbat.AUTO) "בשבת ובחג לפי המיקום" else "",
                    radio = Prefs.shabbatMode(this) == mode) { Prefs.setShabbatMode(this, mode); rebuild() }
            }
        ouiAdd(body, modes)
        ouiNote(body, "באוטומטי מצב השבת נדלק בכניסה ונכבה ביציאה, בלי לגעת")

        // זמנים
        val fmtDay = java.text.SimpleDateFormat("EEEE, d בMMMM", java.util.Locale("iw"))
        val fmtTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        val w = try { Shabbat.nextWindow(this) } catch (_: Exception) { null }
        val times = ouiCard()
        val now = System.currentTimeMillis()
        times.addView(text(if (w != null && now >= w.first) "השבת עכשיו" else "השבת הקרובה", 16f, C.TEXT)
            .apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
        // מיקום (עיר) שלפיו מחושבים הזמנים - נטען ברקע
        val hasLoc = Shabbat.hasLocation(this)
        ouiRow(times, "מיקום", sub = if (hasLoc) "…" else "מרכז הארץ (אין מיקום)")
        if (hasLoc) {
            val row = times.getChildAt(times.childCount - 1) as LinearLayout
            val subView = (row.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
            (try { Weather.lastLocation(this) } catch (_: Exception) { null })?.let { loc ->
                Thread {
                    val city = try { Weather.cityName(this, loc) } catch (_: Exception) { "" }
                    runOnUiThread { subView.text = city.ifBlank { "המיקום שלך" } }
                }.start()
            }
        }
        if (w == null) { ouiDivider(times); ouiRow(times, "לא הצלחנו לחשב את הזמנים") }
        else {
            ouiDivider(times)
            ouiRow(times, "כניסה", sub = fmtDay.format(java.util.Date(w.first)), subColor = C.MUTED,
                end = fmtTime.format(java.util.Date(w.first)), endBig = true)
            ouiDivider(times)
            ouiRow(times, "יציאה", sub = fmtDay.format(java.util.Date(w.second)), subColor = C.MUTED,
                end = fmtTime.format(java.util.Date(w.second)), endBig = true)
        }
        ouiAdd(body, times)
        ouiNote(body, "כניסה חצי שעה לפני השקיעה, יציאה 10 דקות אחרי צאת הכוכבים")

        // מה מצב שבת עושה
        val info = ouiCard()
        info.addView(text("מה מצב שבת עושה", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), 0) })
        ouiText(info, listOf(
            "כל התרעה נפתחת במסך מלא, גם כשהטלפון בשימוש",
            "ההתרעה מוקראת בקול, גם אם ההקראה כבויה בהגדרות",
            "מסך ההתרעה נסגר לבד אחרי דקה – אין צורך לגעת בטלפון",
            "עוקף הכל: שעות שקט, נא לא להפריע, מצב שקט של הטלפון, וגם צלילים ורטט כבויים"
        ).joinToString("\n") { "• $it" })
        ouiAdd(body, info)
    }

    private fun chooseTest() {
        val tests = listOf(
            "מקדימה" to "בדקות הקרובות צפויות להתקבל התרעות באזורך",
            "בדיקת ירי" to "ירי רקטות וטילים",
            "סיום" to "האירוע הסתיים")
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("בדיקת התראה")
            .setItems(tests.map { it.first }.toTypedArray()) { _, i ->
                val title = tests[i].second
                Prefs.setEnabled(this, true)
                AlertService.start(this, testTitle = title)
                if (i != 1 && Prefs.isQuietNow(this)) {
                    android.widget.Toast.makeText(this,
                        "שעות שקט פעילות – ההתראה נשלחה בשקט, בלי צליל ומסך מלא",
                        android.widget.Toast.LENGTH_LONG).show()
                }
                refresh()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    /** אייקון בקו לבן לכל שורה בהגדרות (לפי האמוג'י שלה) */
    private fun setIcon(icon: String) = when (icon) {
        "🎨" -> R.drawable.ic_set_palette; "🚨" -> R.drawable.ic_set_siren; "🕐" -> R.drawable.ic_set_clock
        "🌤" -> R.drawable.ic_set_weather; "👋" -> R.drawable.ic_set_hand; "📍" -> R.drawable.ic_set_pin
        "🔔" -> R.drawable.ic_set_bell; "📳" -> R.drawable.ic_set_vibrate; "🗣" -> R.drawable.ic_set_speech
        "🌙" -> R.drawable.ic_set_moon; "🔕" -> R.drawable.ic_set_belloff; "🕯" -> R.drawable.ic_set_candle
        "📱" -> R.drawable.ic_set_phone; "🔋" -> R.drawable.ic_set_battery; "⚙️" -> R.drawable.ic_set_gear
        "⬆️" -> R.drawable.ic_set_update; "🆕" -> R.drawable.ic_set_new; "ℹ️" -> R.drawable.ic_set_info
        "🧪" -> R.drawable.ic_set_beta; "⏻" -> R.drawable.ic_shield_ind
        else -> 0
    }

    /** צבע העיגול של כל אייקון בהגדרות (סגנון One UI) */
    private fun iconColor(icon: String) = Color.parseColor(when (icon) {
        "🎨" -> "#7E57C2"; "🚨", "📍", "🆕" -> "#E53935"; "🕐", "📱", "⬆️" -> "#1E88E5"
        "🌤", "🔔" -> "#FB8C00"; "👋", "🔋" -> "#43A047"; "📳" -> "#8E24AA"
        "🗣", "🧪" -> "#00897B"; "🌙" -> "#3949AB"; "🔕" -> "#6D4C41"; "🕯" -> "#F9A825"
        "⏻" -> "#D50000"
        else -> "#546E7A"
    })

    private fun bottomNav(sel: Int, reselect: Boolean = false, onTab: (Int) -> Unit) =
        NavBar.build(this, sel, C.LIGHT, reselect, onTab)

    /** כל העמודים הפתוחים (הגדרות, תת-עמודים, התרעות) - כדי שהתפריט התחתון יסגור את כולם במעבר */
    private val pages = mutableListOf<android.app.Dialog>()
    private fun track(d: android.app.Dialog, onClose: (() -> Unit)? = null) {
        pages.add(d)
        d.setOnDismissListener { pages.remove(d); onClose?.invoke() }
    }

    /**
     * עמוד עם התפריט התחתון קבוע (תת-עמודי הגדרות: הלשונית "הגדרות" מסומנת,
     * ולחיצה עליה חוזרת לעמוד ההגדרות הראשי)
     */
    private fun withNav(d: android.app.Dialog, page: View, sel: Int = 2): Pair<FrameLayout, View> {
        val frame = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        frame.addView(page, FrameLayout.LayoutParams(-1, -1))
        val nav = bottomNav(sel, reselect = true) { i -> switchTab(d, i) }
        frame.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = navMargin() })
        frame.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            (nav.layoutParams as FrameLayout.LayoutParams).bottomMargin = insets.systemWindowInsetBottom + dp(10)
            nav.requestLayout()
            page.dispatchApplyWindowInsets(insets)
        }
        return frame to nav
    }

    /**
     * מעבר בין לשוניות: קודם פותחים את הלשונית החדשה ורק אחר כך סוגרים את הישנה,
     * כדי שמסך הבית לא יקפוץ לרגע באמצע
     */
    private fun switchTab(d: android.app.Dialog, i: Int) {
        val old = (pages + d).distinct()
        if (i == 0) { old.forEach { try { it.dismiss() } catch (_: Exception) { } }; return }
        openTab(i)
        ui.postDelayed({ old.forEach { try { it.dismiss() } catch (_: Exception) { } } }, 300)
    }

    /** מעבר ללשונית מהתפריט התחתון (0 = המסך הראשי עצמו) */
    private fun openTab(i: Int) {
        when (i) {
            1 -> showRecent()
            2 -> showSettings()
        }
    }

    /** הגדרות בסגנון One UI: עמוד מלא, כותרת גדולה, כל הקבוצות פתוחות */
    private fun showSettings() {
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val page = ScrollView(this).apply {
            setBackgroundColor(C.BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(body)
        }
        page.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        val cats = listOf(
            // הפעלה / כיבוי של האפליקציה - כמו הכפתור בשורת החיווי
            "צבע אדום" to { listOf(Row("⏻", "הגנה", { if (Prefs.enabled(this)) "פעיל – מוגן" else "כבוי – לא מקבל התרעות" },
                on = { Prefs.enabled(this) }) { toggle() }) },
            "תצוגה" to { listOf(Row("🎨", "תצוגה", { "ערכת צבעים · שעון · תאריך · מזג אוויר" }) { showDisplay() }) },
            "צלילים ורטט" to { listOf(
                Row("📳", "צלילים ורטט", { "שעות שקט · נא לא להפריע · מצב שקט · שבת" }) { showSoundSettings() }) },
            "התרעות" to { listOf(
                Row("🔔", "הגדרות התרעות", { "סוג תצוגה · מיקום · אזורים נוספים · הקראה · בדיקה" }) { showAlertSettings() }) },
            "הרשאות" to { phoneRows() },
            "כללי" to { generalRows() }
        )
        fun rebuild() {
            body.removeAllViews()
            body.addView(text("הגדרות", 32f, C.TEXT, bold = true).apply { setPadding(dp(24), dp(64), dp(24), dp(16)) })
            cats.forEach { (title, rows) ->
                body.addView(text(title, 13f, C.MUTED2).apply { setPadding(dp(28), dp(14), dp(28), dp(6)) })
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = graphite(28)
                    clipToOutline = true
                }
                val list = rows()
                list.forEachIndexed { i, r ->
                    if (i > 0) card.addView(View(this).apply { setBackgroundColor(C.DIV) },
                        LinearLayout.LayoutParams(-1, dp(1)))
                    val row = LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                        setOnClickListener { r.action(); rebuild() }
                    }
                    // אייקון פשוט בקו לבן בתוך עיגול צבעוני
                    row.addView(FrameLayout(this).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(iconColor(r.icon)) }
                        val res = setIcon(r.icon)
                        if (res != 0) addView(android.widget.ImageView(this@MainActivity).apply { setImageResource(res) },
                            FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                        else addView(text(r.icon, 16f, Color.WHITE).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                    }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
                    val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    texts.addView(text(r.title, 16f, C.TEXT))
                    val v = r.value()
                    if (v.isNotEmpty()) texts.addView(text(v, 12f, Color.parseColor("#5AA9FF")))
                    row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                    r.on?.let { isOn ->
                        val on = isOn()
                        row.addView(android.widget.Switch(this).apply {
                            minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0)
                            isChecked = on; isClickable = false
                            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                            trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(if (on) "#3E82F7" else "#5A5A5E"))
                            trackTintMode = android.graphics.PorterDuff.Mode.SRC
                        })
                    }
                    card.addView(row)
                }
                body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), 0, dp(10), 0) })
            }
            body.addView(View(this), LinearLayout.LayoutParams(-1, dp(90)))   // מקום לתפריט התחתון
        }
        rebuild()
        // התפריט התחתון נשאר גם בהגדרות
        val frame = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        frame.addView(page, FrameLayout.LayoutParams(-1, -1))
        val nav = bottomNav(2) { i -> switchTab(d, i) }
        frame.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = navMargin() })
        frame.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            (nav.layoutParams as FrameLayout.LayoutParams).bottomMargin = insets.systemWindowInsetBottom + dp(10)
            nav.requestLayout()
            page.dispatchApplyWindowInsets(insets)
        }
        d.setContentView(frame)
        d.window?.setLayout(-1, -1)
        @Suppress("DEPRECATION")
        d.window?.statusBarColor = C.BG
        edgeToEdge(d)
        // חוזרים מחלון של הגדרה - מעדכנים את המצבים
        d.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { has -> if (has) rebuild() }
        track(d)
        d.show()
    }

    private fun onOff(b: Boolean) = if (b) "פעיל" else "כבוי"

    /** עמוד התרעות: כל ההגדרות של ההתרעה במקום אחד */
    private fun showAlertSettings() = ouiPage("הגדרות התרעות") { body, rebuild ->
        // סוג תצוגת התרעה: הדמיה קטנה של כל סוג + בחירה (הועבר מעמוד תצוגה)
        val style = ouiCard()
        style.addView(text("סוג תצוגת התרעה", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
        val opts = LinearLayout(this).apply { setPadding(dp(6), dp(10), dp(6), dp(16)) }
        listOf("מסך מלא", "פופ-אפ", "כרטיס", "ממוזער").forEachIndexed { i, label ->
            val sel = Prefs.alertStyle(this) == i
            opts.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(alertStylePreview(i), LinearLayout.LayoutParams(dp(58), dp(116)))
                addLabelRadio(this, label, sel)
                setOnClickListener { Prefs.setAlertStyle(this@MainActivity, i); rebuild() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        style.addView(opts)
        ouiDivider(style)
        ouiRow(style, "מפה במסך ההתרעה", on = Prefs.alertMap(this)) {
            Prefs.setAlertMap(this, !Prefs.alertMap(this)); rebuild() }
        ouiAdd(body, style)
        ouiNote(body, "המפה מוצגת במסך מלא ובפופ-אפ")

        val c = ouiCard()
        // מתג = הדלקה/כיבוי, לחיצה על השם = ההגדרות המלאות
        ouiRow(c, "אזור התרעה", sub = onOff(Prefs.nearMe(this)), on = Prefs.nearMe(this)) { toggleNearMe(); rebuild() }
        ouiDivider(c)
        ouiRow(c, "מרחק מקסימלי", sub = Prefs.radiusLabel(Prefs.nearRadiusKm(this))) { showNearRadius() }
        ouiAdd(body, c)

        // אזורים נוספים - שורה אחת שפותחת את חלון האזורים
        val extra = ouiCard()
        ouiRow(extra, "אזורים נוספים") { showExtraAreas() }
        ouiAdd(body, extra)
        // הקראה - כרטיס נפרד
        val sp = ouiCard()
        ouiRow(sp, "הקראה", sub = onOff(Prefs.speakAlerts(this)), on = Prefs.speakAlerts(this),
            onToggle = { Prefs.setSpeakAlerts(this, !Prefs.speakAlerts(this)); rebuild() }) { showSpeech() }
        ouiAdd(body, sp)
        // בדיקת התראה (עברה מהכפתור שבמסך הראשי)
        val t = ouiCard()
        ouiRow(t, "בדיקת התראה", sub = "מקדימה · ירי · סיום") { chooseTest() }
        ouiAdd(body, t)
    }

    /** עמוד מרחק מקסימלי: בחירת מרחק, והיישובים שבקרבתי לפי המרחק הזה */
    private fun showNearRadius() = ouiPage("מרחק מקסימלי") { body, rebuild ->
        val r = ouiCard()
        val radii = Prefs.NEAR_RADII
        val cur = radii.indexOfFirst { it == Prefs.nearRadiusKm(this) }.coerceAtLeast(0)
        ouiRow(r, "מרחק", sub = Prefs.radiusLabel(radii[cur]))
        // הכיתוב הכחול מתחת ל"מרחק" - מתעדכן תוך כדי גרירה
        val distLabel = ((r.getChildAt(r.childCount - 1) as LinearLayout).getChildAt(0) as LinearLayout).getChildAt(1) as TextView
        ouiAdd(body, r)
        // פס בחירה בשלבים בכרטיס משלו, כמו "גודל גופן" בסמסונג: קרוב מימין, רחוק משמאל
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(C.CARD2); cornerRadius = dp(32).toFloat() }
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        bar.addView(StepSlider(this, radii.size, cur, C.LIGHT) { i ->
            distLabel.text = Prefs.radiusLabel(radii[i])
            val km = radii[i]
            Prefs.setNearRadiusKm(this, km)
            // חישוב מחדש מיד לפי המיקום האחרון (אם אין - מיקום חדש)
            val last = Prefs.lastNearLoc(this)
            if (last != null) Thread {
                try { Prefs.setNearbyAreas(this, AreaData.nearby(this, last.first, last.second)) } catch (_: Exception) { }
                runOnUiThread { rebuild() }
            }.start() else { refreshNearby(); rebuild() }
        }.apply { onMove = { i -> distLabel.text = Prefs.radiusLabel(radii[i]) } },
            LinearLayout.LayoutParams(0, -2, 1f))   // מימין: באזורך · משמאל: 10 ק"מ
        ouiAdd(body, bar)
        ouiNote(body, "התרעות יגיעו לאזורים שבמרחק הזה מהמיקום שלך, בנוסף לאזורים שהוספת")

        val near = ouiCard()
        near.addView(text("יישובים בקרבתי", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
        val list = Prefs.nearbyAreas(this).filterNot { AreaData.isNational(it) }
        if (!Prefs.nearMe(this)) ouiRow(near, "אזור התרעה כבוי")
        else if (list.isEmpty()) ouiRow(near, "עוד לא זוהה מיקום")
        else list.forEachIndexed { i, a -> if (i > 0) ouiDivider(near); ouiRow(near, a) }
        ouiAdd(body, near)
    }

    /** עמוד צלילים ורטט: צלילים, רטט, שעות שקט, נא לא להפריע, מצב שקט ומצב שבת */
    private fun showSoundSettings() = ouiPage("צלילים ורטט") { body, rebuild ->
        val c = ouiCard()
        val snd = Prefs.soundsOn(this); val vib = Prefs.vibesOn(this)
        ouiRow(c, "צלילים", sub = if (!snd) "כבוי" else if (AlertService.PHONE_DEFAULTS) "של הטלפון" else "פעיל",
            on = snd, onToggle = { Prefs.setSoundsOn(this, !snd); rebuild() }) {
            if (AlertService.PHONE_DEFAULTS) openNotificationSettings() else showSounds() }
        ouiDivider(c)
        ouiRow(c, "רטט", sub = if (!vib) "כבוי" else if (AlertService.PHONE_DEFAULTS) "של הטלפון" else "פעיל",
            on = vib, onToggle = { Prefs.setVibesOn(this, !vib); rebuild() }) {
            if (AlertService.PHONE_DEFAULTS) openNotificationSettings() else showVibes() }
        ouiAdd(body, c)

        val q = ouiCard()
        ouiRow(q, "שעות שקט",
            sub = if (Prefs.quietOn(this)) "${hhmm(Prefs.quietFrom(this))}–${hhmm(Prefs.quietTo(this))}" else "כבוי",
            on = Prefs.quietOn(this),
            onToggle = { Prefs.setQuietOn(this, !Prefs.quietOn(this)); rebuild() }) { showQuietHours() }
        ouiDivider(q)
        // רק הדלקה/כיבוי - ההרשאה עצמה במסך ההרשאות
        ouiRow(q, "עקיפת נא לא להפריע", sub = onOff(Prefs.dndOverride(this)), on = Prefs.dndOverride(this)) {
            Prefs.setDndOverride(this, !Prefs.dndOverride(this)); rebuild() }
        ouiDivider(q)
        ouiRow(q, "עקיפת מצב שקט", sub = if (Prefs.bypassSilent(this)) "צליל גם כשהטלפון על שקט או רטט" else "כבוי",
            on = Prefs.bypassSilent(this)) { Prefs.setBypassSilent(this, !Prefs.bypassSilent(this)); rebuild() }
        ouiDivider(q)
        // מצב שבת: המתג מדליק "אוטומטי" או מכבה. דלוק תמיד - בעמוד מצב שבת
        val sh = Prefs.shabbatMode(this)
        ouiRow(q, "מצב שבת", sub = Shabbat.label(sh), on = sh != Shabbat.OFF,
            onToggle = { Prefs.setShabbatMode(this, if (sh == Shabbat.OFF) Shabbat.AUTO else Shabbat.OFF); rebuild() }) { chooseShabbat() }
        ouiAdd(body, q)
        ouiNote(body, "מצב שבת עוקף את שעות השקט, נא לא להפריע ומצב שקט")
    }

    companion object { private var reopenDisplay = false }

    /**
     * עמודים שנפתחים כחלון (הגדרות, התרעות, תצוגה) - מצוירים מתחת לשורת המצב ולסרגל הניווט כמו המסך הראשי,
     * כדי שהתפריט התחתון יישב בדיוק באותו מקום בכל המסכים (בלי לזוז במעבר בין לשוניות)
     */
    /** מרחק התפריט התחתון מהתחתית - מחושב מיד (לא מחכים לאירוע insets), כדי שלא יקפוץ בפתיחה */
    private fun navMargin(): Int {
        @Suppress("DEPRECATION")
        val inset = window?.decorView?.rootWindowInsets?.systemWindowInsetBottom ?: 0
        return inset + dp(10)
    }

    private fun edgeToEdge(d: android.app.Dialog) {
        val w = d.window ?: return
        // בלי אנימציית פתיחה/סגירה - העמוד מופיע במקום, והתפריט התחתון לא זז
        w.setWindowAnimations(0)
        // החלונות בנויים על ערכת "Theme.Black" הישנה - בה המערכת מציירת לשורת המצב רקע שחור משלה.
        // בעיצוב בהיר האייקונים כהים, אז על השחור לא רואים שעה/סוללה. מבקשים לצייר את הרקע בעצמנו בצבע העמוד
        @Suppress("DEPRECATION")
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        w.statusBarColor = C.BG
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(C.BG))
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") { w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            (if (C.LIGHT) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0) }
        @Suppress("DEPRECATION")
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30 && C.LIGHT) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(light, light)
        }
    }

    /** כיתוב ממורכז + כפתור בחירה עגול (מתחת לתצוגה מקדימה) */
    private fun addLabelRadio(parent: LinearLayout, label: String, sel: Boolean) {
        parent.addView(text(label, 13f, if (sel) Color.parseColor("#5AA9FF") else C.MUTED2, bold = sel).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
        // כפתור בחירה כמו בסמסונג: טבעת, ובנבחר - נקודה כחולה בתוכה עם רווח
        parent.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.parseColor(if (sel) "#3E82F7" else "#6E6E73"))
            }
            if (sel) addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#3E82F7")) }
            }, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
    }

    /** הדמיה קטנה של סוג תצוגת ההתרעה: 0 מסך מלא · 1 פופ-אפ · 2 כרטיס · 3 ממוזער */
    private fun alertStylePreview(kind: Int): View {
        fun rect(c: String, r: Int) = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(r).toFloat() }
        // בהיר / חשוך לפי ערכת הצבעים
        val bg = if (C.LIGHT) "#D9D9DC" else "#2B2B2E"
        val block = if (C.LIGHT) "#BDBDC2" else "#3E3E42"
        val red = "#D50000"
        val f = FrameLayout(this).apply { background = rect(bg, 10); clipToOutline = true }
        // רשימת כרטיסים עם רווח שווה; בכרטיס/ממוזער - ההתרעה היא אחד הכרטיסים ברשימה
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(5), dp(5), dp(5), dp(5)) }
        listOf(10, 20, 30, 20).forEachIndexed { i, h ->
            val isAlert = (kind == 3 && i == 0) || (kind == 2 && i == 1)
            list.addView(View(this).apply { background = rect(if (isAlert) red else block, 4) },
                LinearLayout.LayoutParams(-1, dp(h)).apply { bottomMargin = dp(4) })
        }
        f.addView(list, FrameLayout.LayoutParams(-1, -1))
        when (kind) {
            0 -> f.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(red))
                addView(View(this@MainActivity).apply { background = rect("#B3FFFFFF", 3) },
                    LinearLayout.LayoutParams(-1, dp(7)).apply { setMargins(dp(10), dp(8), dp(10), dp(4)) })
                if (Prefs.alertMap(this@MainActivity))
                    addView(View(this@MainActivity).apply { setBackgroundColor(Color.parseColor(if (C.LIGHT) "#BDBDC2" else "#333333")) }, LinearLayout.LayoutParams(-1, dp(30)))   // המפה
                addView(View(this@MainActivity).apply { background = rect("#40000000", 5) },
                    LinearLayout.LayoutParams(-1, dp(18)).apply { setMargins(dp(8), dp(6), dp(8), 0) })
            }, FrameLayout.LayoutParams(-1, -1))
            // פופ-אפ: כרטיס אדום באמצע, עם המפה בתוכו (כמו במסך המלא)
            1 -> f.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rect(red, 8); clipToOutline = true
                addView(View(this@MainActivity).apply { background = rect("#B3FFFFFF", 2) },
                    LinearLayout.LayoutParams(-1, dp(5)).apply { setMargins(dp(8), dp(6), dp(8), dp(4)) })
                if (Prefs.alertMap(this@MainActivity))
                    addView(View(this@MainActivity).apply { setBackgroundColor(Color.parseColor(if (C.LIGHT) "#BDBDC2" else "#333333")) },
                        LinearLayout.LayoutParams(-1, dp(18)))
                addView(View(this@MainActivity).apply { background = rect("#40000000", 3) },
                    LinearLayout.LayoutParams(-1, dp(12)).apply { setMargins(dp(6), dp(5), dp(6), dp(6)) })
            }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        return f
    }

    /** ערכת הצבעים נשמרת מיד, ומוחלת (טעינה מחדש) רק ביציאה מעמוד התצוגה - בלי קפיצות בזמן הבחירה */
    private var themeAtOpen = -1
    private fun setTheme(d: android.app.Dialog, mode: Int, rebuild: () -> Unit) {
        if (Prefs.themeMode(this) == mode) return
        Prefs.setThemeMode(this, mode)
        // עמוד התצוגה מתחלף מיד לצבעים החדשים; שאר האפליקציה - ביציאה מהעמוד
        C.apply(Prefs.darkTheme(this))
        (d.window?.decorView?.findViewWithTag<View>("displayPage"))?.setBackgroundColor(C.BG)
        @Suppress("DEPRECATION")
        d.window?.statusBarColor = C.BG
        if (Build.VERSION.SDK_INT >= 30) {
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            d.window?.insetsController?.setSystemBarsAppearance(if (C.LIGHT) light else 0, light)
        }
        rebuild()
    }

    /** עמוד "תצוגה" בסגנון One UI (כמו בהגדרות של סמסונג) */
    private fun showDisplay() {
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(30)) }
        val page = ScrollView(this).apply {
            tag = "displayPage"
            setBackgroundColor(C.BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(body)
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        val blue = Color.parseColor("#5AA9FF")
        fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = graphite(26); clipToOutline = true }
        fun addCard(c: View) = body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), 0, dp(10), dp(8)) })
        fun note(t: String) = body.addView(text(t, 11f, C.MUTED2).apply { setPadding(dp(26), 0, dp(26), dp(14)) })
        // קו מפריד עם שוליים מהצדדים (לא מקצה לקצה), כמו בהגדרות של סמסונג
        fun divider(c: LinearLayout) = c.addView(View(this).apply { setBackgroundColor(C.DIV) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(18), 0, dp(18), 0) })
        /** שורה: שם, מצב בכחול, ומתג (אם יש) */
        /** שורה: שם + מצב בכחול + מתג. open = לחיצה על השם פותחת הגדרה נוספת (והמתג לבד מדליק/מכבה) */
        fun row(c: LinearLayout, title: String, sub: String = "", on: Boolean? = null, sep: Boolean = true,
                open: (() -> Unit)? = null, click: () -> Unit) {
            // גובה קבוע כמו בסמסונג: שורה רגילה 48, שורה עם מצב מתחת 56 (המתג לא מגביה)
            val r = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener { (open ?: click)() }
            }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(text(title, 16f, C.TEXT).apply { includeFontPadding = false })
            if (sub.isNotEmpty()) t.addView(text(sub, 12f, blue).apply { includeFontPadding = false; setPadding(0, dp(4), 0, 0) })
            r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            if (on != null) {
                if (sep) r.addView(View(this).apply { setBackgroundColor(C.SEPV) },
                    LinearLayout.LayoutParams(dp(1), dp(26)).apply { setMargins(dp(12), 0, dp(12), 0) })
                r.addView(android.widget.Switch(this).apply {
                    minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0)
                    isChecked = on
                    if (open != null) setOnClickListener { click() } else isClickable = false
                    thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    trackTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(if (on) "#3E82F7" else "#5A5A5E"))
                    trackTintMode = android.graphics.PorterDuff.Mode.SRC
                })
            }
            c.addView(r, LinearLayout.LayoutParams(-1, dp(if (sub.isEmpty()) 48 else 56)))
        }
        fun rebuild() {
            body.removeAllViews()
            // כותרת עם חזרה
            body.addView(text("תצוגה", 20f, C.TEXT, bold = true).apply { setPadding(dp(24), dp(14), dp(24), dp(14)) })
            // ערכת צבעים: תצוגה מקדימה חשוך / בהיר + "לפי המערכת"
            val mode = Prefs.themeMode(this)
            val theme = card()
            val pick = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(20), dp(10), dp(16)) }
            fun preview(light: Boolean, label: String, sel: Boolean, value: Int) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val box = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = GradientDrawable().apply { setColor(Color.parseColor(if (light) "#D9D9DC" else "#2B2B2E")); cornerRadius = dp(12).toFloat() }
                    listOf(12, 26, 40, 26).forEach { h ->
                        addView(View(this@MainActivity).apply {
                            background = GradientDrawable().apply { setColor(Color.parseColor(if (light) "#BDBDC2" else "#3E3E42")); cornerRadius = dp(6).toFloat() }
                        }, LinearLayout.LayoutParams(-1, dp(h)).apply { bottomMargin = dp(5) })
                    }
                }
                addView(box, LinearLayout.LayoutParams(dp(76), dp(150)))
                addLabelRadio(this, label, sel)
                setOnClickListener { setTheme(d, value) { rebuild() } }
            }
            val sys = mode == 2
            pick.addView(preview(false, "חשוך", !sys && mode == 0, 0), LinearLayout.LayoutParams(0, -2, 1f))
            pick.addView(preview(true, "בהיר", !sys && mode == 1, 1), LinearLayout.LayoutParams(0, -2, 1f))
            theme.addView(pick)
            divider(theme)
            row(theme, "לפי המערכת", on = sys, sep = false) { setTheme(d, if (sys) 0 else 2) { rebuild() } }
            addCard(theme)
            note("כשמופעל - בהיר או חשוך לפי הגדרת הטלפון")
            // שעון ותאריך, מזג אוויר וברכה - בכרטיס אחד
            val more = card()
            row(more, "שעון", listOf(if (Prefs.showSeconds(this)) "שניות" else "", if (Prefs.blinkColon(this)) "נקודתיים מהבהבות" else "")
                .filter { it.isNotEmpty() }.joinToString(" · ")) { showClockPage() }
            divider(more)
            row(more, "תאריך", TimeFormat.dayOptions()[Prefs.dayStyle(this)], on = Prefs.showDate(this),
                open = { chooseDayStyle { rebuild() } }) {
                Prefs.setShowDate(this, !Prefs.showDate(this)); updateClock(); refresh(); rebuild() }
            divider(more)
            row(more, "מזג אוויר", "עדכון " + weatherLabel(Prefs.weatherMinutes(this)), on = Prefs.showWeather(this),
                open = { chooseWeatherInterval { rebuild() } }) {
                Prefs.setShowWeather(this, !Prefs.showWeather(this)); updateWeather(force = true); rebuild() }
            divider(more)
            row(more, "ברכה", on = Prefs.showGreeting(this), sep = false) {
                Prefs.setShowGreeting(this, !Prefs.showGreeting(this)); updateClock(); rebuild() }
            addCard(more)
            note("מזג אוויר וברכה בכרטיס השעון במסך הראשי")
        }
        themeAtOpen = Prefs.themeMode(this)
        rebuild()
        // יציאה מהעמוד: אם ערכת הצבעים השתנתה - טוענים מחדש וחוזרים להגדרות
        track(d) { if (Prefs.themeMode(this) != themeAtOpen) { reopenDisplay = true; recreate() } }
        body.setPadding(0, 0, 0, dp(100))
        d.setContentView(withNav(d, page).first)   // התפריט התחתון קבוע גם כאן
        d.window?.setLayout(-1, -1)
        @Suppress("DEPRECATION")
        d.window?.statusBarColor = C.BG
        edgeToEdge(d)
        // חוזרים מחלון בחירה - מעדכנים את המצבים
        d.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { has -> if (has) rebuild() }
        d.show()
    }

    /** הרשאות: שורה אחת שפותחת מסך עם כל ההרשאות והמצב של כל אחת */
    private fun phoneRows(): List<Row> = listOf(
        Row("🛡", "הרשאות", {
            val bad = permissionItems().count { !it.ok }
            if (bad == 0) "הכל תקין ✓" else "$bad דורש טיפול"
        }) { showPermissions() }
    )

    private class Perm(val icon: String, val title: String, val note: String, val ok: Boolean, val open: () -> Unit)

    private fun permissionItems(): List<Perm> {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        return listOf(
            Perm("📱", "מסך מלא בהתראה", when {
                    !overlayAllowed() -> "חסר: הצגה מעל אפליקציות אחרות"
                    !fullScreenIntentAllowed() -> "חסר: התראות במסך מלא"
                    else -> "התרעה נפתחת על כל המסך גם כשהטלפון בשימוש"
                }, fullScreenOk()) { openFullScreenSettings() },
            Perm("🔋", "חיסכון בסוללה", "האפליקציה לא נעצרת ברקע",
                pm.isIgnoringBatteryOptimizations(packageName)) { openBatterySettings() },
            Perm("🔔", "התראות בטלפון", "התראות האפליקציה מופעלות",
                nm.areNotificationsEnabled()) { openNotificationSettings() },
            Perm("🔕", "נא לא להפריע", "מאפשר לעקוף נא לא להפריע בזמן התרעה",
                dndAccess()) { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        )
    }

    /** מסך הרשאות: כל הרשאה עם מצב, לחיצה פותחת את ההגדרה בטלפון */
    private fun showPermissions() = ouiPage("הרשאות") { body, _ ->
        val card = ouiCard()
        permissionItems().forEachIndexed { i, p ->
            if (i > 0) ouiDivider(card)
            ouiRow(card, p.title, sub = p.note, subColor = C.MUTED,
                end = if (p.ok) "תקין" else "דורש טיפול", endColor = if (p.ok) C.GREEN else C.RED) { p.open() }
        }
        ouiAdd(body, card)
        ouiNote(body, "לחיצה על הרשאה פותחת את ההגדרה בטלפון")
    }

    private fun generalRows(): List<Row> {
        return listOf(
            // בדיקת עדכונים + בטא + מה חדש - במסך אחד
            Row("⬆️", "בדיקת עדכונים", { "v" + Updater.versionLabel(this) }) { showUpdates() },
            Row("ℹ️", "אודות", { "" }) { showAbout() }
        )
    }

    // ---- חיווים ----

    /** חיווי: אייקון למעלה, טקסט מתחת */
    private fun indicator(icon: Int, onClick: (() -> Unit)?) = text("", 12f, C.TEXT).apply {
        setIndIcon(this, icon)
        // האייקון גדל ב-15%, והרווח שמתחת לו קטן באותה מידה - גובה השורה לא משתנה
        compoundDrawablePadding = (dp(4) - (dp(18) * 0.15f).toInt()).coerceAtLeast(0)
        gravity = Gravity.CENTER
        if (onClick != null) setOnClickListener { onClick() }
    }

    /** אייקון החיווי - מעט גדול מהגודל המקורי */
    private fun setIndIcon(v: TextView, icon: Int) {
        val d = getDrawable(icon)?.mutate() ?: return
        d.setBounds(0, 0, (d.intrinsicWidth * 1.45f).toInt(), (d.intrinsicHeight * 1.45f).toInt())
        v.setCompoundDrawablesRelative(null, d, null, null)
    }

    /** האייקון בצבע המצב, הטקסט תמיד לבן-אפור */
    private fun setInd(v: TextView, icon: Int, label: String, color: Int) {
        setIndIcon(v, icon)
        v.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
        v.text = label
    }

    /** סוג החיבור: וויפי / נתונים / אחר, והאם יש אינטרנט בפועל */
    private fun network(): Pair<String, Boolean>? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return null) ?: return null
        val type = when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> "other"
        }
        return type to caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun locationOn(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        return hasLocationPerm() && lm.isLocationEnabled
    }

    private fun updateFeed() {
        val t = FeedText.latest(this) ?: "🇮🇱 פיד ארצי · אין התראות בארץ ב־30 הדקות האחרונות"
        if (t != feedText) {           // לא לאפס את הגלילה של הפס בכל שנייה
            feedText = t
            feedLine.text = t
            feedLine.setTextColor(if (t.startsWith("🇮🇱")) C.MUTED else C.TEXT)
        }
    }

    private fun updateIndicators() {
        updateFeed()
        if (Prefs.enabled(this)) setInd(protInd, R.drawable.ic_shield_ind, "מוגן", C.GREEN)
        else setInd(protInd, R.drawable.ic_shield_ind_off, "לא מוגן", C.RED)
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
            net.first == "cell" -> setInd(netInd, R.drawable.ic_globe, "נתונים ניידים", if (net.second) C.GREEN else C.ORANGE)
            else -> setInd(netInd, R.drawable.ic_globe, "מחובר", if (net.second) C.GREEN else C.ORANGE)
        }
        // מיקום: מתחתיו דיוק המיקום במטרים
        val accM = if (weatherAccuracy > 0) weatherAccuracy
            else (try { Weather.lastLocation(this)?.accuracy?.let { Math.round(it) } } catch (_: Exception) { null } ?: -1)
        val acc = if (accM > 0) "$accM מ׳" else "—"
        if (locationOn()) setInd(locInd, R.drawable.ic_location, acc, if (hasPrecise()) C.GREEN else C.ORANGE)
        else setInd(locInd, R.drawable.ic_location, "כבוי", C.MUTED)

        if (!Prefs.enabled(this)) {
            setInd(srvInd, R.drawable.ic_antenna, "לא מחובר", C.MUTED); blinkServer(false)
        } else {
            val up = SourceHealth.upCount(); val all = SourceHealth.total()
            val connecting = up < all && SourceHealth.anyConnecting()   // אחרי הפעלה/עדכון - עוד מתחברים
            val color = when {
                connecting -> C.ORANGE
                up == 0 -> C.RED; up < all / 2 -> C.ORANGE; else -> C.GREEN
            }
            val label = when {
                connecting -> "מתחבר…"
                up > 0 -> "מחובר לשרת"
                else -> "לא מחובר"
            }
            setInd(srvInd, R.drawable.ic_antenna, label, color)
            blinkServer(connecting)
        }
    }

    /** "מתחבר…" מהבהב עד שהמקורות מחוברים */
    private var srvBlink: android.animation.ObjectAnimator? = null
    private fun blinkServer(on: Boolean) {
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

    private fun showNetworkInfo() {
        val net = network()
        val type = when (net?.first) { "wifi" -> " (Wi-Fi)"; "cell" -> " (נתונים ניידים)"; else -> "" }
        val msg = when {
            net == null -> "לא מחובר לרשת – התראות לא יגיעו"
            !net.second -> "מחובר לרשת$type, אבל אין אינטרנט"
            else -> "מחובר לרשת$type ✓"
        }
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun onLocationIndicator() {
        when {
            !hasLocationPerm() || !hasPrecise() -> onWeatherClick()
            !locationOn() -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else -> {
                android.widget.Toast.makeText(this, "מעדכן מיקום…", android.widget.Toast.LENGTH_SHORT).show()
                refreshNearby()
                updateWeather(force = true)
            }
        }
    }

    private fun showSourcesInfo() {
        fun title() = "שרתי התרעות · ${SourceHealth.upCount()}/${SourceHealth.total()} מחוברים"
        fun body() = if (Prefs.enabled(this)) SourceHealth.report() else "ההאזנה כבויה"
        val d = AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle(title())
            .setMessage(body())
            .setPositiveButton("סגור", null)
            .show()
        // מתעדכן בלייב כל שנייה כל עוד החלון פתוח
        val live = object : Runnable {
            override fun run() {
                if (!d.isShowing) return
                d.setTitle(title()); d.setMessage(body())
                ui.postDelayed(this, 1000)
            }
        }
        ui.postDelayed(live, 1000)
        d.setOnDismissListener { ui.removeCallbacks(live) }
    }

    // ---- מה חדש ----

    /** אחרי עדכון: פעם אחת, כל מה שהשתנה מאז הגרסה הקודמת שהייתה מותקנת */
    private fun showWhatsNewIfUpdated() {
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
    private fun whatsNewText(sinceVersion: String?): String? {
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

    private fun showWhatsNew(sinceVersion: String?) {
        val text = whatsNewText(sinceVersion) ?: return
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("🆕 מה חדש")
            .setMessage(text)
            .setPositiveButton("הבנתי", null)
            .show()
    }

    /** מסך עדכונים: גרסה מותקנת + בדיקה, גרסאות בטא (למי שנפתח לו), ומה חדש */
    private fun showUpdates() = ouiPage("עדכונים",
        // אותה בדיקה בדיוק כמו לחיצה על הגרסה בשורת החיווי
        button = "בדוק אם יש עדכונים" to { Updater.check(this, silent = false) }) { body, rebuild ->
        val ver = ouiCard()
        ouiRow(ver, "גרסה מותקנת", end = "v" + Updater.versionLabel(this), endColor = C.MUTED)
        ouiDivider(ver)
        // מתי האפליקציה עודכנה לאחרונה בטלפון
        val updatedAt = try {
            java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US)
                .format(java.util.Date(packageManager.getPackageInfo(packageName, 0).lastUpdateTime))
        } catch (_: Exception) { "" }
        ouiRow(ver, "עודכן לאחרונה", end = updatedAt, endColor = C.MUTED)
        ouiAdd(body, ver)
        ouiNote(body, "מהעדכון השני ההתקנה אוטומטית, בלי ללחוץ התקן")

        // גרסאות בטא - רק למי שפתח אותן (7 לחיצות על הגרסה)
        if (Prefs.betaUnlocked(this) || Prefs.betaUpdates(this)) {
            val on = Prefs.betaUpdates(this)
            val beta = ouiCard()
            ouiRow(beta, "גרסאות בטא", sub = if (on) "מקבל גרסאות ניסיון" else "כבוי", on = on) {
                Prefs.setBetaUpdates(this, !on)
                Prefs.setSkippedVersion(this, "")
                if (Prefs.enabled(this)) AlertService.reloadPush(this)
                if (on) Prefs.setBetaUnlocked(this, false)   // כיבוי - האפשרות חוזרת להיות מוסתרת
                else Updater.check(this, silent = false)
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

    private fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    /** התרעות לפי מיקום: צריך מיקום מדויק. מיקום ברקע נקרא דרך השירות */
    private fun toggleNearMe() {
        val on = !Prefs.nearMe(this)
        if (on && !hasPrecise()) {
            requestPermissions(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION), 3)
            return
        }
        Prefs.setNearMe(this, on)
        if (on) {
            // זיהוי ראשון מיד, בלי לחכות לשירות
            Weather.freshLocation(this) { loc ->
                if (loc != null) Thread {
                    val list = AreaData.nearby(this, loc.latitude, loc.longitude)
                    Prefs.setNearbyAreas(this, list)
                    runOnUiThread { refresh() }
                }.start()
            }
            // השירות צריך לעלות מחדש כדי לקבל גישה למיקום ברקע
            if (Prefs.enabled(this)) { stopService(Intent(this, AlertService::class.java)); AlertService.start(this, manual = true) }
        }
        refresh()
    }

    private val SOUND_NAMES = arrayOf("ירי / חדירה", "התראה מקדימה", "האירוע הסתיים")

    private fun showSounds() {
        val items = Array(3) { "${SOUND_NAMES[it]}: ${Sounds.label(this, it)}" }
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("🔊 צלילים")
            .setItems(items) { _, level -> chooseSound(level) }
            .setPositiveButton("סגור", null)
            .show()
    }

    private var preview: android.media.Ringtone? = null

    /** השמעת דוגמה של 4 שניות */
    private fun previewSound(level: Int, value: String) {
        preview?.stop()
        val uri = Sounds.uri(this, value, level) ?: return
        preview = android.media.RingtoneManager.getRingtone(this, uri)?.apply { play() }
        ui.postDelayed({ preview?.stop() }, 4000)
    }

    private fun chooseSound(level: Int) {
        val values = arrayOf(Sounds.APP_SIREN, Sounds.APP_BEEP, Sounds.DEVICE, "pick", Sounds.SILENT)
        val names = arrayOf("🚨 צופר האפליקציה", "🔔 צפצוף האפליקציה", "📱 צליל הטלפון",
            "🎵 בחירה מצלילי הטלפון…", "🔇 ללא צליל")
        val cur = values.indexOf(Sounds.effective(this, level)).let { if (it < 0) 3 else it }
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("צליל – ${SOUND_NAMES[level]}")
            .setSingleChoiceItems(names, cur) { d, i ->
                if (values[i] == "pick") { d.dismiss(); preview?.stop(); pickSound(level); return@setSingleChoiceItems }
                Prefs.setSound(this, level, values[i])
                previewSound(level, values[i])
            }
            .setPositiveButton("שמור") { _, _ -> preview?.stop(); showSounds() }
            .show()
    }

    private fun pickSound(level: Int) {
        val type = if (level == AlertService.LEVEL_ALERT) android.media.RingtoneManager.TYPE_ALARM
                   else android.media.RingtoneManager.TYPE_NOTIFICATION
        val cur = Prefs.sound(this, level)
        val i = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                android.media.RingtoneManager.TYPE_ALARM or android.media.RingtoneManager.TYPE_NOTIFICATION or
                    android.media.RingtoneManager.TYPE_RINGTONE)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "צליל – ${SOUND_NAMES[level]}")
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                android.media.RingtoneManager.getDefaultUri(type))
        if (cur.isNotEmpty() && cur != "silent") {
            i.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, 100 + level)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode in 100..102 && resultCode == RESULT_OK && data != null) {
            val level = requestCode - 100
            @Suppress("DEPRECATION")
            val uri = data.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val default = android.media.RingtoneManager.getDefaultUri(
                if (level == AlertService.LEVEL_ALERT) android.media.RingtoneManager.TYPE_ALARM
                else android.media.RingtoneManager.TYPE_NOTIFICATION)
            Prefs.setSound(this, level, when {
                uri == null -> Sounds.SILENT
                uri == default -> Sounds.DEVICE
                else -> uri.toString()
            })
            showSounds()
        }
    }

    private var testSpeaker: Speaker? = null

    /** הקראה: הפעלה/כיבוי ודוגמה */
    private fun dndAccess() =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
    private fun showAbout() {
        val msg = """
            |צבע אדום · גרסה ${Updater.versionLabel(this)}
            |התראות פיקוד העורף בזמן אמת, גם כשהאפליקציה סגורה והמסך כבוי.
            |
            |📡 מקורות (${SourceHealth.total()}):
            |${SourceHealth.NAMES.values.joinToString("\n") { "• $it" }}
            |ההתראה מוצגת מהמקור שהגיע הכי מהר, פעם אחת בלבד.
            |
            |📜 רישיונות:
            |• מפה: Leaflet (BSD-2)
            |• גבולות אזורים וזמני הגעה: oref_alert (MIT)
            |• רשת: OkHttp (Apache 2.0)
            |• מזג אוויר: Open-Meteo · שמות מקומות: OpenStreetMap
            |
            |⚠️ הבהרה:
            |• זו אפליקציה פרטית ולא רשמית. היא אינה קשורה לפיקוד העורף, לצה"ל או לכל גוף ממשלתי.
            |• המידע מגיע ממקורות ציבוריים, וייתכנו עיכובים, תקלות או התראות חסרות.
            |• האפליקציה משלימה ואינה מחליפה את האפליקציה הרשמית של פיקוד העורף, את הצופרים ואת ההנחיות הרשמיות.
            |• השימוש באחריות המשתמש בלבד. ההנחיות הרשמיות באתר פיקוד העורף גוברות תמיד.
            |
            |נבנתה על ידי טל ששון
            |© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} כל הזכויות שמורות
        """.trimMargin()
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("ℹ️ אודות")
            .setMessage(msg)
            .setPositiveButton("סגור", null)
            .show()
    }

    /** עמוד הקראה: הדלקה/כיבוי והשמעת דוגמה */
    private fun showSpeech() = ouiPage("הקראה") { body, rebuild ->
        val c = ouiCard()
        ouiRow(c, "הקראה בקול", on = Prefs.speakAlerts(this)) {
            Prefs.setSpeakAlerts(this, !Prefs.speakAlerts(this)); rebuild() }
        ouiDivider(c)
        ouiRow(c, "השמע דוגמה", sub = "ירי רקטות וטילים באזורים שלך") {
            // דוגמה בקול של ההתראות (הקלטות "אבר")
            val sp = testSpeaker ?: Speaker(this).also { testSpeaker = it }
            val mine = Prefs.cities(this) + Prefs.nearbyAreas(this)
            val areas = mine.ifEmpty { listOf("תל אביב - מרכז העיר") }.take(3)
            sp.speakAlert("ירי רקטות וטילים", areas, AreaData.shelterSeconds(this, areas) ?: 90) {}
        }
        ouiAdd(body, c)
        ouiNote(body, "מקריא סוג, אזורים וזמן להגעה – ואז הצליל. במצב שבת ההקראה פועלת תמיד")
    }

    /** רטט: בחירה לכל סוג התראה, עם דוגמה בכל לחיצה */
    private fun showVibes() {
        val items = Array(3) { "${SOUND_NAMES[it]}: ${Vibes.NAMES[Prefs.vibe(this, it)]}" }
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("📳 רטט")
            .setItems(items) { _, level ->
                AlertDialog.Builder(this, dlg())
                    .setIcon(R.mipmap.ic_launcher)
                    .setTitle("רטט – ${SOUND_NAMES[level]}")
                    .setSingleChoiceItems(Vibes.NAMES, Prefs.vibe(this, level)) { _, i ->
                        Prefs.setVibe(this, level, i)
                        Vibes.play(this, i)          // דוגמה מיידית
                    }
                    .setPositiveButton("שמור") { _, _ -> showVibes() }
                    .show()
            }
            .setPositiveButton("סגור", null)
            .show()
    }

    /** שעות שקט: רק התראה מקדימה וסיום אירוע מושתקים. ירי תמיד נשמע. */
    /** עמוד שעות שקט: הדלקה/כיבוי, שעת התחלה וסיום */
    private fun showQuietHours() = ouiPage("שעות שקט") { body, rebuild ->
        val on = Prefs.quietOn(this)
        val sw = ouiCard()
        ouiRow(sw, "שעות שקט", on = on) { Prefs.setQuietOn(this, !on); rebuild() }
        ouiAdd(body, sw)
        ouiNote(body, "בשעות האלה התרעה מקדימה וסיום אירוע מגיעים בשקט. ירי תמיד נשמע")
        val t = ouiCard()
        ouiRow(t, "התחלה", end = hhmm(Prefs.quietFrom(this)), endBig = true) {
            pickTime(Prefs.quietFrom(this)) { Prefs.setQuiet(this, it, Prefs.quietTo(this)); rebuild() } }
        ouiDivider(t)
        ouiRow(t, "סיום", end = hhmm(Prefs.quietTo(this)), endBig = true) {
            pickTime(Prefs.quietTo(this)) { Prefs.setQuiet(this, Prefs.quietFrom(this), it); rebuild() } }
        ouiAdd(body, t)
        ouiNote(body, "מצב שבת עוקף את שעות השקט")
    }

    /** בחירת שעה - חלון השעה של הטלפון (בסמסונג: גלגלים, "ביטול | סיום") */
    private fun pickTime(cur: Int, done: (Int) -> Unit) {
        android.app.TimePickerDialog(this, dlg(), { _, h, m -> done(h * 60 + m) },
            cur / 60, cur % 60, true).show()
    }

    private fun weatherLabel(min: Int) = when (min) {
        1 -> "כל דקה"
        60 -> "כל שעה"
        else -> "כל $min דקות"
    }

    /** עמוד תאריך: הצגה + סגנון היום - כל שינוי נראה מיד בשעון */
    private fun chooseDayStyle(done: () -> Unit) = ouiPage("תאריך", onClose = done) { body, rebuild ->
        val show = ouiCard()
        ouiRow(show, "הצגת תאריך", on = Prefs.showDate(this)) {
            Prefs.setShowDate(this, !Prefs.showDate(this)); updateClock(); refresh(); rebuild() }
        ouiAdd(body, show)
        ouiNote(body, "התאריך מוצג מתחת לשעון במסך הראשי")
        val styles = ouiCard()
        styles.addView(text("סגנון יום", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
        TimeFormat.dayOptions().forEachIndexed { i, label ->
            if (i > 0) ouiDivider(styles)
            ouiRow(styles, label, radio = Prefs.dayStyle(this) == i) {
                Prefs.setDayStyle(this, i); updateClock(); refresh(); rebuild() }
        }
        ouiAdd(body, styles)
    }

    /** עמוד מזג אוויר: הצגה + תדירות עדכון */
    private fun chooseWeatherInterval(done: () -> Unit) = ouiPage("מזג אוויר", onClose = done) { body, rebuild ->
        val show = ouiCard()
        ouiRow(show, "הצגת מזג אוויר", on = Prefs.showWeather(this)) {
            Prefs.setShowWeather(this, !Prefs.showWeather(this)); updateWeather(force = true); rebuild() }
        ouiAdd(body, show)
        ouiNote(body, "טמפרטורה ומצב מזג האוויר לפי המיקום, בכרטיס השעון")
        val opts = intArrayOf(1, 2, 5, 10, 15, 30, 60)
        val freq = ouiCard()
        freq.addView(text("תדירות עדכון", 16f, C.TEXT).apply { setPadding(dp(18), dp(14), dp(18), dp(4)) })
        opts.forEachIndexed { i, m ->
            if (i > 0) ouiDivider(freq)
            ouiRow(freq, weatherLabel(m), radio = Prefs.weatherMinutes(this) == m) {
                Prefs.setWeatherMinutes(this, m); rebuild() }
        }
        ouiAdd(body, freq)
        ouiNote(body, "עדכון תכוף יותר משתמש קצת יותר בסוללה ובאינטרנט")
    }

    /** עמוד שעון: שניות ונקודתיים מהבהבות */
    private fun showClockPage() = ouiPage("שעון") { body, rebuild ->
        val c = ouiCard()
        ouiRow(c, "הצגת שניות", on = Prefs.showSeconds(this)) {
            Prefs.setShowSeconds(this, !Prefs.showSeconds(this)); refresh(); updateClock(); rebuild() }
        ouiDivider(c)
        ouiRow(c, "נקודתיים מהבהבות", on = Prefs.blinkColon(this)) {
            Prefs.setBlinkColon(this, !Prefs.blinkColon(this)); updateClock(); rebuild() }
        ouiAdd(body, c)
        ouiNote(body, "השינוי נראה מיד בשעון במסך הראשי")
    }


    // ---- רכיבים ----

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** קטע במסך - בלי רקע (עיצוב נקי) */
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(12), dp(2), dp(4))
    }

    private fun chip(label: String, removable: Boolean, onRemove: () -> Unit) =
        text(if (removable) "$label  ×" else label, 13f, C.TEXT).apply {
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(C.CHIP) }
            if (removable) setOnClickListener { onRemove() }
        }

    // ---- הרשאות והגדרות מערכת ----

    @SuppressLint("BatteryLife")
    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) openBatterySettings()
    }

    @SuppressLint("BatteryLife")
    /**
     * סוללה: אם עוד לא מוחרגת - בקשת המערכת. אם כבר מוחרגת (או שהבקשה לא נפתחת, כמו בחלק ממכשירי סמסונג)
     * - פרטי האפליקציה, שם "סוללה" ← "ללא הגבלה". כך לחיצה תמיד פותחת משהו
     */
    private fun openBatterySettings() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")))
                return
            } catch (_: Exception) { }
        }
        openAppDetails()
    }

    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
        } catch (_: Exception) { }
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    /** בכל פתיחה - מיקום עדכני ל"התרעות לפי מיקום" (בנוסף לעדכון של השירות כל 2 דקות) */
    private fun refreshNearby() {
        if (!Prefs.nearMe(this) || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        Weather.freshLocation(this) { got ->
            val loc = got?.let { Weather.chooseLocation(it) }   // מיקום גס לא דורס מיקום מדויק
            if (loc != null) Thread {
                try {
                    Prefs.addVisits(this, AreaData.areasAt(this, loc.latitude, loc.longitude, 0.0), System.currentTimeMillis())
                    val list = AreaData.nearby(this, loc.latitude, loc.longitude)
                    if (list.isNotEmpty()) Prefs.setNearbyAreas(this, list)
                } catch (_: Exception) { }
            }.start()
        }
    }

    private var lockAsked = false
    /** אנדרואיד 14+: בלי ההרשאה הזו ההתראה לא נדלקת על מסך נעילה */
    private fun askLockScreen() {
        if (lockAsked || Build.VERSION.SDK_INT < 34 || !Prefs.enabled(this)) return
        // עם "הצגה מעל אפליקציות" המסך נדלק ומוצג גם בלי ההרשאה הזו - לא מציקים
        if (Settings.canDrawOverlays(this)) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.canUseFullScreenIntent()) return
        lockAsked = true
        android.app.AlertDialog.Builder(this, Prefs.dialogTheme(this))
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("התראה על מסך נעילה")
            .setMessage("כדי שההתראה תידלק מיד כשהמסך כבוי – צריך לאשר \"התראות במסך מלא\".")
            .setPositiveButton("אישור") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:$packageName")))
            }
            .setNegativeButton("לא עכשיו", null)
            .show()
    }

    /**
     * "הצגה מעל אפליקציות אחרות". canDrawOverlays לפעמים מחזיר "לא" גם כשמופעל (במיוחד מיד אחרי
     * שחוזרים מההגדרות) - לכן בודקים גם ישירות בהרשאות המערכת (AppOps)
     */
    private fun overlayAllowed(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        return try {
            val ops = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    /** הרשאת התראה במסך מלא (אנדרואיד 14 ומעלה) */
    private fun fullScreenIntentAllowed(): Boolean =
        Build.VERSION.SDK_INT < 34 || (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()

    /** מסך מלא תקין רק כששתי ההרשאות מופעלות */
    private fun fullScreenOk() = overlayAllowed() && fullScreenIntentAllowed()

    private fun openFullScreenSettings() {
        // קודם מה שחסר: הצגה מעל אפליקציות, ואז התראה במסך מלא.
        // אם הכל מופעל - חוזרים למסך "הצגה מעל אפליקציות" (ולא להגדרות ההתראות)
        if (!overlayAllowed() || fullScreenIntentAllowed()) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:$packageName")))
            return
        }
        startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            .setData(Uri.parse("package:$packageName")))
    }
}

/** פריסה שעוטפת שורות - לתגיות האזורים */
class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = View.MeasureSpec.getSize(widthMeasureSpec)
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            measureChild(c, widthMeasureSpec, heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0; y += rowH + gap; rowH = 0
            }
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > w) {
                x = 0; y += rowH + gap; rowH = 0
            }
            val left = if (rtl) w - x - c.measuredWidth else x
            c.layout(left, y, left + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

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
        }
    }

    private lateinit var circle: LinearLayout
    private lateinit var circleGlow: FrameLayout   // המתג
    private lateinit var switchKnob: View
    private lateinit var circleIcon: View          // הנקודה
    private lateinit var circleLabel: TextView
    private lateinit var circleHint: TextView
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
                    weatherLine.setTextColor(Color.parseColor("#E6FFFFFF"))
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
            updateStatusLine()
            // שאר המסך מתעדכן רק כשמשהו השתנה
            val key = "${Prefs.enabled(this@MainActivity)}|${Prefs.history(this@MainActivity).firstOrNull()?.ts}|" +
                Prefs.nearbyAreas(this@MainActivity).joinToString()   // גם כשהאזורים הקרובים משתנים
            if (key != lastHistoryKey) { lastHistoryKey = key; refresh() }
        } catch (_: Exception) { }
    }

    /** "מוגן · 8/8 מקורות · עדכון אחרון לפני 2 שניות" */
    private fun updateStatusLine() {
        if (!Prefs.enabled(this)) { circleHint.text = "לחיצה להפעלה"; return }
        val last = SourceHealth.lastAny()
        val age = if (last == 0L) -1L else (System.currentTimeMillis() - last) / 1000
        val ago = when {
            age < 0 -> "מתחבר…"
            age <= 1 -> "עכשיו"
            age < 60 -> "לפני $age שניות"
            else -> "לפני ${age / 60} דק׳"
        }
        circleHint.text = "עדכון אחרון: $ago"
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
        // ☰ פותח את התפריט הצידי (התראות אחרונות + הגדרות)
        header.addView(text("☰", 24f, C.TEXT).apply {
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnClickListener { showSettings() }
        })
        header.addView(text("צבע אדום", 22f, C.TEXT, bold = true).apply { setPadding(dp(8), 0, dp(8), 0) })
        // מספר הגרסה עבר לכרטיס החיווים - כאן רק מרווח
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // כפתור בדיקה אחד: בוחרים מקדימה / ירי / סיום
        header.addView(text("🔔", 20f, C.MUTED).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { chooseTest() }
        })
        header.addView(text("🕰", 20f, C.MUTED).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { startActivity(Intent(this@MainActivity, ClockActivity::class.java)) }
        })
        col.addView(header)

        // שעון
        clockTime = text("", 44f, C.TEXT).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        clockDay = text("", 14f, C.TEXT).apply {
            gravity = Gravity.CENTER
        }
        greeting = text("", 16f, C.TEXT).apply { gravity = Gravity.CENTER }
        // כרטיס השעון: רקע גרפיט, הכל ממורכז, טקסט לבן
        val clockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#232526"), Color.parseColor("#414345"))).apply {
                cornerRadius = dp(24).toFloat()
            }
        }
        val white = Color.WHITE
        val soft = Color.parseColor("#E6FFFFFF")
        greeting.setTextColor(soft)
        clockTime.setTextColor(white); clockTime.textSize = 52f
        clockDay.setTextColor(soft)
        clockCard.addView(greeting)
        clockCard.addView(clockTime)
        clockCard.addView(clockDay)
        // כרטיס חיווים מעל השעון: רשת / מיקום / מקורות - שלוש עמודות שוות
        val status = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(7), dp(6), dp(7))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#232526"), Color.parseColor("#414345"))).apply {
                cornerRadius = dp(18).toFloat()
            }
        }
        // בלי לחיצה על החיווים (רק 7 הלחיצות הנסתרות על הגרסה נשארו)
        netInd = indicator(R.drawable.ic_globe, null)
        locInd = indicator(R.drawable.ic_location) { onLocationIndicator() }   // לחיצה: רענון מיקום
        srvInd = indicator(R.drawable.ic_antenna) { showSourcesInfo() }   // לחיצה: רשימת המקורות
        // גרסה: 7 לחיצות מהירות - פותח את אפשרות הבטא (מוסתרת משאר המשתמשים)
        var taps = 0; var lastTap = 0L
        val verInd = indicator(R.drawable.ic_info) {
            val now = System.currentTimeMillis()
            taps = if (now - lastTap < 1500) taps + 1 else 1
            lastTap = now
            if (taps >= 7 && !Prefs.betaUnlocked(this@MainActivity) && Prefs.betaAllowed(this@MainActivity)) {
                Prefs.setBetaUnlocked(this@MainActivity, true)
                android.widget.Toast.makeText(this@MainActivity, "🧪 גרסאות בטא נפתחו (⚙)",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        setInd(verInd, R.drawable.ic_info, "v${Updater.currentVersion(this)}" +
            if (Updater.isBetaBuild(this) && !Updater.currentVersion(this).endsWith("b")) "b" else "", C.MUTED)
        listOf(netInd, locInd, srvInd, verInd).forEachIndexed { i, v ->
            if (i > 0) status.addView(View(this).apply { setBackgroundColor(Color.parseColor("#22FFFFFF")) },
                LinearLayout.LayoutParams(dp(1), dp(26)))
            status.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(GAP) })
        weatherLine = text("", 14f, C.TEXT).apply {
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
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(C.CARD) }
        }

        // עיגול הפעלה/כיבוי
        // כרטיס מוגן: נקודה + "מוגן" + עדכון אחרון, ומתג בצד. לחיצה בכל מקום מפעילה/מכבה
        circleIcon = View(this)
        circleLabel = text("מוגן", 24f, Color.WHITE, bold = true)
        circleHint = text("", 12f, Color.parseColor("#AAAAAA"))
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(circleIcon, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(8) })
        titleRow.addView(circleLabel)
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(titleRow)
        texts.addView(circleHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        switchKnob = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }
        circleGlow = FrameLayout(this).apply {
            addView(switchKnob, FrameLayout.LayoutParams(dp(28), dp(28)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        circle = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = graphite(18)
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(circleGlow, LinearLayout.LayoutParams(dp(62), dp(34)))
            setOnClickListener { toggle() }
        }
        col.addView(circle, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(GAP) })
        // מצב שקט - מוצג רק כשהוא פעיל עכשיו
        quietLine = text("", 12f, C.ORANGE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { showQuietHours() }
        }
        col.addView(quietLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        // כרטיס השעה ומזג האוויר - מתחת לכפתור מוגן
        col.addView(clockCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(GAP) })

        // האזורים שלי
        // אזורי התרעה: אזור לפי מיקום + אזורים נוספים
        val areasCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = graphite(20)
            clipToOutline = true
        }
        chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        areasCard.addView(chips, LinearLayout.LayoutParams(-1, -2, 1f))
        shelterLine = text("", 13f, Color.parseColor("#E6FFFFFF"))
        areasCard.addView(shelterLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        // המסך לא נגלל: אזורי ההתרעה תופסים את מה שנשאר (לכל היותר), והרשימה שבתוכם נגללת
        val areasSlot = FrameLayout(this).apply {
            addView(areasCard, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        col.addView(areasSlot, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(GAP) })

        historyBox = LinearLayout(this)   // לא מוצג - נשאר לשימוש פנימי
        // הכפתור עבר לתפריט הצידי

        // הפיד הארצי - בתחתית המסך
        col.addView(feedLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(GAP) })

        val scroll = FrameLayout(this).apply {
            addView(col, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(scroll)
        askPermissions()
        showWhatsNewIfUpdated()
        // טעינת נתוני האזורים ברקע, כדי שהמסך לא ייתקע בפעם הראשונה
        Thread { try { AreaData.areas(this) } catch (_: Exception) { } }.start()
    }

    private var updateCheckedAt = 0L

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        // "הפעלה" מההתראה "לא מוגן"
        if (intent?.getBooleanExtra("enable", false) == true) {
            intent.removeExtra("enable")
            if (!Prefs.enabled(this)) ui.post { toggle() }
        }
        super.onResume()
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
        super.onPause()
    }

    private fun toggle() {
        val on = !Prefs.enabled(this)
        Prefs.setEnabled(this, on)
        if (on) AlertService.start(this, manual = true) else AlertService.stop(this)
        AlertWidget.updateAll(this)
        refresh()
    }

    private fun refresh() {
        val on = Prefs.enabled(this)
        val color = if (on) C.GREEN else C.OFF
        // עיגול מלא: צבע בהיר במרכז שמתכהה לקצוות, כיתוב לבן מודגש
        val green = Color.parseColor("#30D158")
        circleIcon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(if (on) green else Color.parseColor("#8E8E93"))
        }
        circleLabel.text = if (on) "מוגן" else "כבוי"
        circleGlow.background = GradientDrawable().apply {
            cornerRadius = dp(17).toFloat(); setColor(if (on) green else Color.parseColor("#5A5A5E"))
        }
        // מתג דולק: הידית בצד השמאלי (כמו מתג אנדרואיד בעברית)
        (switchKnob.layoutParams as FrameLayout.LayoutParams).gravity =
            Gravity.CENTER_VERTICAL or (if (on) Gravity.LEFT else Gravity.RIGHT)
        switchKnob.requestLayout()
        updateStatusLine()

        renderChips()
        renderHistory()
    }

    /** אזורי התרעה: פס בולט עם המיקום שלי, ומתחתיו האזורים הנוספים */
    private fun renderChips() {
        chips.removeAllViews()
        val list = Prefs.cities(this)
        val nearOn = Prefs.nearMe(this)
        val near = if (nearOn) Prefs.nearbyAreas(this) else emptyList()
        val muted = Color.parseColor("#AAAAAA")
        val light = Color.parseColor("#E6FFFFFF")

        // מיקום נוכחי
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))   // רקע כמו שאר הכרטיסים (של הכרטיס עצמו)
        }
        top.addView(text("מיקום נוכחי", 15f, muted))
        top.addView(text(if (!nearOn) "כבוי" else near.firstOrNull() ?: "מאתר…", 24f, Color.WHITE, bold = true)
            .apply { setPadding(0, dp(2), 0, 0) })
        val sec = if (nearOn && near.isNotEmpty()) AreaData.shelterSeconds(this, near.take(1)) else null
        if (sec != null) top.addView(text("⏱ זמן התגוננות: ${AreaData.shelterText(sec)}", 15f, light)
            .apply { setPadding(0, dp(2), 0, 0) })
        for (i in 0 until top.childCount) (top.getChildAt(i) as? TextView)?.gravity = Gravity.CENTER
        chips.addView(top)
        chips.addView(View(this).apply { setBackgroundColor(Color.parseColor("#14FFFFFF")) }, LinearLayout.LayoutParams(-1, dp(1)))

        // אזורים נוספים
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        head.addView(text("אזורים נוספים", 12f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(text("+ הוספה", 13f, Color.parseColor("#64B5F6")).apply {
            setPadding(dp(8), dp(2), 0, dp(2)); setOnClickListener { addCity() } })
        bottom.addView(head)
        if (list.isEmpty()) bottom.addView(text("אין אזורים נוספים", 14f, muted).apply { setPadding(0, dp(7), 0, dp(7)) })
        // הרשימה נגללת: עד 4 שורות גלויות, השאר בגלילה
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.forEachIndexed { i, city ->
            if (i > 0) rows.addView(View(this).apply { setBackgroundColor(Color.parseColor("#14FFFFFF")) },
                LinearLayout.LayoutParams(-1, dp(1)))
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            r.addView(text(city, 16f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(text("✕", 15f, muted).apply {
                setPadding(dp(10), 0, dp(4), 0)
                setOnClickListener { Prefs.setCities(this@MainActivity, Prefs.cities(this@MainActivity) - city); refresh() }
            })
            rows.addView(r)
        }
        // הרשימה נגללת כשאין לה מקום במסך
        bottom.addView(ScrollView(this).apply { addView(rows) }, LinearLayout.LayoutParams(-1, -2, 1f))
        if (list.isEmpty() && !nearOn)
            bottom.addView(text("⚠️ אין אזורים – לא יתקבלו התראות", 13f, C.ORANGE).apply { setPadding(0, dp(4), 0, dp(4)) })
        chips.addView(bottom, LinearLayout.LayoutParams(-1, -2, 1f))
        shelterLine.visibility = View.GONE   // הזמן מוצג עכשיו בפס המיקום
    }

    /** במסך הראשי: שורת סיכום של ההתראה האחרונה */
    private fun renderHistory() {
        historyBox.removeAllViews()
        val e = Prefs.history(this).firstOrNull()
        val muted = Color.parseColor("#AAAAAA")
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
    private fun showRecent() = fullPage("📜 התראות אחרונות") { c ->
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(4))
            background = graphite(20)
        }
        c.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val cached = archiveCache
        if (cached != null) { fillHistory(card, Int.MAX_VALUE, cached, national = true); return@fullPage }
        card.addView(text("טוען את כל ההתראות…", 14f, Color.parseColor("#AAAAAA")).apply { setPadding(0, dp(12), 0, dp(12)) })
        Thread {
            val list = try { OrefArchive.fetch() } catch (_: Exception) { emptyList() }
                .ifEmpty { Prefs.feed(this) }   // אין חיבור לארכיון - מה שהאפליקציה שמרה
            runOnUiThread {
                archiveCache = list
                card.removeAllViews()
                fillHistory(card, Int.MAX_VALUE, list, national = true)
            }
        }.start()
    }
    /** נטען פעם אחת לכל פתיחה של האפליקציה */
    private var archiveCache: List<Prefs.Entry>? = null

    private fun fillHistory(historyBox: LinearLayout, count: Int,
                            source: List<Prefs.Entry> = Prefs.history(this), national: Boolean = false) {
        val items = source.take(count)
        val muted = Color.parseColor("#AAAAAA")
        if (items.isEmpty()) {
            historyBox.addView(text("אין התראות עדיין", 14f, muted).apply { setPadding(0, dp(8), 0, dp(10)) })
            return
        }
        items.forEachIndexed { i, e ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                // לחיצה: המפה על האזורים של ההתראה
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, MapActivity::class.java)
                        .putExtra("focus", e.body).putExtra("focusTs", e.ts)
                        .putExtra("focusAll", national))
                }
            }
            val barColor = when (e.level) {
                AlertService.LEVEL_PRE -> Color.parseColor("#F08C00")
                AlertService.LEVEL_END -> C.GREEN
                else -> C.RED
            }
            // פס צבע לפי סוג ההתראה
            row.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(barColor) }
            }, LinearLayout.LayoutParams(dp(4), dp(34)).apply { marginEnd = dp(10) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val shortTitle = if (e.level == AlertService.LEVEL_PRE) "התראה מקדימה" else e.title
            texts.addView(text(shortTitle, 15f, Color.WHITE))
            texts.addView(text(e.body, 12f, muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            // שעה, ומתחת: היום / אתמול / תאריך
            val whenCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            if (e.ts > 0) {
                whenCol.addView(text(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(e.ts)), 12f, muted))
                whenCol.addView(text(dayLabel(e.ts), 12f, muted))
            } else whenCol.addView(text(e.time.take(5), 12f, muted))
            row.addView(whenCol, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            historyBox.addView(row)
            if (i < items.size - 1) historyBox.addView(View(this).apply { setBackgroundColor(Color.parseColor("#14FFFFFF")) },
                LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

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

    /** הוספת אזור: חיפוש (גם חלקי) ברשימת אזורי ההתרעה הרשמיים, לחיצה על תוצאה מוסיפה */
    private fun addCity() {
        val all = AreaData.areas(this).keys().asSequence().toList().sorted()
        val input = EditText(this).apply {
            hint = "חיפוש אזור, לדוגמה: תל אב"
            setSingleLine()
        }
        val results = android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList())
        val listView = android.widget.ListView(this).apply { adapter = results }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(listView, LinearLayout.LayoutParams(-1, dp(300)))
        }
        fun filter(q: String) {
            val t = q.trim()
            val k = Prefs.areaKey(t)
            val mine = Prefs.cities(this)
            val found = if (t.isEmpty()) emptyList() else all.filter {
                it !in mine && (it.contains(t) || (k.isNotEmpty() && Prefs.areaKey(it).contains(k)))
            }.sortedBy { if (it.startsWith(t)) 0 else 1 }.take(200)
            results.clear(); results.addAll(found); results.notifyDataSetChanged()
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { filter(e.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val d = AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("הוספת אזור התרעה")
            .setView(box)
            .setNegativeButton("סגירה", null)
            .show()
        listView.setOnItemClickListener { _, _, pos, _ ->
            val v = results.getItem(pos) ?: return@setOnItemClickListener
            if (v !in Prefs.cities(this)) { Prefs.setCities(this, Prefs.cities(this) + v); refresh() }
            d.dismiss()
        }
    }

    // ---- מסך הגדרות: כפתור לכל קטגוריה, וכל קטגוריה במסך משלה ----

    private class Row(val icon: String, val title: String, val value: () -> String, val action: () -> Unit)

    private fun graphite(radius: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.parseColor("#232526"), Color.parseColor("#414345"))).apply { cornerRadius = dp(radius).toFloat() }

    /** מסך מלא (על כל המסך) עם כותרת וחץ חזרה; build בונה את התוכן, ונבנה מחדש כשחוזרים אליו */
    private fun fullPage(title: String, build: (LinearLayout) -> Unit) {
        val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(30)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("→", 22f, C.MUTED).apply {
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { d.dismiss() }
        })
        head.addView(text(title, 22f, C.TEXT, bold = true))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun rebuild() { content.removeAllViews(); build(content) }
        body.addView(head)
        body.addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        d.setContentView(ScrollView(this).apply {
            setBackgroundColor(C.BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(body)
        })
        rebuild()
        // חוזרים מחלון של הגדרה - מעדכנים את המצבים
        d.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { has -> if (has) rebuild() }
        d.show()
    }

    /** קטגוריה פתוחה במגירת ההגדרות (נשמרת בין פתיחות) */
    private var openCat = -1

    /** הגדרות: מגירה שנפתחת מצד ימין, כל קטגוריה נפתחת/נסגרת במקום */
    private fun chooseShabbat() {
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("🕯 מצב שבת")
            .setSingleChoiceItems(arrayOf("כבוי", "דלוק", "אוטומטי – בשבת ובחג לפי המיקום"), Prefs.shabbatMode(this)) { d, i ->
                Prefs.setShabbatMode(this, i); d.dismiss()
            }
            .setNegativeButton("סגור", null)
            .show()
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

    private fun showSettings() {
        val d = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#99000000")) }
        val panelW = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(18), dp(14), dp(30)) }
        body.addView(text("📜  התראות אחרונות", 16f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(15), dp(14), dp(15))
            background = graphite(18)
            setOnClickListener { d.dismiss(); showRecent() }
        })
        body.addView(text("⚙ הגדרות", 18f, Color.WHITE, bold = true).apply { setPadding(dp(4), dp(20), dp(4), 0) })
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content)
        val panel = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            elevation = dp(12).toFloat()
            isClickable = true
            addView(body)
        }
        root.addView(panel, android.widget.FrameLayout.LayoutParams(panelW, -1, Gravity.END))
        // המגירה לא נכנסת מתחת לשורת הסטטוס / סרגל הניווט של הטלפון
        val statusH = resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it > 0) resources.getDimensionPixelSize(it) else dp(24) }
        body.setPadding(dp(14), statusH + dp(14), dp(14), dp(30))
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            body.setPadding(dp(14), maxOf(insets.systemWindowInsetTop, statusH) + dp(14), dp(14), insets.systemWindowInsetBottom + dp(30))
            insets
        }
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR   // END = ימין
        root.setOnClickListener { close(d, panel, panelW) }

        val cats = listOf(
            Triple("🎨", "תצוגה", { displayRows() }),
            Triple("🔔", "התראות", { alertsRows() }),
            Triple("📱", "הרשאות וטלפון", { phoneRows() }),
            Triple("ℹ️", "כללי", { generalRows() })
        )
        fun rebuild() {
            content.removeAllViews()
            cats.forEachIndexed { idx, (icon, title, rows) ->
                val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = graphite(18) }
                val head = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(15), dp(14), dp(15))
                    setOnClickListener { openCat = if (openCat == idx) -1 else idx; rebuild() }
                }
                head.addView(text(icon, 20f, Color.WHITE), LinearLayout.LayoutParams(dp(36), -2))
                head.addView(text(title, 16f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                head.addView(text(if (openCat == idx) "▲" else "▼", 13f, Color.parseColor("#AAAAAA")))
                card.addView(head)
                if (openCat == idx) rows().forEach { r ->
                    card.addView(View(this).apply { setBackgroundColor(Color.parseColor("#14FFFFFF")) }, LinearLayout.LayoutParams(-1, dp(1)))
                    val row = LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        setOnClickListener { r.action(); rebuild() }
                    }
                    row.addView(text(r.icon, 16f, Color.WHITE), LinearLayout.LayoutParams(dp(32), -2))
                    row.addView(text(r.title, 14f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(text(r.value(), 12f, Color.parseColor("#AAAAAA")))
                    card.addView(row)
                }
                content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
        }
        rebuild()
        d.setContentView(root)
        d.window?.setLayout(-1, -1)
        // חוזרים מחלון של הגדרה - מעדכנים את המצבים
        d.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { has -> if (has) rebuild() }
        d.setOnKeyListener { _, k, e ->
            if (k == android.view.KeyEvent.KEYCODE_BACK && e.action == android.view.KeyEvent.ACTION_UP) { close(d, panel, panelW); true } else false
        }
        panel.translationX = panelW.toFloat()
        d.show()
        panel.animate().translationX(0f).setDuration(220).start()
    }

    private fun close(d: android.app.Dialog, panel: View, w: Int) {
        panel.animate().translationX(w.toFloat()).setDuration(180).withEndAction { d.dismiss() }.start()
    }

    /** כרטיס עם שורות: אייקון, שם, מצב בצד וחץ */
    private fun rowsPage(title: String, rows: () -> List<Row>) {
        fullPage(title) { c ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(2), dp(16), dp(2))
                background = graphite(20)
            }
            val list = rows()
            list.forEachIndexed { i, r ->
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(13), 0, dp(13))
                    setOnClickListener { r.action() }
                }
                row.addView(text(r.icon, 17f, Color.WHITE), LinearLayout.LayoutParams(dp(32), -2))
                row.addView(text(r.title, 15f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text(r.value(), 13f, Color.parseColor("#AAAAAA")))
                row.addView(text("‹", 16f, Color.parseColor("#777777")).apply { setPadding(dp(8), 0, 0, 0) })
                card.addView(row)
                if (i < list.size - 1) card.addView(View(this).apply { setBackgroundColor(Color.parseColor("#14FFFFFF")) },
                    LinearLayout.LayoutParams(-1, dp(1)))
            }
            c.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
    }

    private fun onOff(b: Boolean) = if (b) "פעיל" else "כבוי"

    private fun alertsRows(): List<Row> {
        return listOf(
            Row("📍", "קרוב אליי", { onOff(Prefs.nearMe(this)) }) { toggleNearMe() },
            Row("🔔", "צלילים", { if (AlertService.PHONE_DEFAULTS) "של הטלפון" else "" }) {
                if (AlertService.PHONE_DEFAULTS) openNotificationSettings() else showSounds() },
            Row("📳", "רטט", { if (AlertService.PHONE_DEFAULTS) "של הטלפון" else "" }) {
                if (AlertService.PHONE_DEFAULTS) openNotificationSettings() else showVibes() },
            Row("🗣", "הקראה", { onOff(Prefs.speakAlerts(this)) }) { showSpeech() },
            Row("🌙", "שעות שקט", {
                if (Prefs.quietOn(this)) "${hhmm(Prefs.quietFrom(this))}–${hhmm(Prefs.quietTo(this))}" else "כבוי" }) { showQuietHours() },
            Row("🔕", "עקיפת נא לא להפריע", { dndState() }) { showDnd() },
            Row("🕯", "מצב שבת", { Shabbat.label(Prefs.shabbatMode(this)) }) { chooseShabbat() }
        )
    }

    private fun displayRows(): List<Row> {
        return listOf(
            Row("🎨", "ערכת צבעים", { Prefs.THEME_NAMES[Prefs.themeMode(this)] }) { chooseTheme() },
            Row("🕐", "שעון ותאריך", { "" }) { showClockSettings() },
            Row("🌤", "מזג אוויר", { if (Prefs.showWeather(this)) "" else "מוסתר" }) {
                Prefs.setShowWeather(this, !Prefs.showWeather(this)); updateWeather(force = true) },
            Row("👋", "ברכה", { if (Prefs.showGreeting(this)) "מוצג" else "מוסתר" }) {
                Prefs.setShowGreeting(this, !Prefs.showGreeting(this)); updateClock() }
        )
    }

    private fun phoneRows(): List<Row> {
        return listOf(
            Row("📱", "מסך מלא בהתראה", { if (Settings.canDrawOverlays(this)) "פעיל ✓" else "לא פעיל" }) { openFullScreenSettings() },
            Row("🔋", "חיסכון בסוללה", { "" }) { openBatterySettings() },
            Row("⚙️", "הגדרות התראות בטלפון", { "" }) { openNotificationSettings() }
        )
    }

    private fun generalRows(): List<Row> {
        val beta = Prefs.betaUnlocked(this) || Prefs.betaUpdates(this)
        return listOf(
            Row("⬆️", "בדיקת עדכונים", { "v" + Updater.versionLabel(this) }) { Updater.check(this, silent = false) },
            Row("🆕", "מה חדש", { "" }) { showWhatsNew(sinceVersion = null) },
            Row("ℹ️", "אודות", { "" }) { showAbout() }
        ) + (if (beta) listOf(Row("🧪", "גרסאות בטא", { if (Prefs.betaUpdates(this)) "מקבל ✓" else "כבוי" }) { showBeta() })
             else emptyList())
    }

    private fun chooseTheme() {
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("ערכת צבעים")
            .setSingleChoiceItems(Prefs.THEME_NAMES, Prefs.themeMode(this)) { d, i ->
                d.dismiss()
                if (i != Prefs.themeMode(this)) { Prefs.setThemeMode(this, i); recreate() }
            }
            .show()
    }

    // ---- חיווים ----

    /** חיווי: אייקון למעלה, טקסט מתחת */
    private fun indicator(icon: Int, onClick: (() -> Unit)?) = text("", 12f, Color.parseColor("#E6E6E6")).apply {
        setIndIcon(this, icon)
        compoundDrawablePadding = dp(4)
        gravity = Gravity.CENTER
        if (onClick != null) setOnClickListener { onClick() }
    }

    /** אייקון החיווי - מעט גדול מהגודל המקורי */
    private fun setIndIcon(v: TextView, icon: Int) {
        val d = getDrawable(icon)?.mutate() ?: return
        d.setBounds(0, 0, (d.intrinsicWidth * 1.3f).toInt(), (d.intrinsicHeight * 1.3f).toInt())
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

    private fun showFeed() {
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("🇮🇱 פיד ארצי · 24 שעות")
            .setMessage(FeedText.day(this))
            .setNeutralButton("היסטוריה מלאה") { _, _ ->
                startActivity(Intent(this, HistoryActivity::class.java))
            }
            .setPositiveButton("סגור", null)
            .show()
    }

    private fun updateIndicators() {
        updateFeed()
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
            setInd(srvInd, R.drawable.ic_antenna, "לא מחובר", C.MUTED)
        } else {
            val up = SourceHealth.upCount(); val all = SourceHealth.total()
            val color = when {
                up < all && SourceHealth.anyConnecting() -> C.MUTED   // אחרי הפעלה/עדכון - עוד מתחברים
                up == 0 -> C.RED; up < all / 2 -> C.ORANGE; else -> C.GREEN
            }
            val label = when {
                up > 0 -> "מחובר לשרת"
                SourceHealth.anyConnecting() -> "מתחבר…"
                else -> "לא מחובר"
            }
            setInd(srvInd, R.drawable.ic_antenna, label, color)
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
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("שרתי התרעות · ${SourceHealth.upCount()}/${SourceHealth.total()} מחוברים")
            .setMessage(if (Prefs.enabled(this)) SourceHealth.report() else "ההאזנה כבויה")
            .setPositiveButton("סגור", null)
            .show()
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

    /** sinceVersion = null: רק הגרסה הנוכחית (מההגדרות) */
    private fun showWhatsNew(sinceVersion: String?) {
        val log = try {
            org.json.JSONObject(assets.open("changelog.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: Exception) { return }
        val cur = Updater.currentVersion(this)
        val versions = log.keys().asSequence()
            .filter { v -> if (sinceVersion == null) v == cur else Updater.isNewer(v, sinceVersion) && !Updater.isNewer(v, cur) }
            .sortedWith { a, b -> if (Updater.isNewer(a, b)) -1 else if (a == b) 0 else 1 }
            .toList()
        if (versions.isEmpty()) return
        val text = versions.joinToString("\n\n") { v ->
            val items = log.getJSONArray(v)
            "גרסה $v\n" + (0 until items.length()).joinToString("\n") { "• " + items.getString(it) }
        }
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("🆕 מה חדש")
            .setMessage(text)
            .setPositiveButton("הבנתי", null)
            .show()
    }

    private fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    /** קרוב אליי: צריך מיקום מדויק. מיקום ברקע נקרא דרך השירות */
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
                    val list = AreaData.areasAt(this, loc.latitude, loc.longitude, 1.0).take(4)
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
    private fun dndState() = when {
        !Prefs.dndOverride(this) -> "כבוי"
        dndAccess() -> "פעיל"
        else -> "צריך הרשאה"
    }

    /** בזמן התראה "נא לא להפריע" נכבה לדקה וחוזר למצב הקודם */
    private fun showDnd() {
        val on = Prefs.dndOverride(this)
        val items = arrayOf(
            if (on) "✅ פעיל – לחץ לכיבוי" else "⬜ כבוי – לחץ להפעלה",
            "🔑 הרשאה: " + if (dndAccess()) "מאושרת ✓" else "לא מאושרת – לחץ לאישור",
            "ℹ️ בזמן ירי ו\"התראה מקדימה\" – נא לא להפריע נכבה לדקה כדי שהצליל, הרטט והמסך יעבדו, ואז חוזר לבד"
        )
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("🔕 עקיפת נא לא להפריע")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { Prefs.setDndOverride(this, !on); showDnd() }
                    1 -> startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    else -> showDnd()
                }
            }
            .setPositiveButton("סגור", null)
            .show()
    }

    private fun showBeta() {
        val on = Prefs.betaUpdates(this)
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("🧪 גרסאות בטא")
            .setMessage("קבלת גרסאות ניסיון לפני שהן משוחררות לכולם.\n" +
                "יכולות להיות בהן תקלות. כשיוצאת גרסה רגילה חדשה – היא מותקנת כרגיל.\n\n" +
                "מצב: " + (if (on) "מקבל גרסאות בטא ✓" else "כבוי") +
                "\n\nמזהה מכשיר: ${Prefs.deviceCode(this)}")
            .setPositiveButton(if (on) "כבה" else "הפעל") { _, _ ->
                Prefs.setBetaUpdates(this, !on)
                Prefs.setSkippedVersion(this, "")
                if (Prefs.enabled(this)) AlertService.reloadPush(this)
                // כיבוי - האפשרות חוזרת להיות מוסתרת
                if (on) Prefs.setBetaUnlocked(this, false)
                if (!on) Updater.check(this, silent = false)
            }
            .setNegativeButton("סגור", null)
            .show()
    }

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
            |⚠️ האפליקציה משלימה ואינה מחליפה את האפליקציה הרשמית של פיקוד העורף.
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

    private fun showSpeech() {
        val on = Prefs.speakAlerts(this)
        val items = arrayOf(
            if (on) "✅ פעיל – לחץ לכיבוי" else "⬜ כבוי – לחץ להפעלה",
            "🔊 השמע דוגמה",
            "ℹ️ מקריא סוג, אזורים וזמן להגעה – ואז הצליל"
        )
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("🗣 הקראה בקול")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { Prefs.setSpeakAlerts(this, !on); showSpeech() }
                    1 -> {
                        // דוגמה בקול של ההתראות (הקלטות "אבר")
                        val sp = testSpeaker ?: Speaker(this).also { testSpeaker = it }
                        val mine = Prefs.cities(this) + Prefs.nearbyAreas(this)
                        val areas = mine.ifEmpty { listOf("תל אביב - מרכז העיר") }.take(3)
                        sp.speakAlert("ירי רקטות וטילים", areas, AreaData.shelterSeconds(this, areas) ?: 90) {}
                        showSpeech()
                    }
                    else -> showSpeech()
                }
            }
            .setPositiveButton("סגור", null)
            .show()
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
    private fun showQuietHours() {
        val on = Prefs.quietOn(this)
        // בלי setMessage - באנדרואיד הודעה מסתירה את רשימת האפשרויות
        val items = arrayOf(
            if (on) "✅ פעיל – לחץ לכיבוי" else "⬜ כבוי – לחץ להפעלה",
            "🕚 מתחיל ב־${hhmm(Prefs.quietFrom(this))} – לחץ לשינוי",
            "🕖 נגמר ב־${hhmm(Prefs.quietTo(this))} – לחץ לשינוי",
            "ℹ️ מקדימה וסיום יגיעו בשקט. ירי תמיד נשמע."
        )
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("🌙 שעות שקט")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        Prefs.setQuietOn(this, !on)
                        android.widget.Toast.makeText(this,
                            if (!on) "שעות שקט הופעלו" else "שעות שקט כובו",
                            android.widget.Toast.LENGTH_SHORT).show()
                        showQuietHours()
                    }
                    1 -> pickTime(Prefs.quietFrom(this)) { Prefs.setQuiet(this, it, Prefs.quietTo(this)); showQuietHours() }
                    2 -> pickTime(Prefs.quietTo(this)) { Prefs.setQuiet(this, Prefs.quietFrom(this), it); showQuietHours() }
                    else -> showQuietHours()
                }
            }
            .setPositiveButton("סגור", null)
            .show()
    }

    private fun pickTime(cur: Int, done: (Int) -> Unit) {
        android.app.TimePickerDialog(this, dlg(), { _, h, m -> done(h * 60 + m) },
            cur / 60, cur % 60, true).show()
    }

    private fun weatherLabel(min: Int) = when (min) {
        1 -> "כל דקה"
        60 -> "כל שעה"
        else -> "כל $min דקות"
    }

    /** הגדרות שעה ותאריך - כל שינוי נראה מיד בשעון */
    private fun showClockSettings() {
        val items = arrayOf(
            "ערכת צבעים: " + Prefs.THEME_NAMES[Prefs.themeMode(this)],
            "סגנון יום: ${TimeFormat.dayOptions()[Prefs.dayStyle(this)]}",
            "שניות: " + if (Prefs.showSeconds(this)) "מוצג" else "מוסתר",
            "נקודתיים מהבהבות: " + if (Prefs.blinkColon(this)) "פעיל" else "כבוי",
            "תאריך: " + if (Prefs.showDate(this)) "מוצג" else "מוסתר",
            "מזג אוויר: " + if (Prefs.showWeather(this)) "מוצג" else "מוסתר",
            "עדכון מזג אוויר: " + weatherLabel(Prefs.weatherMinutes(this)),
            "ברכה (בוקר טוב / ערב טוב…): " + if (Prefs.showGreeting(this)) "מוצג" else "מוסתר"
        )
        AlertDialog.Builder(this, dlg())
            .setIcon(R.mipmap.ic_launcher)
            .setTitle("תצוגה")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this, dlg())
                        .setIcon(R.mipmap.ic_launcher)
                        .setTitle("ערכת צבעים")
                        .setSingleChoiceItems(Prefs.THEME_NAMES, Prefs.themeMode(this)) { d, i ->
                            d.dismiss()
                            if (i != Prefs.themeMode(this)) {
                                Prefs.setThemeMode(this, i)
                                recreate()   // בונה את המסך מחדש בצבעים החדשים
                            }
                        }
                        .show()
                    1 -> AlertDialog.Builder(this, dlg())
                        .setIcon(R.mipmap.ic_launcher)
                        .setTitle("סגנון יום")
                        .setSingleChoiceItems(TimeFormat.dayOptions(), Prefs.dayStyle(this)) { d, i ->
                            Prefs.setDayStyle(this, i)
                            updateClock(); refresh(); d.dismiss(); showClockSettings()
                        }
                        .show()
                    2 -> { Prefs.setShowSeconds(this, !Prefs.showSeconds(this)); updateClock(); refresh(); showClockSettings() }
                    3 -> { Prefs.setBlinkColon(this, !Prefs.blinkColon(this)); updateClock(); showClockSettings() }
                    4 -> { Prefs.setShowDate(this, !Prefs.showDate(this)); updateClock(); refresh(); showClockSettings() }
                    5 -> { Prefs.setShowWeather(this, !Prefs.showWeather(this)); updateWeather(force = true); showClockSettings() }
                    7 -> { Prefs.setShowGreeting(this, !Prefs.showGreeting(this)); updateClock(); showClockSettings() }
                    6 -> {
                        val opts = intArrayOf(1, 2, 5, 10, 15, 30, 60)
                        AlertDialog.Builder(this, dlg())
                            .setIcon(R.mipmap.ic_launcher)
                            .setTitle("עדכון מזג אוויר")
                            .setSingleChoiceItems(opts.map { weatherLabel(it) }.toTypedArray(),
                                opts.indexOf(Prefs.weatherMinutes(this))) { d, i ->
                                Prefs.setWeatherMinutes(this, opts[i])
                                d.dismiss(); showClockSettings()
                            }
                            .show()
                    }
                }
            }
            .setPositiveButton("סגור", null)
            .show()
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
    private fun openBatterySettings() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName")))
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    /** בכל פתיחה - מיקום עדכני ל"קרוב אליי" (בנוסף לעדכון של השירות כל 2 דקות) */
    private fun refreshNearby() {
        if (!Prefs.nearMe(this) || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        Weather.freshLocation(this) { loc ->
            if (loc != null) Thread {
                try {
                    Prefs.addVisits(this, AreaData.areasAt(this, loc.latitude, loc.longitude, 0.0), System.currentTimeMillis())
                    val list = AreaData.areasAt(this, loc.latitude, loc.longitude, 1.0).take(4)
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

    private fun openFullScreenSettings() {
        // "הצגה מעל אפליקציות אחרות" - מאפשר לקפוץ במסך מלא גם כשהטלפון בשימוש
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:$packageName")))
                return
            }
        }
        openNotificationSettings()
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

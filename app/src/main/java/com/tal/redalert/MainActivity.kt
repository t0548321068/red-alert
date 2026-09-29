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
            BG = c("#0E0E10", "#F2F2F7")
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
    private lateinit var circleIcon: TextView
    private lateinit var circleLabel: TextView
    private lateinit var circleHint: TextView
    private lateinit var chips: FlowLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var clockTime: TextView
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
            weatherLine.text = "📍 מאתר מיקום מדויק…"
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
                    val (icon, desc) = Weather.describe(w.code, w.isDay)
                    val hint = if (hasPrecise()) "" else " (לחץ למיקום מדויק)"
                    weatherLine.text = listOf("$icon ${w.temp}°", desc, w.place)
                        .filter { it.isNotBlank() }.joinToString(" · ") + hint
                    weatherLine.setTextColor(C.TEXT)
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
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            updateIndicators()
            updateWeather()
            updateStatusLine()
            // שאר המסך מתעדכן רק כשמשהו השתנה
            val key = "${Prefs.enabled(this@MainActivity)}|${Prefs.history(this@MainActivity).firstOrNull()?.ts}|" +
                Prefs.nearbyAreas(this@MainActivity).joinToString()   // גם כשהאזורים הקרובים משתנים
            if (key != lastHistoryKey) { lastHistoryKey = key; refresh() }
            ui.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    /** "מוגן · 8/8 מקורות · עדכון אחרון לפני 2 שניות" */
    private fun updateStatusLine() {
        if (!Prefs.enabled(this)) { circleHint.text = "לחיצה להפעלה"; return }
        val last = SourceHealth.lastAny()
        val age = if (last == 0L) -1L else (System.currentTimeMillis() - last) / 1000
        val ago = when {
            age < 0 -> "מתחבר…"
            age <= 1 -> "עדכון אחרון עכשיו"
            age < 60 -> "עדכון אחרון לפני $age שניות"
            else -> "עדכון אחרון לפני ${age / 60} דק׳"
        }
        circleHint.text = "מוגן · ${SourceHealth.upCount()}/${SourceHealth.total()} מקורות · $ago\nלחיצה לכיבוי"
    }

    private fun updateClock() {
        val now = System.currentTimeMillis()
        clockTime.text = TimeFormat.time(this, now)
        clockDay.text = TimeFormat.dayLine(this, now)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
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
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        // כותרת + גלגל שיניים
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("צבע אדום", 22f, C.TEXT, bold = true))
        header.addView(text("v${Updater.currentVersion(this)}" + if (Updater.isBetaBuild(this)) "β" else "", 13f, C.MUTED).apply {
            setPadding(dp(8), dp(6), 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("🕰", 20f, C.MUTED).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { startActivity(Intent(this@MainActivity, ClockActivity::class.java)) }
        })
        header.addView(text("⚙", 22f, C.MUTED).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { showSettings() }
        })
        col.addView(header)

        // שעון
        clockTime = text("", 44f, C.TEXT).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setOnClickListener { showClockSettings() }
        }
        clockDay = text("", 14f, C.MUTED).apply {
            gravity = Gravity.CENTER
            setOnClickListener { showClockSettings() }
        }
        col.addView(clockTime, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        col.addView(clockDay)
        weatherLine = text("", 14f, C.TEXT).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onWeatherClick() }
            // לחיצה ארוכה: כמה מדויק המיקום שנמדד
            setOnLongClickListener {
                val best = Prefs.bestAccuracy(this@MainActivity)
                val msg = buildString {
                    append(if (weatherAccuracy < 0) "עוד לא נמדד מיקום" else "דיוק עכשיו: כ-$weatherAccuracy מטר")
                    if (best > 0f) append("\nהשיא של המכשיר: כ-${Math.round(best)} מטר")
                    append(if (Prefs.dualFrequency(this@MainActivity)) "\nGPS כפול־תדר: יש ✓" else "\nGPS כפול־תדר: לא זוהה")
                    if (!hasPrecise()) append("\n(הרשאה משוערת בלבד)")
                }
                android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                true
            }
        }
        col.addView(weatherLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // חיווים: רשת / מיקום / שרתי התרעות
        val status = LinearLayout(this).apply { gravity = Gravity.CENTER }
        netInd = indicator(R.drawable.ic_wifi) { showNetworkInfo() }
        locInd = indicator(R.drawable.ic_location) { onLocationIndicator() }
        srvInd = indicator(R.drawable.ic_cloud) { showSourcesInfo() }
        listOf(netInd, locInd, srvInd).forEachIndexed { i, v ->
            status.addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) })
        }
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // פיד ארצי - פס רץ עם כל ההתראות בארץ (בלי צליל)
        feedLine = text("", 12f, C.MUTED).apply {
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(C.CARD) }
            setOnClickListener { showFeed() }
        }
        col.addView(feedLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // עיגול הפעלה/כיבוי
        circleIcon = text("✓", 40f, C.GREEN).apply { gravity = Gravity.CENTER }
        circleLabel = text("מוגן", 16f, C.GREEN, bold = true).apply { gravity = Gravity.CENTER }
        circle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(circleIcon)
            addView(circleLabel)
            setOnClickListener { toggle() }
        }
        val circleWrap = FrameLayout(this).apply {
            addView(circle, FrameLayout.LayoutParams(dp(136), dp(136), Gravity.CENTER))
        }
        col.addView(circleWrap, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        circleHint = text("", 12f, C.MUTED).apply { gravity = Gravity.CENTER }
        col.addView(circleHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        // מצב שקט - מוצג רק כשהוא פעיל עכשיו
        quietLine = text("", 12f, C.ORANGE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { showQuietHours() }
        }
        col.addView(quietLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(20) })

        // האזורים שלי
        val areasCard = card()
        val areasHead = LinearLayout(this)
        areasHead.addView(text("האזורים שלי", 13f, C.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        areasHead.addView(text("+ הוסף", 13f, C.BLUE).apply { setOnClickListener { addCity() } })
        areasCard.addView(areasHead)
        chips = FlowLayout(this, dp(6))
        areasCard.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        shelterLine = text("", 13f, C.TEXT)
        areasCard.addView(shelterLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        col.addView(areasCard)

        // התראות אחרונות
        val histCard = card()
        val histHead = LinearLayout(this)
        histHead.addView(text("התראות אחרונות", 13f, C.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        histHead.addView(text("📜 הכל", 13f, C.BLUE).apply {
            setPadding(0, 0, dp(14), 0)
            setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }
        })
        histHead.addView(text("🗺 מפה", 13f, C.BLUE).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, MapActivity::class.java)) }
        })
        histCard.addView(histHead)
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        histCard.addView(historyBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        col.addView(histCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // כפתורי בדיקה
        val tests = LinearLayout(this)
        listOf(
            Triple("מקדימה", C.ORANGE, "בדקות הקרובות צפויות להתקבל התרעות באזורך"),
            Triple("בדיקת ירי", C.RED, "ירי רקטות וטילים"),
            Triple("סיום", C.GREEN, "האירוע הסתיים")
        ).forEachIndexed { i, (label, color, title) ->
            tests.addView(text(label, 13f, color).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat(); setStroke(dp(1), color)
                }
                setOnClickListener {
                    Prefs.setEnabled(this@MainActivity, true)
                    AlertService.start(this@MainActivity, testTitle = title)
                    if (color != C.RED && Prefs.isQuietNow(this@MainActivity)) {
                        android.widget.Toast.makeText(this@MainActivity,
                            "שעות שקט פעילות – ההתראה נשלחה בשקט, בלי צליל ומסך מלא",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    refresh()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        col.addView(tests, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val scroll = ScrollView(this).apply {
            addView(col)
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

    override fun onResume() {
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
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun toggle() {
        val on = !Prefs.enabled(this)
        Prefs.setEnabled(this, on)
        if (on) AlertService.start(this) else AlertService.stop(this)
        AlertWidget.updateAll(this)
        refresh()
    }

    private fun refresh() {
        val on = Prefs.enabled(this)
        val color = if (on) C.GREEN else C.OFF
        circle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) C.GREEN_BG else C.OFF_BG)
            setStroke(dp(3), color)
        }
        circleIcon.text = if (on) "✓" else "✕"
        circleIcon.setTextColor(color)
        circleLabel.text = if (on) "מוגן" else "כבוי"
        circleLabel.setTextColor(color)
        updateStatusLine()

        renderChips()
        renderHistory()
    }

    private fun renderChips() {
        chips.removeAllViews()
        val list = Prefs.cities(this)
        val near = if (Prefs.nearMe(this)) Prefs.nearbyAreas(this) else emptyList()
        if (Prefs.nearMe(this)) {
            val label = near.firstOrNull()?.let { "📍 קרוב אליי: $it" } ?: "📍 קרוב אליי: מאתר…"
            chips.addView(chip(label, removable = false) { })
        }
        if (list.isEmpty() && near.isEmpty()) {
            chips.addView(chip("כל הארץ", removable = false) {})
        }
        list.forEach { city ->
            chips.addView(chip(city, removable = true) {
                Prefs.setCities(this, Prefs.cities(this) - city)
                refresh()
            })
        }
        // זמן להגעה למרחב מוגן לפי האזורים שלי (הקצר ביותר)
        val sec = AreaData.shelterSeconds(this, list + near)
        shelterLine.text = if (sec != null) "⏱ זמן להגעה למרחב מוגן: ${AreaData.shelterText(sec)}" else ""
        shelterLine.visibility = if (sec != null) View.VISIBLE else View.GONE
    }

    private fun renderHistory() {
        historyBox.removeAllViews()
        val items = Prefs.history(this).take(5)
        if (items.isEmpty()) {
            historyBox.addView(text("אין התראות עדיין", 14f, C.MUTED).apply {
                setPadding(0, dp(6), 0, dp(6))
            })
            return
        }
        items.forEachIndexed { i, e ->
            val row = LinearLayout(this).apply {
                setPadding(0, dp(8), 0, dp(8))
            }
            val dotColor = when (e.level) {
                AlertService.LEVEL_PRE -> C.ORANGE
                AlertService.LEVEL_END -> C.END
                else -> C.RED
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { topMargin = dp(7); marginEnd = dp(10) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val shortTitle = if (e.level == AlertService.LEVEL_PRE) "התראה מקדימה" else e.title
            texts.addView(text(shortTitle, 14f, C.TEXT))
            texts.addView(text(e.body, 12f, C.MUTED).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(if (e.ts > 0) TimeFormat.stamp(this, e.ts) else e.time.take(5), 12f, C.MUTED))
            historyBox.addView(row)
            if (i < items.size - 1) {
                historyBox.addView(View(this).apply { setBackgroundColor(C.CHIP) },
                    LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
    }

    private fun addCity() {
        val input = EditText(this).apply {
            hint = "לדוגמה: תל אביב"
            setSingleLine()
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this, dlg())
            .setTitle("הוספת אזור")
            .setView(wrap)
            .setPositiveButton("הוסף") { _, _ ->
                val v = input.text.toString().trim().replace(",", " ")
                if (v.isNotEmpty() && v !in Prefs.cities(this)) {
                    Prefs.setCities(this, Prefs.cities(this) + v)
                    refresh()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun showSettings() {
        val fullOk = Settings.canDrawOverlays(this)
        val quiet = if (Prefs.quietOn(this))
            "${hhmm(Prefs.quietFrom(this))}–${hhmm(Prefs.quietTo(this))}" else "כבוי"
        val options = arrayOf("תצוגה", "בדיקת עדכונים", "מה חדש",
            "מסך מלא בהתראה: " + if (fullOk) "פעיל ✓" else "לא פעיל – לחץ להפעלה",
            "קרוב אליי (לפי מיקום): " + if (Prefs.nearMe(this)) "פעיל" else "כבוי",
            "צלילים", "רטט", "🗣 הקראה: " + if (Prefs.speakAlerts(this)) "פעיל" else "כבוי",
            "🔕 עקיפת נא לא להפריע: " + dndState(),
            "שעות שקט: $quiet",
            "חיסכון בסוללה", "הגדרות התראות",
            "🧪 גרסאות בטא: " + if (Prefs.betaUpdates(this)) "מקבל ✓" else "כבוי",
            "ℹ️ אודות")
        AlertDialog.Builder(this, dlg())
            .setTitle("הגדרות · גרסה ${Updater.versionLabel(this)}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showClockSettings()
                    1 -> Updater.check(this, silent = false)
                    2 -> showWhatsNew(sinceVersion = null)
                    3 -> openFullScreenSettings()
                    4 -> toggleNearMe()
                    5 -> showSounds()
                    6 -> showVibes()
                    7 -> showSpeech()
                    8 -> showDnd()
                    9 -> showQuietHours()
                    10 -> openBatterySettings()
                    11 -> openNotificationSettings()
                    12 -> showBeta()
                    13 -> showAbout()
                }
            }
            .show()
    }

    // ---- חיווים ----

    private fun indicator(icon: Int, onClick: () -> Unit) = text("", 12f, C.TEXT).apply {
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawablePadding = dp(5)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(C.CARD) }
        setOnClickListener { onClick() }
    }

    private fun setInd(v: TextView, icon: Int, label: String, color: Int) {
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        v.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
        v.text = label
        v.compoundDrawablePadding = if (label.isEmpty()) 0 else dp(5)
        v.setTextColor(color)
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
            quietLine.visibility = View.INVISIBLE
        }
        val net = network()
        when {
            net == null -> setInd(netInd, R.drawable.ic_no_net, "", C.RED)
            net.first == "wifi" -> setInd(netInd, R.drawable.ic_wifi, "", if (net.second) C.GREEN else C.ORANGE)
            net.first == "cell" -> setInd(netInd, R.drawable.ic_cell, "", if (net.second) C.GREEN else C.ORANGE)
            else -> setInd(netInd, R.drawable.ic_wifi, "", if (net.second) C.GREEN else C.ORANGE)
        }
        if (locationOn()) setInd(locInd, R.drawable.ic_location, "", if (hasPrecise()) C.GREEN else C.ORANGE)
        else setInd(locInd, R.drawable.ic_location, "", C.MUTED)

        if (!Prefs.enabled(this)) {
            setInd(srvInd, R.drawable.ic_cloud, "0/${SourceHealth.total()}", C.MUTED)
        } else {
            val up = SourceHealth.upCount(); val all = SourceHealth.total()
            val color = when {
                up < all && SourceHealth.anyConnecting() -> C.MUTED   // אחרי הפעלה/עדכון - עוד מתחברים
                up == 0 -> C.RED; up < all / 2 -> C.ORANGE; else -> C.GREEN
            }
            setInd(srvInd, R.drawable.ic_cloud, "$up/$all", color)
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
            else -> updateWeather(force = true)
        }
    }

    private fun showSourcesInfo() {
        AlertDialog.Builder(this, dlg())
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
                    val list = AreaData.areasAt(this, loc.latitude, loc.longitude).take(8)
                    Prefs.setNearbyAreas(this, list)
                    runOnUiThread { refresh() }
                }.start()
            }
            // השירות צריך לעלות מחדש כדי לקבל גישה למיקום ברקע
            if (Prefs.enabled(this)) { AlertService.stop(this); AlertService.start(this) }
        }
        refresh()
    }

    private val SOUND_NAMES = arrayOf("ירי / חדירה", "התראה מקדימה", "האירוע הסתיים")

    private fun showSounds() {
        val items = Array(3) { "${SOUND_NAMES[it]}: ${Sounds.label(this, it)}" }
        AlertDialog.Builder(this, dlg())
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
            .setTitle("🧪 גרסאות בטא")
            .setMessage("קבלת גרסאות ניסיון לפני שהן משוחררות לכולם.\n" +
                "יכולות להיות בהן תקלות. כשיוצאת גרסה רגילה חדשה – היא מותקנת כרגיל.\n\n" +
                "מצב: " + if (on) "מקבל גרסאות בטא ✓" else "כבוי")
            .setPositiveButton(if (on) "כבה" else "הפעל") { _, _ ->
                Prefs.setBetaUpdates(this, !on)
                Prefs.setSkippedVersion(this, "")
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
        """.trimMargin()
        AlertDialog.Builder(this, dlg())
            .setTitle("ℹ️ אודות")
            .setMessage(msg)
            .setPositiveButton("סגור", null)
            .show()
    }

    private fun showSpeech() {
        val on = Prefs.speakAlerts(this)
        val items = arrayOf(
            if (on) "✅ פעיל – לחץ לכיבוי" else "⬜ כבוי – לחץ להפעלה",
            "🗣 קול: " + if (Prefs.maleVoice(this)) "גבר" else "אישה",
            "🔊 השמע דוגמה",
            "ℹ️ מקריא סוג, אזורים וזמן להגעה – ואז הצליל"
        )
        AlertDialog.Builder(this, dlg())
            .setTitle("🗣 הקראה בקול")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { Prefs.setSpeakAlerts(this, !on); showSpeech() }
                    1 -> {
                        Prefs.setMaleVoice(this, !Prefs.maleVoice(this))
                        testSpeaker?.applyVoice()
                        // השירות יטען את הקול החדש בהתראה הבאה
                        if (Prefs.enabled(this)) AlertService.reloadVoice(this)
                        showSpeech()
                    }
                    2 -> {
                        val sp = testSpeaker ?: Speaker(this).also { testSpeaker = it }
                        // המנוע עולה תוך רגע - מחכים לו
                        ui.postDelayed({
                            if (sp.hebrewOk == false) {
                                android.widget.Toast.makeText(this,
                                    "אין קול עברי בטלפון. הגדרות ← שפה ← המרת טקסט לדיבור ← התקנת עברית",
                                    android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                val mine = Prefs.cities(this) + Prefs.nearbyAreas(this)
                                val areas = mine.ifEmpty { listOf("תל אביב - מרכז העיר") }.take(3)
                                sp.speak(Speaker.textFor("ירי רקטות וטילים", areas,
                                    AreaData.shelterSeconds(this, areas) ?: 90)) {}
                            }
                        }, if (testSpeaker == sp && sp.hebrewOk != null) 0 else 1200)
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
            .setTitle("📳 רטט")
            .setItems(items) { _, level ->
                AlertDialog.Builder(this, dlg())
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
            "ערכת צבעים: " + if (Prefs.darkTheme(this)) "כהה" else "בהירה",
            "סגנון יום: ${TimeFormat.DAY_OPTIONS[Prefs.dayStyle(this)]}",
            "שניות: " + if (Prefs.showSeconds(this)) "מוצג" else "מוסתר",
            "תאריך: " + if (Prefs.showDate(this)) "מוצג" else "מוסתר",
            "מזג אוויר: " + if (Prefs.showWeather(this)) "מוצג" else "מוסתר",
            "עדכון מזג אוויר: " + weatherLabel(Prefs.weatherMinutes(this))
        )
        AlertDialog.Builder(this, dlg())
            .setTitle("תצוגה")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this, dlg())
                        .setTitle("ערכת צבעים")
                        .setSingleChoiceItems(arrayOf("כהה", "בהירה"), if (Prefs.darkTheme(this)) 0 else 1) { d, i ->
                            d.dismiss()
                            val dark = i == 0
                            if (dark != Prefs.darkTheme(this)) {
                                Prefs.setDarkTheme(this, dark)
                                recreate()   // בונה את המסך מחדש בצבעים החדשים
                            }
                        }
                        .show()
                    1 -> AlertDialog.Builder(this, dlg())
                        .setTitle("סגנון יום")
                        .setSingleChoiceItems(TimeFormat.DAY_OPTIONS, Prefs.dayStyle(this)) { d, i ->
                            Prefs.setDayStyle(this, i)
                            updateClock(); refresh(); d.dismiss(); showClockSettings()
                        }
                        .show()
                    2 -> { Prefs.setShowSeconds(this, !Prefs.showSeconds(this)); updateClock(); refresh(); showClockSettings() }
                    3 -> { Prefs.setShowDate(this, !Prefs.showDate(this)); updateClock(); refresh(); showClockSettings() }
                    4 -> { Prefs.setShowWeather(this, !Prefs.showWeather(this)); updateWeather(force = true); showClockSettings() }
                    5 -> {
                        val opts = intArrayOf(1, 2, 5, 10, 15, 30, 60)
                        AlertDialog.Builder(this, dlg())
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

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(C.CARD) }
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
                    val list = AreaData.areasAt(this, loc.latitude, loc.longitude).take(8)
                    if (list.isNotEmpty()) Prefs.setNearbyAreas(this, list)
                } catch (_: Exception) { }
            }.start()
        }
    }

    private var lockAsked = false
    /** אנדרואיד 14+: בלי ההרשאה הזו ההתראה לא נדלקת על מסך נעילה */
    private fun askLockScreen() {
        if (lockAsked || Build.VERSION.SDK_INT < 34 || !Prefs.enabled(this)) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.canUseFullScreenIntent()) return
        lockAsked = true
        android.app.AlertDialog.Builder(this, Prefs.dialogTheme(this))
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

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
        if (!force && System.currentTimeMillis() - weatherAt < 15 * 60 * 1000) return
        weatherAt = System.currentTimeMillis()
        if (force || weatherLine.text.isNullOrBlank()) {
            weatherLine.text = "📍 מאתר מיקום…"
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
    }

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            updateWeather()
            // שאר המסך מתעדכן רק כשמשהו השתנה
            val key = "${Prefs.enabled(this@MainActivity)}|${Prefs.history(this@MainActivity).firstOrNull()?.ts}"
            if (key != lastHistoryKey) { lastHistoryKey = key; refresh() }
            ui.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
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
        header.addView(text("v${Updater.currentVersion(this)}", 13f, C.MUTED).apply {
            setPadding(dp(8), dp(6), 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("⚙", 22f, C.MUTED).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { showSettings() }
        })
        col.addView(header)

        // שעון
        clockTime = text("", 44f, C.TEXT).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
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
                val msg = if (weatherAccuracy < 0) "עוד לא נמדד מיקום"
                    else "דיוק המיקום: כ-$weatherAccuracy מטר" + if (hasPrecise()) "" else " (הרשאה משוערת בלבד)"
                android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                true
            }
        }
        col.addView(weatherLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

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
        col.addView(circleHint, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10); bottomMargin = dp(20)
        })

        // האזורים שלי
        val areasCard = card()
        val areasHead = LinearLayout(this)
        areasHead.addView(text("האזורים שלי", 13f, C.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        areasHead.addView(text("+ הוסף", 13f, C.BLUE).apply { setOnClickListener { addCity() } })
        areasCard.addView(areasHead)
        chips = FlowLayout(this, dp(6))
        areasCard.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(areasCard)

        // התראות אחרונות
        val histCard = card()
        val histHead = LinearLayout(this)
        histHead.addView(text("התראות אחרונות", 13f, C.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
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
    }

    private var updateCheckedAt = 0L

    override fun onResume() {
        super.onResume()
        ui.post(tick)
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
        circleHint.text = if (on) "9 מקורות פעילים · לחיצה לכיבוי" else "לחיצה להפעלה"

        renderChips()
        renderHistory()
    }

    private fun renderChips() {
        chips.removeAllViews()
        val list = Prefs.cities(this)
        if (list.isEmpty()) {
            chips.addView(chip("כל הארץ", removable = false) {})
            return
        }
        list.forEach { city ->
            chips.addView(chip(city, removable = true) {
                Prefs.setCities(this, Prefs.cities(this) - city)
                refresh()
            })
        }
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
        val options = arrayOf("תצוגה", "בדיקת עדכונים",
            "מסך מלא בהתראה: " + if (fullOk) "פעיל ✓" else "לא פעיל – לחץ להפעלה",
            "חיסכון בסוללה", "הגדרות התראות")
        AlertDialog.Builder(this, dlg())
            .setTitle("הגדרות · גרסה ${Updater.currentVersion(this)}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showClockSettings()
                    1 -> Updater.check(this, silent = false)
                    2 -> openFullScreenSettings()
                    3 -> openBatterySettings()
                    4 -> openNotificationSettings()
                }
            }
            .show()
    }

    /** הגדרות שעה ותאריך - כל שינוי נראה מיד בשעון */
    private fun showClockSettings() {
        val items = arrayOf(
            "ערכת צבעים: " + if (Prefs.darkTheme(this)) "כהה" else "בהירה",
            "סגנון יום: ${TimeFormat.DAY_OPTIONS[Prefs.dayStyle(this)]}",
            "שניות: " + if (Prefs.showSeconds(this)) "מוצג" else "מוסתר",
            "תאריך: " + if (Prefs.showDate(this)) "מוצג" else "מוסתר",
            "מזג אוויר: " + if (Prefs.showWeather(this)) "מוצג" else "מוסתר"
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

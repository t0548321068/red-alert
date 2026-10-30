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
    internal var locTapAt = 0L          // לחיצה אחרונה על חיווי המיקום
    internal var lastHistoryKey = ""

    internal fun granted(p: String) =
        checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    internal fun hasLocationPerm() =
        granted(android.Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
    internal fun hasPrecise() = granted(android.Manifest.permission.ACCESS_FINE_LOCATION)

    /** מתעדכן כל 15 דקות, או מיד כשמכריחים (לחיצה על השורה) */
    internal fun updateWeather(force: Boolean = false) {
        if (!Prefs.showWeather(this)) { weatherLine.visibility = View.GONE; return }
        weatherLine.visibility = View.VISIBLE
        if (!hasLocationPerm()) {
            weatherLine.text = "📍 הצג מזג אוויר לפי מיקום"
            weatherLine.setTextColor(C.BLUE)
            return
        }
        if (!force && System.currentTimeMillis() - weatherAt < Prefs.weatherMinutes(this) * 60 * 1000L) return
        weatherAt = System.currentTimeMillis()
        // כשכבר מוצג מזג אוויר - נשאר עד שמגיע החדש (בלי להבהב "מאתר מיקום")
        if (!weatherShown) {
            weatherLine.text = "מאתר מיקום מדויק…"
            weatherLine.setTextColor(C.MUTED)
        }
        // קודם מיקום מהרשת (שנייה-שתיים) - מזג האוויר מופיע מיד; אחר כך ה-GPS המדויק מעדכן את שם המקום
        var shown: android.location.Location? = null
        var gpsDone = false
        Weather.networkLocation(this) { net ->
            if (!gpsDone && net != null && net.accuracy < 2000f) { shown = net; loadWeather(net) }
        }
        Weather.freshLocation(this) { loc ->
            gpsDone = true
            if (loc == null) {
                if (shown == null) {
                    weatherAt = 0L
                    if (!weatherShown) {   // מזג אוויר קודם נשאר על המסך
                        weatherLine.text = "📍 לא נמצא מיקום – לחץ לנסות שוב"
                        weatherLine.setTextColor(C.MUTED)
                    }
                }
                return@freshLocation
            }
            // המיקום מהרשת גס (מאות מטרים) - שם השכונה לפיו יכול להיות של השכונה הסמוכה.
            // כשמגיע המיקום המדויק - טוענים שוב (גם השם), אלא אם המיקום הראשון כבר היה מדויק וקרוב
            val first = shown
            keepAccuracy(loc)
            if (first == null || first.accuracy > 50f || loc.distanceTo(first) > 300f) loadWeather(loc)
        }
    }

    /**
     * חיווי הדיוק יציב: מציג את הדיוק הטוב ביותר שנמדד לאחרונה (נשמר גם אחרי סגירה ופתיחה).
     * מתעדכן רק כשה-GPS מוצא דיוק טוב יותר, כשזזת (מעל 200 מטר), או כשהמדידה האחרונה בת יותר מחצי שעה
     */
    internal fun keepAccuracy(loc: android.location.Location) {
        val m = Math.round(loc.accuracy)
        val (prev, at, pos) = Prefs.lastAcc(this)
        val moved = FloatArray(1).also { android.location.Location.distanceBetween(pos.first, pos.second, loc.latitude, loc.longitude, it) }[0] > 200f
        val stale = System.currentTimeMillis() - at > 30 * 60_000L
        if (prev <= 0 || m <= prev || moved || stale) {
            Prefs.setLastAcc(this, m, loc.latitude, loc.longitude)
            weatherAccuracy = m
        }
    }
    /** מספר ראשוני בפתיחה (נלקח פעם אחת, לא מתעדכן כל שנייה) - עד שיש מדידה */
    internal var initialAcc = -1
    internal fun shownAccuracy(): Int {
        if (weatherAccuracy > 0) return weatherAccuracy
        val (m, _, _) = Prefs.lastAcc(this)
        return if (m > 0) m else initialAcc
    }
    /** בכל פתיחה: מספר ראשוני מיד, ומדידה מדויקת ברקע שמעדכנת אותו (אם לא נמדד בדקה האחרונה) */
    internal fun refreshAccuracy() {
        if (!hasLocationPerm() || !locationOn()) return
        if (initialAcc <= 0) initialAcc = try { Weather.lastLocation(this)?.accuracy?.let { Math.round(it) } } catch (_: Exception) { null } ?: -1
        if (System.currentTimeMillis() - Prefs.lastAcc(this).second < 60_000L) return
        Weather.freshLocation(this) { loc -> if (loc != null) keepAccuracy(loc) }
    }

    internal fun loadWeather(loc: android.location.Location) = Thread {
        val w = try { Weather.fetch(this, loc) } catch (_: Exception) { null }
        runOnUiThread {
            if (w == null) { weatherAt = 0L; return@runOnUiThread }
            val (icon, _) = Weather.describe(w.code, w.isDay)
            val hint = if (hasPrecise()) "" else " (לחץ למיקום מדויק)"
            weatherLine.text = listOf("$icon ${w.temp}°", w.place)
                .filter { it.isNotBlank() }.joinToString(" · ") + hint
            weatherLine.setTextColor(C.SOFT)
            weatherShown = true

        }
    }.start()

    internal fun onWeatherClick() {
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

    internal val ui = Handler(Looper.getMainLooper())
    /**
     * השעון: קל ומהיר, מתוזמן קודם כל - כך שתקלה או עיכוב בשאר המסך לא עוצרים את השניות וההבהוב.
     * רץ כל חצי שנייה (שנייה מלאה + אמצע השנייה להבהוב הנקודתיים).
     */
    internal val tick = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            val ms = System.currentTimeMillis() % 1000
            ui.postDelayed(this, if (ms < 500) 500 - ms else 1000 - ms)
            try { updateClock() } catch (_: Exception) { }
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
    internal fun applyMainTint(level: Int?) {
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
    internal fun updateAlertCard() {
        val j = Prefs.activeAlert(this)
        val level = j?.optInt("level") ?: 0
        val firedAt = j?.optLong("firedAt") ?: 0L
        // בתצוגה "ממוזער" אין כרטיס במסך הראשי כל עוד החלון הקטן פתוח (כפילות); אחרי שנסגר - הכרטיס מופיע
        val mini = Prefs.alertStyle(this) == 3 && !Shabbat.active(this) && MiniOverlay.isShowing()
        if (j == null || mini || AlertUi.expired(level, firedAt) || Prefs.alertDismissed(this) == firedAt) {
            applyMainTint(null)
            if (alertWrap.visibility != View.GONE) alertWrap.visibility = View.GONE
            setScrollMode(false)
            alertCardKey = ""; return
        }
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
        // האייקון לפי השלב (היכנסו → נשארים במרחב המוגן → ממתינים לסיום)
        AlertUi.stageIcon(j.optString("title"), level, j.optInt("shelter", -1), firedAt).let { r ->
            if (r != alertCardIconRes) { alertCardIconRes = r; AlertUi.setIcon(alertCardIcon, r, big = false) } }
        AlertUi.timer(level, j.optInt("shelter", -1), firedAt)?.let { (lbl, t) ->
            alertCardLabel?.text = lbl
            alertCardLabel?.visibility = if (lbl.isEmpty()) View.GONE else View.VISIBLE
            alertCardTimer?.textSize = if (lbl.isEmpty()) 20f else 38f
            alertCardTimer?.text = t
        }
    }

    /** לחיצה על הכרטיס - מסך ההתרעה המלא */
    internal fun openActiveAlert() {
        val j = Prefs.activeAlert(this) ?: return
        startActivity(Intent(this, AlertActivity::class.java)
            .putExtra("title", j.optString("title")).putExtra("body", j.optString("body"))
            .putExtra("level", j.optInt("level")).putExtra("shelter", j.optInt("shelter", -1))
            .putExtra("firedAt", j.optLong("firedAt")).putExtra("source", j.optString("source"))
            .putExtra("mapAreas", j.optString("mapAreas", j.optString("body"))))   // המפה - כמו בהתרעה עצמה
    }

    internal fun updateClock() {
        val now = System.currentTimeMillis()
        clockTime.text = TimeFormat.clock(this, now)
        clockDay.text = TimeFormat.dayLine(this, now)
        val g = Prefs.showGreeting(this)
        greeting.visibility = if (g) View.VISIBLE else View.GONE
        if (g) greeting.text = TimeFormat.greeting(now)
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
        headerIcon(R.drawable.ic_nav_clock) { startActivity(Intent(this@MainActivity, ClockActivity::class.java)) }
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
        netInd = indicator(R.drawable.ic_globe) { showNetworkInfo() }   // לחיצה: הודעה קצרה עם סוג החיבור
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
                android.widget.Toast.makeText(this@MainActivity, "🧪 גרסאות בטא נפתחו (⚙ ← בדיקת עדכונים)",
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
        askPermissions()
        showWhatsNewIfUpdated()
        // אחרי החלפת ערכת צבעים (האפליקציה נטענת מחדש) - חוזרים להגדרות
        if (reopenDisplay) { reopenDisplay = false; ui.post { showSettings() } }
        // טעינת נתוני האזורים ברקע, כדי שהמסך לא ייתקע בפעם הראשונה
        AreaData.preload(this)   // נתוני היישובים ברקע - המסך לא נתקע בפתיחה
    }

    internal var updateCheckedAt = 0L

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        if (::statusRow.isInitialized) { applyStatusRow(); applyTestFab() }
        refreshAccuracy()   // "לפי זמן": מוצגים בכל פתיחה
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

    /** המסך נסגר / נבנה מחדש (סיבוב, החלפת ערכת צבעים) - סוגרים עמודים וטיימרים שלא יישארו תלויים */
    override fun onDestroy() {
        // בלי להפעיל את פעולות הסגירה של העמודים (למשל טעינה מחדש אחרי החלפת ערכת צבעים)
        pages.toList().forEach { try { it.setOnDismissListener(null); it.dismiss() } catch (_: Exception) { } }
        pages.clear()
        ui.removeCallbacksAndMessages(null)
        pulseAnims.forEach { it.cancel() }
        recentList = null; recentSegView = null
        super.onDestroy()
    }

    internal fun toggle() {
        val on = !Prefs.enabled(this)
        Prefs.setEnabled(this, on)
        if (on) AlertService.start(this, manual = true) else AlertService.stop(this)
        AlertWidget.updateAll(this)
        refresh()
    }

    internal fun evenClockGaps() {
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
    internal fun setSweep(on: Boolean) {
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

    internal fun refresh() {
        val sched = Prefs.enabled(this) && Prefs.scheduleOffNow(this)   // פעיל, אבל עכשיו מחוץ לתזמון
        val on = Prefs.enabled(this) && !sched
        val color = if (on) C.GREEN else C.OFF
        // כרטיס כהה, מסגרת ירוקה כשמוגן / אפורה כשלא
        // מוגן: רקע ירוק כהה (בבהיר - ירוק בהיר) עם מסגרת ירוקה; לא מוגן - הכרטיס הרגיל עם מסגרת אפורה
        // מוגן אבל אין אינטרנט: כרטיס כתום - ההתרעות לא יגיעו עד שהרשת תחזור
        val noNet = on && network()?.second != true
        val dark = Prefs.darkTheme(this@MainActivity)
        val off = !Prefs.enabled(this)   // כבוי לגמרי (לא לפי תזמון): כרטיס אדום
        circle.background = graphite(24).apply {
            when {
                noNet -> setColor(Color.parseColor(if (dark) "#33240A" else "#FDEFD9"))
                off -> setColor(Color.parseColor(if (dark) "#3A1214" else "#FBE3E4"))
                on -> setColor(Color.parseColor(if (dark) "#12301A" else "#E3F4E5"))
            }
            setStroke(dp(2), Color.parseColor(if (noNet) "#FF9F0A" else if (on) "#4CAF50" else if (off) "#FF453A" else "#555555")) }
        shieldIcon.setImageResource(if (on && !noNet) R.drawable.ic_shield_green else R.drawable.ic_shield_outline)
        shieldIcon.imageTintList = if (noNet) android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9F0A"))
            else if (off) android.content.res.ColorStateList.valueOf(Color.parseColor("#FF453A")) else null
        haloOuter.background = GradientDrawable().apply { shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (noNet) "#0FFF9F0A" else if (on) "#0F69F0AE" else if (off) "#0FFF453A" else "#00000000")) }
        haloInner.background = GradientDrawable().apply { shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (noNet) "#24FF9F0A" else if (on) "#2469F0AE" else if (off) "#24FF453A" else "#10FFFFFF")) }
        circleLabel.text = if (noNet) "אין רשת – התרעות לא יגיעו" else if (on) "מוגן" else if (sched) "כבוי לפי תזמון" else "לא מוגן"
        circleLabel.setTextColor(Color.parseColor(if (noNet) "#FF9F0A" else if (on) "#69F0AE" else if (off) "#FF453A" else "#AAAAAA"))
        setSweep(on && !noNet)
        updateIndicators()

        renderChips()
        renderHistory()
    }

    /** אזורי התרעה: פס בולט עם המיקום שלי, ומתחתיו האזורים הנוספים */
    internal fun renderChips() {
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

        shelterLine.visibility = View.GONE   // הזמן מוצג עכשיו בפס המיקום
        renderExtraCard()
    }

    /** כרטיס במסך הראשי: היישובים והאזורים הנוספים (תצוגה בלבד) */
    internal fun renderExtraCard() {
        areasSlot.removeAllViews()
        val (towns, dists) = extraSelection()
        val dmap = AreaData.districtMap(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = graphite(28); clipToOutline = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(14), 0, dp(10))
        }
        // תצוגה בלבד - העריכה בהגדרות התרעות → אזורים נוספים
        card.addView(text("יישובים ואזורים נוספים", 16f, C.TEXT, bold = true).apply { setPadding(dp(18), 0, dp(18), dp(6)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(name: String, sub: String) = list.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(5), dp(18), dp(5))
            addView(text(name, 15f, C.TEXT).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f))
            if (sub.isNotEmpty()) addView(text(sub, 12f, C.MUTED).apply { setSingleLine() },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        })
        fun section(title: String, names: List<String>, sub: (String) -> String) {
            if (names.isEmpty()) return
            list.addView(text(title, 12f, C.MUTED2).apply { setPadding(dp(18), dp(6), dp(18), dp(2)) })
            names.forEach { row(it, sub(it)) }
        }
        section("יישובים", towns) { if (it == AreaData.ALL_COUNTRY) "" else dmap[it] ?: "" }
        section("אזורים", dists) { "" }
        if (towns.isEmpty() && dists.isEmpty())
            list.addView(text("לא נבחרו", 14f, C.MUTED).apply { setPadding(dp(18), dp(4), dp(18), dp(6)) })
        // הרשימה נגללת בתוך הכרטיס כשהיא ארוכה; הכרטיס לא חורג מהמקום שנשאר במסך
        card.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) },
            LinearLayout.LayoutParams(-1, -2, 1f))
        areasSlot.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
    }

    /** במסך הראשי: שורת סיכום של ההתראה האחרונה */
    internal fun renderHistory() {
        historyBox.removeAllViews()
        val e = Prefs.history(this).firstOrNull()
        val muted = C.MUTED2
        historyBox.addView(text(
            if (e == null) "אין התראות עדיין"
            else (if (e.level == AlertService.LEVEL_PRE) "התראה מקדימה" else e.title) +
                (if (e.ts > 0) " · " + dayLabel(e.ts) + " " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.ts)) else ""),
            12f, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
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
    internal class Row(val icon: String, val title: String, val value: () -> String,
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

    companion object { internal var reopenDisplay = false }

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
    internal var lockAsked = false
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

/**
 * אייקון בתוך טקסט, ממורכז בדיוק לגובה האותיות (ולא לגובה השורה כולה, שכולל רווח לניקוד) -
 * כך האייקונים בפיד בדיוק בקו אחד עם סוג האירוע
 */
class CenteredIcon(private val d: android.graphics.drawable.Drawable) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int,
                         fm: android.graphics.Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val pf = paint.fontMetricsInt
            fm.ascent = pf.ascent; fm.descent = pf.descent; fm.top = pf.top; fm.bottom = pf.bottom
        }
        return d.bounds.right
    }

    override fun draw(c: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float,
                      top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        // מרכז האותיות: לפי הגבולות האמיתיים של אותיות עבריות מהקו התחתון ועד ראשן
        val r = android.graphics.Rect()
        paint.getTextBounds("אבג", 0, 3, r)
        val center = y + (r.top + r.bottom) / 2f
        c.save()
        c.translate(x, center - d.bounds.bottom / 2f)
        d.draw(c)
        c.restore()
    }
}

/** עיגול בחירה בסגנון סמסונג (ריק / מלא עם וי) - ברשימת בחירת האזורים */
class CheckDot(context: Context, var on: Boolean) : View(context) {
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

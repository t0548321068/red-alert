package com.tal.redalert

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** מסך שקופץ בזמן התראה - מסך מלא, או פופ-אפ באמצע המסך (AlertPopupActivity) */
open class AlertActivity : Activity() {
    companion object {
        /** כמה מסכי התרעה (מסך מלא / פופ-אפ) פתוחים עכשיו */
        @Volatile var open = 0
        /** זמן ההתרעה האחרונה שמסך ההתרעה כבר הוצג לה (אחרי שנסגר - הכרטיס מופיע מיד) */
        @Volatile var shownFiredAt = 0L
    }


    /** פופ-אפ: כרטיס באמצע המסך מעל מה שפתוח */
    protected open val popup = false

    private val ui = Handler(Looper.getMainLooper())
    private var countdown: TextView? = null
    private var cdLabel: TextView? = null
    private var shelterSec = -1
    private var firedAt = 0L
    private var level = AlertService.LEVEL_ALERT

    private var stageIconV: ImageView? = null
    private var chipsFlow: Flow? = null
    private var alertMap: AlertMap? = null
    private val shownAreas = LinkedHashSet<String>()
    private var alertTitle = ""
    private var stageRes = 0

    private val tick = object : Runnable {
        override fun run() {
            AlertUi.stageIcon(alertTitle, level, shelterSec, firedAt).let { r ->
                if (r != stageRes) { stageRes = r; AlertUi.setIcon(stageIconV, r, big = false) } }
            val cd = countdown ?: return
            val t = AlertUi.timer(level, shelterSec, firedAt) ?: return
            cdLabel?.text = t.first
            cdLabel?.visibility = if (t.first.isEmpty()) View.GONE else View.VISIBLE
            cd.textSize = if (t.first.isEmpty()) 20f else 38f
            cd.text = t.second
            if (t.first.isNotEmpty()) ui.postDelayed(this, 250)
        }
    }

    private fun card(alpha: String = "#38000000", r: Int = 28) = GradientDrawable().apply {
        setColor(Color.parseColor(alpha)); cornerRadius = dpx(r).toFloat()
    }

    private fun tv(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open++   // כל עוד מסך ההתרעה פתוח - בלי כרטיס במסך הראשי (רק הצבע)
        shownFiredAt = intent.getLongExtra("firedAt", 0L)
        Prefs.markAlertScreen(this)   // המסך הראשי שמאחור מתעדכן (צבע מאחורי הפופ-אפ)
        MiniOverlay.hide(this)   // המסך חזר - בלי החלון הקטן
        // מופיע מעל מסך הנעילה ומדליק את המסך
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        @Suppress("DEPRECATION")
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        level = intent.getIntExtra("level", AlertService.LEVEL_ALERT)
        shelterSec = intent.getIntExtra("shelter", -1)
        firedAt = intent.getLongExtra("firedAt", System.currentTimeMillis())
        // סגירה אוטומטית לפי הצבע; מצב שבת - אחרי 2 דקות
        val autoClose = intent.getLongExtra("autoCloseMs", 0L)
        if (autoClose > 0) ui.postDelayed(autoCloseRun, (firedAt + autoClose - System.currentTimeMillis()).coerceAtLeast(1000L))

        val bg = Color.parseColor(AlertUi.color(level))
        val title = intent.getStringExtra("title") ?: "צבע אדום"
        alertTitle = title
        val pre = level == AlertService.LEVEL_PRE
        val end = level == AlertService.LEVEL_END
        val (headTitle, headNote) = AlertUi.head(title, level)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (popup) {
                background = GradientDrawable().apply { setColor(bg); cornerRadius = dpx(28).toFloat() }
                clipToOutline = true
                elevation = dpx(16).toFloat()
            } else setBackgroundColor(bg)
            // בכוונה בלי סגירה בלחיצה על המסך - כדי שלא ייסגר בטעות
        }

        // כותרת: סוג ההתרעה, ומתחת מה לעשות
        val titleView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpx(16), dpx(if (popup) 16 else 36), dpx(16), dpx(if (popup) 10 else 14))
            // אייקון מעל הכותרת לפי סוג ההתרעה (אותם אייקונים כמו ברשימת ההתרעות)
            // האייקון משתנה לפי השלב (היכנסו → נשארים במרחב המוגן → ממתינים לסיום)
            val size = dpx(36)   // פופ-אפ: אותו גודל כמו במסך מלא
            addView(ImageView(this@AlertActivity).apply {
                AlertUi.setIcon(this, AlertUi.stageIcon(title, level, shelterSec, firedAt), big = false); stageIconV = this },
                LinearLayout.LayoutParams(size, size))
            addView(tv(headTitle, 28f, bold = true))
            addView(tv(headNote, 20f).apply { setPadding(0, dpx(2), 0, 0) })
        }
        root.addView(titleView)
        (if (Prefs.alertMap(this)) miniMap(level, savedInstanceState) else null)?.let { map ->   // אפשר לכבות את המפה בהגדרות
            val h = if (popup) dpx(170) else (resources.displayMetrics.heightPixels * 0.34f).toInt()
            root.addView(map, LinearLayout.LayoutParams(-1, h))
        }

        // אזורים כתגיות + מקור ההתרעה - מתחת למפה, בגדול
        val chips = Flow(this, dpx(8)); chipsFlow = chips; shownAreas.clear()
        (intent.getStringExtra("body") ?: "").split(",").map { AreaData.unify(it.trim()) }.filter { it.isNotEmpty() }.distinct().forEach { a ->
            shownAreas += a; chips.addView(chip(a))
        }
        val maxH = (resources.displayMetrics.heightPixels * if (popup) 0.18f else 0.22f).toInt()
        val chipsScroll = object : android.widget.ScrollView(this) {
            // הרבה אזורים: גובה מוגבל ונגלל, כדי שלא ידחפו את הכפתורים מהמסך
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply {
            isVerticalScrollBarEnabled = false
            if (chips.childCount > 0) addView(chips, ViewGroup.LayoutParams(-1, -2))
        }
        val srcView = intent.getStringExtra("source")?.takeIf { it.isNotEmpty() }?.let { src ->
            tv("מקור ההתרעה: $src", 13f).apply { setTextColor(Color.parseColor("#E6FFFFFF")); setPadding(0, dpx(6), 0, 0) }
        }
        if (chips.childCount > 0) {
            val areaCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // בלי כרטיס מסביב - רק התגיות
                setPadding(dpx(6), 0, dpx(6), 0)
                addView(chipsScroll, LinearLayout.LayoutParams(-1, -2))
            }
            root.addView(areaCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dpx(10), dpx(10), dpx(10), 0) })
        }

        if (end) root.addView(tv("ניתן לצאת מהמרחב המוגן", 19f).apply { setPadding(dpx(16), dpx(14), dpx(16), 0) })
        if (pre) {
            // מתחת למפה: נוסח ההתרעה ומה לעשות
            if (title.isNotBlank() && title != "התרעה מקדימה")
                root.addView(tv(title, 19f).apply { setPadding(dpx(16), dpx(14), dpx(16), 0) })
            root.addView(tv("במקרה של קבלת התרעה, יש להיכנס למרחב המוגן ולשהות בו עד לקבלת הנחיה מפורשת", 19f)
                .apply { setPadding(dpx(16), dpx(6), dpx(16), 0) })
        }

        // כרטיס הטיימר: זמן התגוננות -> נשארים במרחב המוגן
        var timerCard: LinearLayout? = null
        if (level == AlertService.LEVEL_ALERT) {
            timerCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                // בלי כרטיס - הטיימר ישירות על הרקע
                setPadding(dpx(12), 0, dpx(12), 0)
            }
            cdLabel = tv("", 13f)
            countdown = tv("", 38f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                includeFontPadding = false
            }
            timerCard.addView(cdLabel)
            timerCard.addView(countdown)
            root.addView(timerCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dpx(10), dpx(4), dpx(10), 0) })
        }

        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))   // שטח פנוי מעל הכפתורים (גם בפופאפ - כדי שכולם באותו גובה)
        srcView?.let { root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dpx(16), dpx(8), dpx(16), 0) }) }   // מקור ההתרעה - מעל הכפתורים

        // כפתורים: השתק · הנחיות · סגור (אייקון מעל הכיתוב)
        val buttons = LinearLayout(this).apply { setPadding(dpx(10), dpx(10), dpx(10), dpx(if (popup) 12 else 16)) }
        fun btn(icon: Int, label: String, click: (TextView) -> Unit): LinearLayout {
            val l = tv(label, 13f)
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dpx(9), 0, dpx(9))
                background = card("#40000000", 24)
                addView(ImageView(this@AlertActivity).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dpx(20), dpx(20)))
                addView(l, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpx(3) })
                setOnClickListener { click(l) }
            }
        }
        val tips = Config.guidance(title)
        val list = mutableListOf(
            btn(R.drawable.ic_volume_off, "השתק") { l ->
                AlertService.silence(this); l.text = "הושתק"; l.alpha = 0.6f
            })
        if (tips.isNotEmpty()) list += btn(R.drawable.ic_info_line, "הנחיות") {
            tipsSheet = AppSheet.show(this, "הנחיות", tips.joinToString("\n") { "• $it" }, "סגור")
        }
        list += btn(R.drawable.ic_close, "סגור") { close() }
        list.forEachIndexed { i, b ->
            buttons.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dpx(8) })
        }
        root.addView(buttons)

        if (popup) {
            // כרטיס באמצע המסך, מעל מה שפתוח (הרקע מוחשך)
            val frame = FrameLayout(this).apply {
                // גובה אחיד לפופאפ בכל סוגי ההתרעות (אדום/כתום/ירוק)
                val popupH = minOf(dpx(if (Prefs.alertMap(this@AlertActivity)) 560 else 390),
                    resources.displayMetrics.heightPixels - dpx(48))
                addView(root, FrameLayout.LayoutParams(-1, popupH, Gravity.CENTER).apply { setMargins(dpx(14), dpx(24), dpx(14), dpx(24)) })
            }
            setContentView(frame)
        } else {
            // שלא יוסתר מאחורי כפתורי הניווט / שורת המצב של הטלפון
            root.setOnApplyWindowInsetsListener { v, ins ->
                @Suppress("DEPRECATION")
                v.setPadding(ins.systemWindowInsetLeft, 0, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                titleView.setPadding(dpx(16), ins.systemWindowInsetTop + dpx(12), dpx(16), dpx(14))
                ins
            }
            setContentView(root)
        }
    }

    /** סגירה: השתקה, ובמסך הראשי נשאר כרטיס ההתרעה מעל השעון */
    private fun close() {
        AlertService.silence(this)
        MiniOverlay.hide(this)
        // "אחרי סגירה: ממוזער" - החלון הקטן למעלה, וחוזרים למה שהיה פתוח (לא בתצוגה מקדימה, ולא כשההתרעה כבר נגמרה)
        val firedAt = intent.getLongExtra("firedAt", 0L)
        if (AlertUi.closeToMini(Prefs.afterClose(this), MiniOverlay.canShow(this),
                intent.getStringExtra("source") == "תצוגה מקדימה", AlertUi.expired(level, firedAt))) {
            MiniOverlay.show(this, Intent(intent))
            finish(); return
        }
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (_: Exception) { }
        finish()
    }

    /** שורות של תגיות שנשברות לשורה הבאה (מימין לשמאל), ממורכזות */
    private class Flow(c: android.content.Context, val gap: Int) : ViewGroup(c) {
        override fun onMeasure(w: Int, h: Int) {
            val maxW = MeasureSpec.getSize(w) - paddingLeft - paddingRight
            var x = 0; var y = 0; var lineH = 0
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += lineH + gap; lineH = 0 }
                x += ch.measuredWidth + gap; lineH = maxOf(lineH, ch.measuredHeight)
            }
            setMeasuredDimension(MeasureSpec.getSize(w), y + lineH + paddingTop + paddingBottom)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l - paddingLeft - paddingRight
            val lines = mutableListOf<MutableList<android.view.View>>(mutableListOf())
            var x = 0
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                if (x > 0 && x + ch.measuredWidth > maxW) { lines.add(mutableListOf()); x = 0 }
                lines.last().add(ch); x += ch.measuredWidth + gap
            }
            var y = paddingTop
            for (line in lines) {
                val lw = line.sumOf { it.measuredWidth } + gap * (line.size - 1).coerceAtLeast(0)
                var cx = paddingLeft + (maxW + lw) / 2   // מתחילים מימין
                var lh = 0
                for (ch in line) {
                    cx -= ch.measuredWidth
                    ch.layout(cx, y, cx + ch.measuredWidth, y + ch.measuredHeight)
                    cx -= gap; lh = maxOf(lh, ch.measuredHeight)
                }
                y += lh + gap
            }
        }
    }

    private fun chip(a: String) = tv(a, 22f, bold = true).apply {
        setPadding(dpx(14), dpx(6), dpx(14), dpx(6))
        background = card("#33FFFFFF", 18)
    }

    private fun dpx(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** מפה קטנה (בלי אינטרנט) שמסמנת את האזורים של ההתראה בצבע שלה */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    /** מפת אזור ההתרעה - מפה מובנית, נפתחת מיד */
    private fun miniMap(level: Int, saved: Bundle?): android.view.View? {
        val names = mapNames(intent)
        if (names.isEmpty()) return null
        val me = try { Weather.lastLocation(this) } catch (_: Exception) { null }
        val m = AlertMap(this, saved, names, level, me?.latitude, me?.longitude, Prefs.darkTheme(this))
        alertMap = m
        return m.view
    }

    /** האזורים של ההתרעה בשמות המלאים שבמפה */
    private fun mapNames(i: Intent): List<String> =
        (i.getStringExtra("mapAreas") ?: i.getStringExtra("body") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { raw -> try { AreaData.match(this, raw) } catch (_: Exception) { emptyList() } }.distinct()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        android.widget.Toast.makeText(this, "לסגירה: ✕ סגור", android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * סגירה אוטומטית (מצב שבת: אחרי 2 דקות). עם "שעון במסך" - חוזרים לשעון של מסך השבת,
     * בלי - המסך נסגר והטלפון מכבה אותו
     */
    private val autoCloseRun = Runnable {
        if (Shabbat.active(this) && Prefs.shabbatClock(this))
            try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } catch (_: Exception) { }
        finish()
    }

    /** התרעה חדשה כשהמסך כבר פתוח - מציגים אותה */
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        // התרעה אדומה נוספת כשמסך של התרעה אדומה כבר פתוח: לא פותחים מחדש -
        // האזורים החדשים מתווספים (תגיות + מפה, והסיכות שלהם נופלות), והטיימר לפי ההתרעה החדשה
        val chips = chipsFlow
        if (level == AlertService.LEVEL_ALERT && i.getIntExtra("level", -1) == AlertService.LEVEL_ALERT && chips != null && chips.parent != null) {
            val fresh = (i.getStringExtra("body") ?: "").split(",").map { AreaData.unify(it.trim()) }
                .filter { it.isNotEmpty() && it !in shownAreas }.distinct()
            fresh.forEach { a -> shownAreas += a; chips.addView(chip(a)) }
            mapNames(i).takeIf { it.isNotEmpty() }?.let { alertMap?.add(it) }
            // הכוונה: אותה כותרת, כל האזורים; הטיימר - מההתרעה החדשה
            val all = shownAreas.joinToString(", ")
            val oldMap = intent.getStringExtra("mapAreas") ?: ""
            setIntent(i.putExtra("body", all).putExtra("mapAreas",
                listOf(oldMap, i.getStringExtra("mapAreas") ?: "").filter { it.isNotEmpty() }.joinToString(", ")))
            shelterSec = i.getIntExtra("shelter", -1)
            firedAt = i.getLongExtra("firedAt", System.currentTimeMillis())
            shownFiredAt = firedAt   // יישוב שהצטרף - גם הוא כבר הוצג במסך
            ui.removeCallbacks(tick); ui.post(tick)
            // מצב שבת: דקה מההתרעה החדשה
            i.getLongExtra("autoCloseMs", 0L).takeIf { it > 0 }?.let { ui.removeCallbacks(autoCloseRun); ui.postDelayed(autoCloseRun, it) }
            return
        }
        setIntent(i)
        recreate()
    }

    override fun onStart() { super.onStart(); alertMap?.onStart() }
    override fun onStop() { alertMap?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); alertMap?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); alertMap?.onSave(outState) }

    override fun onResume() {
        super.onResume()
        alertMap?.onResume()
        ui.post(tick)
    }

    private var counted = true   // נספר ב-open
    private fun uncount() { if (counted) { counted = false; open = (open - 1).coerceAtLeast(0) } }

    override fun onPause() {
        // נסגר: מורידים מהספירה כבר עכשיו - המסך הראשי חוזר לפני onDestroy, והכרטיס מופיע בו מיד
        if (isFinishing) uncount()
        alertMap?.onPause()
        ui.removeCallbacks(tick)
        super.onPause()
    }

    /** חלון ההנחיות - נסגר יחד עם המסך (גם בסגירה אוטומטית / התרעה חדשה) */
    private var tipsSheet: android.app.Dialog? = null

    override fun onDestroy() {
        uncount()
        try { tipsSheet?.dismiss() } catch (_: Exception) { }
        tipsSheet = null
        ui.removeCallbacksAndMessages(null)   // כולל הסגירה האוטומטית במצב שבת
        alertMap?.onDestroy(); alertMap = null
        super.onDestroy()
    }
}

/** פופ-אפ: אותו תוכן בכרטיס באמצע המסך */
class AlertPopupActivity : AlertActivity() {
    override val popup = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.6f)
    }
}

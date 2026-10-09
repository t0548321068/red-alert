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

    /** פופ-אפ: כרטיס באמצע המסך מעל מה שפתוח */
    protected open val popup = false

    private val ui = Handler(Looper.getMainLooper())
    private var countdown: TextView? = null
    private var cdLabel: TextView? = null
    private var shelterSec = -1
    private var firedAt = 0L
    private var level = AlertService.LEVEL_ALERT

    private val tick = object : Runnable {
        override fun run() {
            val cd = countdown ?: return
            val t = AlertUi.timer(level, shelterSec, firedAt) ?: return
            cdLabel?.text = t.first
            cdLabel?.visibility = if (t.first.isEmpty()) View.GONE else View.VISIBLE
            cd.textSize = if (t.first.isEmpty()) 20f else if (popup) 32f else 38f
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
        // מצב שבת: המסך נסגר לבד אחרי דקה
        val autoClose = intent.getLongExtra("autoCloseMs", 0L)
        if (autoClose > 0) ui.postDelayed({ finish() }, (firedAt + autoClose - System.currentTimeMillis()).coerceAtLeast(1000L))

        val bg = Color.parseColor(AlertUi.color(level))
        val title = intent.getStringExtra("title") ?: "צבע אדום"
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
            val icon = when {
                pre -> R.drawable.ic_list_pre
                end -> R.drawable.ic_list_end
                else -> R.drawable.ic_list_siren
            }
            val size = dpx(if (popup) 34 else 44)
            addView(ImageView(this@AlertActivity).apply { setImageResource(icon) },
                LinearLayout.LayoutParams(size, size).apply { bottomMargin = dpx(8) })
            addView(tv(headTitle, if (popup) 22f else 28f, bold = true))
            addView(tv(headNote, if (popup) 16f else 20f).apply { setPadding(0, dpx(2), 0, 0) })
        }
        root.addView(titleView)
        (if (Prefs.alertMap(this)) miniMap(level) else null)?.let { map ->   // אפשר לכבות את המפה בהגדרות
            val h = if (popup) dpx(170) else (resources.displayMetrics.heightPixels * 0.34f).toInt()
            root.addView(map, LinearLayout.LayoutParams(-1, h))
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
                background = card(r = if (popup) 22 else 28)
                setPadding(dpx(12), dpx(6), dpx(12), dpx(8))
            }
            cdLabel = tv("", 13f)
            countdown = tv("", if (popup) 32f else 38f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                includeFontPadding = false
            }
            timerCard.addView(cdLabel)
            timerCard.addView(countdown)
            root.addView(timerCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dpx(10), dpx(10), dpx(10), 0) })
        }

        // אזורים כתגיות + מקור ההתרעה
        val chips = Flow(this, dpx(6))
        (intent.getStringExtra("body") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { a ->
            chips.addView(tv(a, 14f).apply {
                setPadding(dpx(12), dpx(4), dpx(12), dpx(4))
                background = card("#33FFFFFF", 14)
            })
        }
        val chipsScroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            if (chips.childCount > 0) addView(chips, ViewGroup.LayoutParams(-1, -2))
        }
        val srcView = intent.getStringExtra("source")?.takeIf { it.isNotEmpty() }?.let { src ->
            tv("מקור ההתרעה: $src", 13f).apply { setTextColor(Color.parseColor("#E6FFFFFF")); setPadding(0, dpx(6), 0, 0) }
        }
        if (popup && timerCard != null) {
            // בפופ-אפ: האזורים והמקור בתוך כרטיס הטיימר
            timerCard.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpx(6) })
            srcView?.let { timerCard.addView(it) }
        } else {
            val areaCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = card(r = if (popup) 22 else 28)
                setPadding(dpx(12), dpx(10), dpx(12), dpx(10))
                addView(chipsScroll, LinearLayout.LayoutParams(-1, -2))
                srcView?.let { addView(it) }
            }
            if (popup) root.addView(areaCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dpx(10), dpx(10), dpx(10), 0) })
            else {
                // האזורים יושבים בתחתית השטח הפנוי - צמודים לכפתורים (נגללים אם יש הרבה)
                val box = FrameLayout(this).apply {
                    addView(areaCard, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                }
                root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dpx(10), dpx(10), dpx(10), 0) })
            }
        }

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
        val tips = Guidance.forTitle(title)
        val list = mutableListOf(
            btn(R.drawable.ic_volume_off, "השתק") { l ->
                AlertService.silence(this); l.text = "הושתק"; l.alpha = 0.6f
            })
        if (tips.isNotEmpty()) list += btn(R.drawable.ic_info_line, "הנחיות") {
            android.app.AlertDialog.Builder(this)
                .setTitle("הנחיות")
                .setMessage(tips.joinToString("\n") { "• $it" })
                .setPositiveButton("סגור", null)
                .show()
        }
        list += btn(R.drawable.ic_close, "סגור") { close() }
        list.forEachIndexed { i, b ->
            buttons.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dpx(8) })
        }
        root.addView(buttons)

        if (popup) {
            // כרטיס באמצע המסך, מעל מה שפתוח (הרקע מוחשך)
            val frame = FrameLayout(this).apply {
                addView(root, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dpx(14), dpx(24), dpx(14), dpx(24)) })
            }
            setContentView(frame)
        } else {
            // שלא יוסתר מאחורי כפתורי הניווט / שורת המצב של הטלפון
            root.setOnApplyWindowInsetsListener { v, ins ->
                @Suppress("DEPRECATION")
                v.setPadding(0, 0, 0, ins.systemWindowInsetBottom)
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

    private fun dpx(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** מפה קטנה (בלי אינטרנט) שמסמנת את האזורים של ההתראה בצבע שלה */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun miniMap(level: Int): android.view.View? {
        val names = org.json.JSONArray()
        (intent.getStringExtra("mapAreas") ?: intent.getStringExtra("body") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { raw -> try { AreaData.match(this, raw).forEach { names.put(it) } } catch (_: Exception) { } }
        if (names.length() == 0) return null
        val web = android.webkit.WebView(this).apply {
            settings.javaScriptEnabled = true
            // שקוף עד שהמפה מוכנה - רואים את צבע ההתרעה, לא מלבן כהה
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            // הנתונים עוברים בכתובת - המפה נפתחת ישר במצב הקטן על אזור ההתרעה
            val me = try { Weather.lastLocation(this@AlertActivity) } catch (_: Exception) { null }
            val q = org.json.JSONObject().put("names", names).put("level", level)
            if (me != null) q.put("lat", me.latitude).put("lon", me.longitude)
            loadUrl("file:///android_asset/map.html#mini=" + android.net.Uri.encode(q.toString()))
        }
        return web
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        android.widget.Toast.makeText(this, "לסגירה: ✕ סגור", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** התראה חדשה כשהמסך כבר פתוח - מציגים אותה */
    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        recreate()
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
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

package com.tal.redalert

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** מסך מלא שקופץ בזמן התראה - עם ספירה לאחור לכניסה למרחב המוגן */
class AlertActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private var countdown: TextView? = null
    private var shelterSec = -1
    private var firedAt = 0L

    private var stay: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            countdown?.let { cd ->
                val left = shelterSec - ((now - firedAt) / 1000).toInt()
                cd.text = when {
                    shelterSec <= 0 -> "מיידי"
                    left > 0 -> "%d:%02d".format(left / 60, left % 60)
                    else -> "0:00"
                }
            }
            // טיימר שהייה: 10 דקות מהירי
            stay?.let { st ->
                // 10 הדקות מתחילות רק אחרי שנגמר הזמן להגעה
                val enterEnd = firedAt + shelterSec.coerceAtLeast(0) * 1000L
                if (now < enterEnd) { st.text = ""; ui.postDelayed(this, 250); return }
                val left = ((enterEnd + Prefs.STAY_MS - now) / 1000).toInt()
                st.text = if (left > 0) "נשארים במרחב המוגן: %d:%02d".format(left / 60, left % 60)
                          else "ממתינים להודעת סיום אירוע"
                if (left > 0) { ui.postDelayed(this, 250); return }
            }
            if (countdown != null && shelterSec > 0 && now - firedAt < shelterSec * 1000L) ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MiniOverlay.hide(this)   // המסך המלא חזר - בלי החלון הקטן
        // מופיע מעל מסך הנעילה ומדליק את המסך
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        @Suppress("DEPRECATION")
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val level = intent.getIntExtra("level", AlertService.LEVEL_ALERT)
        shelterSec = intent.getIntExtra("shelter", -1)
        firedAt = intent.getLongExtra("firedAt", System.currentTimeMillis())
        // מצב שבת: המסך נסגר לבד אחרי דקה
        val autoClose = intent.getLongExtra("autoCloseMs", 0L)
        if (autoClose > 0) ui.postDelayed({ finish() }, (firedAt + autoClose - System.currentTimeMillis()).coerceAtLeast(1000L))

        val (bg, note) = when (level) {
            AlertService.LEVEL_PRE -> "#F08C00" to "היו בקרבת מרחב מוגן"
            AlertService.LEVEL_END -> "#2E7D32" to "ניתן לצאת מהמרחב המוגן"
            else -> "#D50000" to "היכנסו למרחב המוגן"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(bg))
            // בכוונה בלי סגירה בלחיצה על המסך - כדי שלא ייסגר בטעות
        }
        val title = intent.getStringExtra("title") ?: "צבע אדום"

        val map = miniMap(level)
        val titleView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpx(16), dpx(36), dpx(16), dpx(14))
            addView(TextView(this@AlertActivity).apply {
                text = title
                textSize = 28f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            // מתחת לכותרת: מה לעשות
            addView(TextView(this@AlertActivity).apply {
                text = note
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dpx(2), 0, 0)
            })
        }
        // הכותרת למעלה, והמפה מתחתיה (לא מוסתרת)
        root.addView(titleView)
        if (map != null) root.addView(map, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.34f).toInt()))

        // זמן התגוננות - ממורכז מתחת למפה
        if (level == AlertService.LEVEL_ALERT && shelterSec >= 0) {
            root.addView(TextView(this).apply {
                text = "זמן התגוננות"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dpx(14), 0, 0)
            })
            countdown = TextView(this).apply {
                textSize = 48f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            root.addView(countdown)
        }

        if (level == AlertService.LEVEL_ALERT) {
            stay = TextView(this).apply {
                textSize = 17f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dpx(4), 0, 0)
            }
            root.addView(stay)
        }

        // אזורים כתגיות - בכרטיס שנגלל אם יש הרבה
        val chips = Flow(this, dpx(6)).apply {
            setPadding(dpx(10), dpx(10), dpx(10), dpx(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpx(18).toFloat()
                setColor(Color.parseColor("#33000000"))
            }
        }
        (intent.getStringExtra("body") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { a ->
            chips.addView(TextView(this).apply {
                text = a
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(dpx(12), dpx(5), dpx(12), dpx(5))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpx(14).toFloat()
                    setColor(Color.parseColor("#38FFFFFF"))
                }
            })
        }
        val chipsScroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            if (chips.childCount > 0) addView(chips, ViewGroup.LayoutParams(-1, -2))
        }
        // האזורים יושבים בתחתית השטח הפנוי - צמודים למקור ולכפתורים (נגללים אם יש הרבה)
        val chipsBox = android.widget.FrameLayout(this).apply {
            addView(chipsScroll, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        root.addView(chipsBox, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(dpx(14), dpx(10), dpx(14), 0)
        })

        intent.getStringExtra("source")?.takeIf { it.isNotEmpty() }?.let { src ->
            root.addView(TextView(this).apply {
                text = "מקור ההתרעה: $src"
                textSize = 13f
                setTextColor(Color.parseColor("#DDFFFFFF"))
                gravity = Gravity.CENTER
                setPadding(0, dpx(8), 0, 0)
            })
        }

        // כפתורים: השתקה · הנחיות · מזעור · סגירה בהחלקה
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dpx(8), dpx(14), dpx(8), 0)
        }
        fun btn(label: String) = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dpx(14), dpx(9), dpx(14), dpx(9))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpx(24).toFloat()
                setStroke(dpx(2), Color.WHITE)
                setColor(Color.parseColor("#33FFFFFF"))
            }
        }
        val mute = btn("🔇 השתק").apply {
            setOnClickListener {
                AlertService.silence(this@AlertActivity)
                text = "🔇 הושתק"
                alpha = 0.6f
            }
        }
        // הנחיות - מה לעשות לפי סוג האיום
        val tips = Guidance.forTitle(title)
        val guide = btn("ℹ️ הנחיות").apply {
            setOnClickListener {
                android.app.AlertDialog.Builder(this@AlertActivity)
                    .setTitle("הנחיות")
                    .setMessage(tips.joinToString("\n") { "• $it" })
                    .setPositiveButton("סגור", null)
                    .show()
            }
        }
        // מזער: חלון קטן למעלה עם הספירה, והמסך המלא נסגר
        val mini = btn("▭ מזער").apply {
            setOnClickListener {
                if (MiniOverlay.canShow(this@AlertActivity)) MiniOverlay.show(this@AlertActivity, intent)
                else android.widget.Toast.makeText(this@AlertActivity,
                    "לחלון קטן צריך הרשאת \"הצגה מעל אפליקציות אחרות\"", android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
        }
        buttons.addView(mute)
        if (tips.isNotEmpty()) buttons.addView(guide, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpx(8) })
        buttons.addView(mini, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dpx(8) })
        root.addView(buttons)

        // סגירה: החלקה של הכפתור עד הסוף (לא נסגר בנגיעה בטעות)
        root.addView(slideToClose(), LinearLayout.LayoutParams(-1, dpx(60)).apply {
            setMargins(dpx(16), dpx(14), dpx(16), dpx(20))
        })
        // שלא יוסתר מאחורי כפתורי הניווט / שורת המצב של הטלפון
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            v.setPadding(0, 0, 0, ins.systemWindowInsetBottom)
            titleView.setPadding(dpx(16), ins.systemWindowInsetTop + dpx(12), dpx(16), dpx(14))
            ins
        }
        setContentView(root)
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

    /** פס "החלק לסגירה": גוררים את העיגול לצד השני */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun slideToClose(): android.view.View {
        val track = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpx(32).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
                setStroke(dpx(2), Color.WHITE)
            }
            layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
        }
        val label = TextView(this).apply {
            // החיצים מבודדים משמאל-לימין - כדי שלא יתהפכו בטקסט העברי ויצביעו לכיוון ההחלקה
            text = "\u2066›››\u2069  החלק לסגירה  \u2066›››\u2069"
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
        }
        track.addView(label, android.widget.FrameLayout.LayoutParams(-1, -1))
        val size = dpx(48)   // פס בגובה 60 - רווח 6 מכל הצדדים
        val knob = TextView(this).apply {
            text = "✕"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D50000"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE)
            }
        }
        track.addView(knob, android.widget.FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START; leftMargin = dpx(6)
        })
        var downX = 0f
        knob.setOnTouchListener { v, e ->
            val max = (track.width - size - dpx(12)).toFloat()
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX - v.translationX; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    v.translationX = (e.rawX - downX).coerceIn(0f, max)
                    label.alpha = 1f - v.translationX / max
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (v.translationX > max * 0.85f) {
                        AlertService.silence(this)
                        MiniOverlay.hide(this)
                        finish()
                    } else {
                        v.animate().translationX(0f).setDuration(200).start()
                        label.animate().alpha(1f).setDuration(200).start()
                    }
                    true
                }
                else -> false
            }
        }
        return track
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        android.widget.Toast.makeText(this, "לסגירה: החלק את הכפתור · למזעור: ▭ מזער", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** התראה חדשה כשהמסך כבר פתוח - מציגים אותה */
    override fun onNewIntent(i: android.content.Intent) {
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

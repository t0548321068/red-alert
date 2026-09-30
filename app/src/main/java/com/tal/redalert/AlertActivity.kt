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
                st.text = if (left > 0) "⏳ נשארים במרחב המוגן: %d:%02d".format(left / 60, left % 60)
                          else "⏳ ממתינים להודעת סיום אירוע"
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

        val (bg, note) = when (level) {
            AlertService.LEVEL_PRE -> "#F08C00" to "היו בקרבת מרחב מוגן"
            AlertService.LEVEL_END -> "#2E7D32" to "ניתן לצאת מהמרחב המוגן"
            else -> "#D50000" to "היכנסו למרחב המוגן"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(bg))
            setPadding(48, 48, 48, 48)
            // בכוונה בלי סגירה בלחיצה על המסך - כדי שלא ייסגר בטעות
        }
        root.addView(TextView(this).apply {
            text = intent.getStringExtra("title") ?: "צבע אדום"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = intent.getStringExtra("body") ?: ""
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40)
        })

        // ספירה לאחור - רק בהתראת ירי/חדירה ורק אם הזמן ידוע
        if (level == AlertService.LEVEL_ALERT && shelterSec >= 0) {
            root.addView(TextView(this).apply {
                text = "זמן להגעה למרחב המוגן"
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            countdown = TextView(this).apply {
                textSize = 72f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 30)
            }
            root.addView(countdown)
        }

        root.addView(TextView(this).apply {
            text = note
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        intent.getStringExtra("source")?.takeIf { it.isNotEmpty() }?.let { src ->
            root.addView(TextView(this).apply {
                text = "📡 התקבל ראשון מ: $src"
                textSize = 13f
                setTextColor(Color.parseColor("#DDFFFFFF"))
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 0)
            })
        }

        if (level == AlertService.LEVEL_ALERT) {
            stay = TextView(this).apply {
                textSize = 18f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 0)
            }
            root.addView(stay)
        }

        // מה לעשות - הנחיות קצרות לפי סוג האיום
        val tips = Guidance.forTitle(intent.getStringExtra("title") ?: "")
        root.addView(TextView(this).apply {
            text = tips.joinToString("\n") { "• $it" }
            textSize = 15f
            setTextColor(Color.WHITE)
            setLineSpacing(0f, 1.25f)
            setPadding(36, 28, 36, 28)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 30f
                setColor(Color.parseColor("#26000000"))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 36 })

        // כפתורים: השתקה (צליל ורטט בלבד) · מזעור לחלון קטן · סגירה בהחלקה
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 60, 0, 0)
        }
        fun btn(label: String) = TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(44, 26, 44, 26)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 60f
                setStroke(3, Color.WHITE)
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
        buttons.addView(mini, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 40 })
        root.addView(buttons)

        // סגירה: החלקה של הכפתור עד הסוף (לא נסגר בנגיעה בטעות)
        root.addView(slideToClose(), LinearLayout.LayoutParams(-1, dpx(64)).apply { topMargin = dpx(28) })
        // גלילה - שהכל ייכנס גם במסך קטן
        setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor(bg))
            addView(root, ViewGroup.LayoutParams(-1, -1))
        })
    }

    private fun dpx(v: Int) = (v * resources.displayMetrics.density).toInt()

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
            text = "החלק לסגירה  \u2066›››\u2069"
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
        }
        track.addView(label, android.widget.FrameLayout.LayoutParams(-1, -1))
        val size = dpx(56)
        val knob = TextView(this).apply {
            text = "✕"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#D50000"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.WHITE)
            }
        }
        track.addView(knob, android.widget.FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START; leftMargin = dpx(4)
        })
        var downX = 0f
        knob.setOnTouchListener { v, e ->
            val max = (track.width - size - dpx(8)).toFloat()
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

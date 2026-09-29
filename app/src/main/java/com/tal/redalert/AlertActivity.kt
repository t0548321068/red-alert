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

    private val tick = object : Runnable {
        override fun run() {
            val cd = countdown ?: return
            val left = shelterSec - ((System.currentTimeMillis() - firedAt) / 1000).toInt()
            if (shelterSec <= 0) {
                cd.text = "מיידי"
            } else if (left > 0) {
                cd.text = "%d:%02d".format(left / 60, left % 60)
                ui.postDelayed(this, 250)
            } else {
                cd.text = "0:00"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            AlertService.LEVEL_PRE -> "#E65100" to "היו בקרבת מרחב מוגן"
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

        // כפתורים: השתקה (צליל ורטט בלבד) · סגירה בלחיצה ארוכה
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
        val close = btn("✕ סגור").apply {
            setOnClickListener {
                android.widget.Toast.makeText(this@AlertActivity, "לחיצה ארוכה לסגירה",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            setOnLongClickListener {
                AlertService.silence(this@AlertActivity)
                finish()
                true
            }
        }
        buttons.addView(mute)
        buttons.addView(close, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 40 })
        root.addView(buttons)
        // גלילה - שהכל ייכנס גם במסך קטן
        setContentView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor(bg))
            addView(root, ViewGroup.LayoutParams(-1, -1))
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        android.widget.Toast.makeText(this, "לסגירה: לחיצה ארוכה על ✕ סגור", android.widget.Toast.LENGTH_SHORT).show()
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

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
            setOnClickListener { finish() }
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
            text = "$note\n(לחיצה לסגירה)"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
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

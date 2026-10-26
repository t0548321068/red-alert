package com.tal.redalert

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * מצב שעון לילה: מסך שחור עם שעון גדול ועמום בלבד, המסך נשאר דולק.
 * כשמגיעה התראה - מסך ההתראה קופץ מעליו (האפליקציה בחזית).
 * השעון זז מעט כל דקה כדי לא לצרוב את המסך.
 */
class ClockActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private lateinit var time: TextView
    private var lastShift = 0L

    private val DIM = Color.parseColor("#8A8A8E")

    private val tick = object : Runnable {
        override fun run() {
            // מתזמנים קודם - תקלה בהמשך לא תעצור את השעון. כל חצי שנייה (להבהוב הנקודתיים)
            ui.removeCallbacks(this)
            val now = System.currentTimeMillis()
            ui.postDelayed(this, if (now % 1000 < 500) 500 - now % 1000 else 1000 - now % 1000)
            time.text = TimeFormat.clock(this@ClockActivity, now)
            if (now % 1000 >= 500) return
            if (now - lastShift > 60_000) { shift(); lastShift = now }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.05f }   // עמום
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        time = TextView(this).apply {
            textSize = 84f
            setTextColor(DIM)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(time)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        setContentView(root)
    }

    /** הזזה קטנה ואקראית נגד צריבת מסך */
    private fun shift() {
        box.translationX = Random.nextInt(-40, 41).toFloat()
        box.translationY = Random.nextInt(-60, 61).toFloat()
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }
}

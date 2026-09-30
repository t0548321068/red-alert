package com.tal.redalert

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * מצב שעון לילה: מסך שחור עם שעון גדול ועמום, המסך נשאר דולק.
 * כשמגיעה התראה - מסך ההתראה קופץ מעליו (האפליקציה בחזית).
 * השעון זז מעט כל דקה כדי לא לצרוב את המסך.
 */
class ClockActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private lateinit var time: TextView
    private lateinit var day: TextView
    private lateinit var status: TextView
    private lateinit var feed: TextView
    private var lastShift = 0L

    private val DIM = Color.parseColor("#8A8A8E")
    private val DIM2 = Color.parseColor("#55555A")

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            time.text = TimeFormat.clock(this@ClockActivity, now)
            TimeFormat.fit(time, 84f)
            day.text = TimeFormat.dayLine(this@ClockActivity, now)

            val on = Prefs.enabled(this@ClockActivity)
            status.text = if (on) "● מוגן · ${SourceHealth.upCount()}/${SourceHealth.total()}" else "○ כבוי"
            status.setTextColor(if (on) Color.parseColor("#1F7A36") else DIM2)

            feed.text = FeedText.latest(this@ClockActivity) ?: ""

            if (now - lastShift > 60_000) { shift(); lastShift = now }
            ui.postDelayed(this, 1000 - now % 1000)
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
        day = TextView(this).apply { textSize = 18f; setTextColor(DIM2); gravity = Gravity.CENTER }
        status = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(0, 16, 0, 0) }
        feed = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#7A2A26"))
            gravity = Gravity.CENTER
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            setPadding(24, 20, 24, 0)
        }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(time); addView(day); addView(status); addView(feed)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            setOnClickListener { finish() }   // לחיצה - יציאה
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

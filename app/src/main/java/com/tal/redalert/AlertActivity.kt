package com.tal.redalert

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** מסך אדום מלא שקופץ בזמן התראה */
class AlertActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val level = intent.getIntExtra("level", AlertService.LEVEL_ALERT)
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
        root.addView(TextView(this).apply {
            text = "$note\n(לחיצה לסגירה)"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
    }
}

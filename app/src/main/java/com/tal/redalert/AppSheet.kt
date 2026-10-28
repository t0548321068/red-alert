package com.tal.redalert

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * חלון הודעה בסגנון האפליקציה (כמו חלונות הבחירה בהגדרות): כרטיס מעוגל בתחתית המסך,
 * כותרת, טקסט, ולמטה כפתור אחד או שניים עם קו מפריד - במקום החלון של הטלפון
 */
object AppSheet {
    fun show(a: Activity, title: String, message: String, ok: String, onOk: (() -> Unit)? = null,
             cancel: String? = null, onCancel: (() -> Unit)? = null, cancelable: Boolean = true) {
        if (a.isFinishing) return
        val light = !Prefs.darkTheme(a)
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt() }
        val white = if (light) Color.parseColor("#1C1C1E") else Color.WHITE
        val soft = if (light) Color.parseColor("#3A3A3C") else Color.parseColor("#D8D8D8")
        fun tv(t: String, size: Float, color: Int, bold: Boolean) = TextView(a).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(if (light) Color.WHITE else Color.parseColor("#393939")); cornerRadius = dp(26).toFloat() }
        }
        card.addView(tv(title, 17f, white, true).apply { setPadding(dp(30), dp(24), dp(30), 0) })
        // טקסט ארוך (למשל "מה חדש") נגלל בתוך החלון
        val msg = tv(message, 15f, soft, false).apply {
            setPadding(dp(30), dp(12), dp(30), dp(4)); setLineSpacing(0f, 1.15f) }
        val maxH = (a.resources.displayMetrics.heightPixels * 0.6f).toInt()
        card.addView(object : ScrollView(a) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply { addView(msg) })
        val btns = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(t: String, run: (() -> Unit)?) = tv(t, 17f, white, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { d.dismiss(); run?.invoke() }
        }
        if (cancel != null) {
            btns.addView(button(cancel, onCancel), LinearLayout.LayoutParams(0, -1, 1f))
            btns.addView(View(a).apply { setBackgroundColor(Color.parseColor(if (light) "#D1D1D6" else "#535353")) },
                LinearLayout.LayoutParams(dp(1), dp(16)))
        }
        btns.addView(button(ok, onOk), LinearLayout.LayoutParams(0, -1, 1f))
        card.addView(btns, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(8) })

        val frame = FrameLayout(a)
        frame.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(10)) })
        if (cancelable) frame.setOnClickListener { d.dismiss() }   // לחיצה מחוץ לחלון סוגרת
        d.setCancelable(cancelable)
        frame.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            (card.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = insets.systemWindowInsetBottom + dp(10)
                leftMargin = insets.systemWindowInsetLeft + dp(10); rightMargin = insets.systemWindowInsetRight + dp(10) }
            card.requestLayout(); insets
        }
        d.setContentView(frame)
        d.window?.apply {
            setLayout(-1, -1)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            setWindowAnimations(0)
        }
        d.show()
    }
}

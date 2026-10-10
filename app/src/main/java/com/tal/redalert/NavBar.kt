package com.tal.redalert

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** התפריט התחתון - משותף למסך הראשי, להגדרות, להתרעות ולשעון */
object NavBar {
    /** תפריט תחתון צף (sel = הלשונית הנבחרת, -1 = אף אחת): ראשי · התרעות · הגדרות */
    fun build(c: Context, sel: Int, light: Boolean = false, onTab: (Int) -> Unit): LinearLayout {
        fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()
        // תפריט תחתון צף: ראשי · התרעות · הגדרות (לפי המידות של inv-bottom-nav)
        val nav = LinearLayout(c).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(5), dp(4), dp(5) + dp(6), dp(4))   // +6 מפצה על החפיפה של הלשונית האחרונה
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (light) "#E3E3E8" else "#3A3A3E")); cornerRadius = dp(32).toFloat()
                setStroke(dp(1), Color.parseColor(if (light) "#1F000000" else "#1FFFFFFF"))
            }
            elevation = dp(6).toFloat()
            clipToPadding = false   // הלשונית האחרונה חופפת 6 לתוך הריפוד - שלא תיחתך
        }
        // רוחב לשונית: (רוחב המסך - 32 - 10 + 24) / 5, לכל היותר 83
        val screenDp = c.resources.displayMetrics.widthPixels / c.resources.displayMetrics.density
        val tabW = dp(((screenDp - 32 - 10 + 24) / 5).coerceAtMost(83f).toInt())
        fun tab(icon: Int, label: String, on: Boolean, click: () -> Unit) = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(7), dp(4), dp(7))
            if (on) background = GradientDrawable().apply { setColor(Color.parseColor(if (light) "#C7C7CC" else "#55555B")); cornerRadius = dp(24).toFloat() }
            val col = if (light) Color.parseColor(if (on) "#1C1C1E" else "#6E6E73") else if (on) Color.WHITE else Color.parseColor("#AAAAAA")
            addView(ImageView(c).apply {
                setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(col)
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(TextView(c).apply {
                text = label; textSize = 11f; setTextColor(col); gravity = Gravity.CENTER
                includeFontPadding = false; setSingleLine(); if (on) typeface = Typeface.DEFAULT_BOLD },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            setOnClickListener { click() }
        }
        // חפיפה של 6 בין הלשוניות
        val tabLp = { LinearLayout.LayoutParams(tabW, -2).apply { marginEnd = -dp(6) } }
        listOf(R.drawable.ic_home to "ראשי", R.drawable.ic_nav_alerts to "התרעות",
               R.drawable.ic_gear to "הגדרות").forEachIndexed { i, (ic, l) ->
            nav.addView(tab(ic, l, i == sel) { if (i != sel) onTab(i) }, tabLp())
        }
        return nav
    }
}

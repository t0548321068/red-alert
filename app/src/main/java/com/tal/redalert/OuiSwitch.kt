package com.tal.redalert

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * מתג בסגנון סמסונג (לפי צילום מהגדרות "חיבורים"): פס מעוגל 30x18,
 * עיגול לבן בתוך הפס. בעברית: דלוק = העיגול משמאל על כחול, כבוי = מימין על אפור.
 */
class OuiSwitch(context: Context, private val on: Boolean, light: Boolean) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (on) "#4878F6" else if (light) "#BDBDC2" else "#646368")
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((30 * d).toInt(), (18 * d).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
        c.drawRoundRect(0f, 0f, w, h, r, r, track)
        val inset = 1.6f * d
        val tr = r - inset
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        // דלוק = לכיוון הסוף (בעברית שמאל), כבוי = לכיוון ההתחלה (בעברית ימין)
        val atEnd = on
        val left = (atEnd && rtl) || (!atEnd && !rtl)
        val cx = if (left) inset + tr else w - inset - tr
        c.drawCircle(cx, r, tr, thumb)
    }
}

package com.tal.tzofarrelay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/** עיגול עם טבעת: מסתובבת בזמן הבדיקה, ומלאה בצבע התוצאה כשהיא מסתיימת */
class UpdateRing(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; color = Color.parseColor("#2A2A2C") }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private var angle = 0f
    private var spinning = true
    private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }
    var color: Int = Color.parseColor("#4C7DFF")
        set(v) { field = v; invalidate() }

    fun spin() { spinning = true; if (!anim.isRunning) anim.start() }
    fun stop() { spinning = false; anim.cancel(); invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (spinning) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val r = RectF(arc.strokeWidth, arc.strokeWidth, width - arc.strokeWidth, height - arc.strokeWidth)
        arc.color = color
        if (spinning) { c.drawOval(r, track); c.drawArc(r, angle - 90f, 90f, false, arc) }
        else c.drawOval(r, arc)   // סיום: טבעת מלאה בצבע התוצאה
    }
}

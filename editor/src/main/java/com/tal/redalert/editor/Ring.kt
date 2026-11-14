package com.tal.redalert.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * עיגול התקדמות (כמו בניהול צופר): טבעת שמתמלאת לפי האחוז, והאחוז באמצע.
 * from: מאיזה אחוז להתחיל - המספר עולה אחד-אחד עד האחוז (בלי קפיצות)
 */
class Ring(c: Context, private val target: Int, var color: Int, from: Int = target) : View(c) {
    private var percent = from.coerceIn(0, 100)
    private val anim: android.animation.ValueAnimator? = if (from == target) null else
        android.animation.ValueAnimator.ofInt(percent, target.coerceIn(0, 100)).apply {
            duration = (kotlin.math.abs(target - from) * 40L).coerceIn(300L, 3000L)   // 40ms לכל אחוז
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { percent = it.animatedValue as Int; invalidate() }
        }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim?.start() }
    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; this.color = Color.parseColor("#2C2C2E") }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat(); val r = s / 2 - 3 * d
        val cx = width / 2f; val cy = height / 2f
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(box, 0f, 360f, false, track)
        val p = percent.coerceIn(0, 100) / 100f
        arc.color = color
        if (p > 0) c.drawArc(box, -90f, 360f * p, false, arc)
        txt.textSize = s * 0.26f
        c.drawText("${percent.coerceIn(0, 100)}%", cx, cy + txt.textSize * 0.36f, txt)
    }
}

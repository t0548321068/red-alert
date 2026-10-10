package com.tal.redalert

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.Scroller
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * גלגל מספרים כמו בבורר השעה של סמסונג ("הגדר שעה"): שלוש שורות,
 * האמצעית לבנה ומודגשת, שמעליה ומתחתיה אפורות. מסתובב בלי סוף (23 → 00).
 */
class TimeWheel(context: Context, private val count: Int, start: Int) : View(context) {
    private val d = resources.displayMetrics.density
    private val rowH = 51 * d                       // לפי הצילום: 3 שורות בגובה 153
    private var offset = start * rowH               // מיקום הגלילה בפיקסלים
    private val scroller = Scroller(context)
    private var tracker: VelocityTracker? = null
    private var lastY = 0f
    private var lastTick = start

    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 32 * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif", Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E6E6E"); textSize = selPaint.textSize
        typeface = selPaint.typeface; textAlign = Paint.Align.CENTER
    }

    /** הערך שנבחר עכשיו */
    val value: Int get() = Math.floorMod((offset / rowH).roundToInt(), count)

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (rowH * 3).toInt())

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val base = (offset / rowH).toInt() - 2
        for (i in base..base + 4) {
            val y = cy + (i * rowH - offset)
            if (y < -rowH || y > height + rowH) continue
            val dist = abs(y - cy) / rowH
            val p = if (dist < 0.5f) selPaint else dimPaint
            p.alpha = if (p === selPaint) 255 else (255 * (1f - (dist - 0.5f).coerceIn(0f, 1f) * 0.35f)).toInt()
            val txt = "%02d".format(Math.floorMod(i, count))
            c.drawText(txt, width / 2f, y - (p.descent() + p.ascent()) / 2, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { scroller.forceFinished(true); lastY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { offset -= e.y - lastY; lastY = e.y; tick(); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.computeCurrentVelocity(1000)
                val v = tracker?.yVelocity ?: 0f
                tracker?.recycle(); tracker = null
                if (abs(v) > 300 * d) {
                    scroller.fling(0, offset.toInt(), 0, -v.toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
                    // עוצרים בדיוק על שורה
                    val end = (scroller.finalY / rowH).roundToInt() * rowH
                    scroller.finalY = end.toInt()
                } else snap()
                postInvalidateOnAnimation()
            }
        }
        return true
    }

    private fun snap() {
        val target = ((offset / rowH).roundToInt() * rowH).toInt()
        if (target != offset.toInt()) scroller.startScroll(0, offset.toInt(), 0, target - offset.toInt(), 180)
        else { offset = target.toFloat(); invalidate() }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offset = scroller.currY.toFloat(); tick(); postInvalidateOnAnimation()
            if (scroller.isFinished && abs(offset - (offset / rowH).roundToInt() * rowH) > 1) snap()
        }
    }

    /** "טיק" רטט קטן בכל מעבר מספר, כמו בסמסונג */
    private fun tick() {
        val v = value
        if (v != lastTick) { lastTick = v; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }
}

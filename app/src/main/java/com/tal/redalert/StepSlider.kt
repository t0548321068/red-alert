package com.tal.redalert

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * פס בחירה בשלבים בסגנון סמסונג (כמו "גודל גופן"): פס כהה מעוגל עם נקודות,
 * והשלב הנבחר מסומן בטבעת כחולה. בעברית השלב הראשון מימין.
 * onPick נקרא כשמרימים את האצבע על שלב חדש.
 */
class StepSlider(context: Context, private val steps: Int, private var sel: Int,
                 private val light: Boolean, private val onPick: (Int) -> Unit) : View(context) {

    /** נקרא תוך כדי גרירה בכל פעם שהשלב משתנה (לעדכון הכיתוב מיד) */
    var onMove: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    // מידות לפי הצילום מסמסונג: פס דק 13, נקודות 7, טבעת 19 עם קו כחול דק
    private val trackH = 13 * d
    private val ringR = 9.5f * d
    private var drag = -1   // שלב בזמן גרירה (-1 = לא גוררים)

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (light) "#E5E5EA" else "#3A3A3C")
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (light) "#AEAEB2" else "#636366")
    }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(if (light) "#FFFFFF" else "#000000")
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.parseColor("#4C7DFF")
    }

    private val rtl get() = layoutDirection == LAYOUT_DIRECTION_RTL

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (44 * d).toInt())   // שטח מגע נוח

    /** מיקום X של שלב i (בעברית - מימין לשמאל) */
    private fun xOf(i: Int): Float {
        val left = ringR; val right = width - ringR   // הטבעת לא נחתכת בקצוות
        val t = if (steps <= 1) 0f else i / (steps - 1f)
        return if (rtl) right - t * (right - left) else left + t * (right - left)
    }

    private fun stepAt(x: Float): Int {
        val left = ringR; val right = width - ringR
        var t = ((x - left) / (right - left)).coerceIn(0f, 1f)
        if (rtl) t = 1f - t
        return (t * (steps - 1)).roundToInt()
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val pad = ringR - trackH / 2 - 2 * d   // הפס מתחיל קצת לפני הנקודה הראשונה
        c.drawRoundRect(pad, cy - trackH / 2, width - pad, cy + trackH / 2, trackH / 2, trackH / 2, track)
        for (i in 0 until steps) c.drawCircle(xOf(i), cy, 3.5f * d, dot)
        val cur = if (drag >= 0) drag else sel
        val x = xOf(cur)
        c.drawCircle(x, cy, ringR, ringFill)
        c.drawCircle(x, cy, ringR - ring.strokeWidth / 2, ring)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val p = stepAt(e.x)
                if (p != drag) { drag = p; invalidate(); onMove?.invoke(p) }
            }
            MotionEvent.ACTION_UP -> {
                val p = stepAt(e.x); drag = -1
                if (p != sel) { sel = p; invalidate(); onPick(p) } else invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { drag = -1; invalidate(); onMove?.invoke(sel) }
        }
        return true
    }
}

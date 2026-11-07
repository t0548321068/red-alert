package com.tal.redalert
import com.tal.redalert.MainActivity.C
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// רכיבי תצוגה קטנים של המסך הראשי

/**
 * אייקון בתוך טקסט, ממורכז בדיוק לגובה האותיות (ולא לגובה השורה כולה, שכולל רווח לניקוד) -
 * כך האייקונים בפיד בדיוק בקו אחד עם סוג האירוע
 */
class CenteredIcon(private val d: android.graphics.drawable.Drawable) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int,
                         fm: android.graphics.Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val pf = paint.fontMetricsInt
            fm.ascent = pf.ascent; fm.descent = pf.descent; fm.top = pf.top; fm.bottom = pf.bottom
        }
        return d.bounds.right
    }

    override fun draw(c: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float,
                      top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        // מרכז האותיות: לפי הגבולות האמיתיים של אותיות עבריות מהקו התחתון ועד ראשן
        val r = android.graphics.Rect()
        paint.getTextBounds("אבג", 0, 3, r)
        val center = y + (r.top + r.bottom) / 2f
        c.save()
        c.translate(x, center - d.bounds.bottom / 2f)
        d.draw(c)
        c.restore()
    }
}

/** עיגול בחירה בסגנון סמסונג (ריק / מלא עם וי) - ברשימת בחירת האזורים */
class CheckDot(context: Context, var on: Boolean) : View(context) {
    private val d = resources.displayMetrics.density
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.6f * d; color = Color.parseColor("#8E8E93") }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5B8AF5") }
    private val tick = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.4f * d; color = Color.BLACK   // וי שחור כמו בסמסונג
        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
    override fun onDraw(c: android.graphics.Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - 1.5f * d
        if (!on) { c.drawCircle(cx, cy, r, ring); return }
        c.drawCircle(cx, cy, r, fill)
        val p = android.graphics.Path().apply {
            moveTo(cx - r * 0.42f, cy + r * 0.02f); lineTo(cx - r * 0.1f, cy + r * 0.34f); lineTo(cx + r * 0.44f, cy - r * 0.3f) }
        c.drawPath(p, tick)
    }
}

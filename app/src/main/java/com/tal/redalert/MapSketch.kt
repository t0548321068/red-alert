package com.tal.redalert

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.tan

/**
 * ציור מיידי של מפת מסך ההתרעה: האזורים בצבע ההתרעה והמיקום שלי, באותו זום ובאותו מקום
 * כמו המפה החיה (כמו fitBounds עם ריווח 14, זום שלם 7-15). מוצג מהשנייה הראשונה,
 * והמפה החיה מכסה אותו כשהיא מוכנה
 */
@SuppressLint("ViewConstructor")
class MapSketch(c: Context, private val polys: List<List<DoubleArray>>, level: Int,
                private val meLat: Double?, private val meLon: Double?, dark: Boolean) : View(c) {
    private val d = resources.displayMetrics.density
    private val color = when (level) { AlertService.LEVEL_PRE -> 0xFFFF9F0A.toInt(); AlertService.LEVEL_END -> 0xFF30D158.toInt(); else -> 0xFFFF3B30.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = this@MapSketch.color; alpha = 153 }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = this@MapSketch.color; strokeJoin = Paint.Join.ROUND }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A84FF.toInt() }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    init { setBackgroundColor(if (dark) 0xFF2C3042.toInt() else 0xFFF5F1E8.toInt()) }

    // Web Mercator, 0..1
    private fun mx(lon: Double) = (lon + 180.0) / 360.0
    private fun my(lat: Double): Double { val r = lat * PI / 180; return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 }

    override fun onDraw(cv: Canvas) {
        val pts = polys.flatten()
        if (pts.isEmpty() || width == 0 || height == 0) return
        // הזום: כל האזורים (והמיקום שלי אם הוא עד 20 ק"מ מהם)
        val fit = ArrayList(pts)
        if (meLat != null && meLon != null) {
            val cLat = (pts.minOf { it[0] } + pts.maxOf { it[0] }) / 2; val cLon = (pts.minOf { it[1] } + pts.maxOf { it[1] }) / 2
            val dy = (meLat - cLat) * 111.0; val dx = (meLon - cLon) * 111.0 * cos(cLat * PI / 180)
            if (dx * dx + dy * dy < 400.0) fit += doubleArrayOf(meLat, meLon)
        }
        val x0 = fit.minOf { mx(it[1]) }; val x1 = fit.maxOf { mx(it[1]) }
        val y0 = fit.minOf { my(it[0]) }; val y1 = fit.maxOf { my(it[0]) }
        val pad = 14 * d
        val wDp = (width - 2 * pad) / d; val hDp = (height - 2 * pad) / d
        val sx = if (x1 > x0) wDp / ((x1 - x0) * 256) else Double.MAX_VALUE
        val sy = if (y1 > y0) hDp / ((y1 - y0) * 256) else Double.MAX_VALUE
        val z = floor(log2(min(sx, sy).coerceAtLeast(1e-9))).coerceIn(7.0, 15.0)
        val scale = 256 * Math.pow(2.0, z) * d   // פיקסלים ליחידה
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        fun px(lon: Double) = ((mx(lon) - cx) * scale + width / 2).toFloat()
        fun py(lat: Double) = ((my(lat) - cy) * scale + height / 2).toFloat()
        for (p in polys) {
            if (p.size == 1) { cv.drawCircle(px(p[0][1]), py(p[0][0]), 8 * d, fill); continue }
            val path = Path()
            p.forEachIndexed { i, pt -> if (i == 0) path.moveTo(px(pt[1]), py(pt[0])) else path.lineTo(px(pt[1]), py(pt[0])) }
            path.close()
            cv.drawPath(path, fill); cv.drawPath(path, stroke)
        }
        if (meLat != null && meLon != null) {
            cv.drawCircle(px(meLon), py(meLat), 8.5f * d, dotRing)
            cv.drawCircle(px(meLon), py(meLat), 5.5f * d, dotFill)
        }
    }
}

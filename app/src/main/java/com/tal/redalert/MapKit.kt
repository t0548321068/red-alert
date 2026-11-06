package com.tal.redalert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

/** חלקים משותפים למפות המובנות (המפה הרגילה ומפת מסך ההתרעה) */
object MapKit {
    val COLORS = intArrayOf(0xFFFF3B30.toInt(), 0xFFFF9F0A.toInt(), 0xFF30D158.toInt())
    fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)
    fun levelHex(level: Int) = hex(COLORS.getOrElse(level) { COLORS[0] })
    /** רקע המפה עד שהרחובות מצוירים - בצבע של מפת הרחובות */
    fun bg(dark: Boolean) = Color.parseColor(if (dark) "#2C3042" else "#F5F1E8")

    @Volatile private var ready = false
    /** פעם אחת לכל הפעלה: המפה, ומטמון הרחובות בטלפון - עד 20MB (הטלפון יכול לנקות) */
    fun init(c: Context) {
        MapLibre.getInstance(c.applicationContext)
        if (ready) return
        ready = true
        try {
            OfflineManager.getInstance(c.applicationContext).setMaximumAmbientCacheSize(20L * 1024 * 1024,
                object : OfflineManager.FileSourceCallback { override fun onSuccess() { } ; override fun onError(message: String) { } })
        } catch (_: Exception) { }
    }

    /** צורת האזור: מצולע (לון, לט) או נקודה */
    fun geometryOf(o: JSONObject): JSONObject? {
        val p = o.optJSONArray("p")
        if (p != null && p.length() > 2) {
            val ring = JSONArray()
            for (i in 0 until p.length()) { val pt = p.getJSONArray(i); ring.put(JSONArray().put(pt.getDouble(1)).put(pt.getDouble(0))) }
            ring.put(ring.getJSONArray(0))
            return JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))
        }
        val c = o.optJSONArray("c") ?: return null
        return point(c.getDouble(0), c.getDouble(1))
    }
    fun point(lat: Double, lon: Double): JSONObject = JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat))
    fun feature(g: JSONObject, props: JSONObject = JSONObject()): JSONObject = JSONObject().put("type", "Feature").put("geometry", g).put("properties", props)
    fun fc(features: List<JSONObject>) = JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features)).toString()

    @Volatile private var namesCache: String? = null
    /**
     * שמות כל אזורי ההתרעה של פיקוד העורף (גם מקומות שאין להם שם במפת הרחובות) - מזום 11.
     * מתחת לשמות של מפת הרחובות, כך ששם שכבר מופיע שם מקבל עדיפות ולא נכפל
     */
    fun addAreaNames(c: Context, st: org.maplibre.android.maps.Style, dark: Boolean) {
        val src = org.maplibre.android.style.sources.GeoJsonSource("area-names")
        st.addSource(src)
        val layer = org.maplibre.android.style.layers.SymbolLayer("area-names", "area-names").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.textField(org.maplibre.android.style.expressions.Expression.get("n")),
            org.maplibre.android.style.layers.PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            org.maplibre.android.style.layers.PropertyFactory.textSize(11f),
            org.maplibre.android.style.layers.PropertyFactory.textColor(if (dark) "#D0D3DA" else "#3C4043"),
            org.maplibre.android.style.layers.PropertyFactory.textHaloColor(if (dark) "#1E2130" else "#FFFFFF"),
            org.maplibre.android.style.layers.PropertyFactory.textHaloWidth(1.5f))
        layer.minZoom = 11f
        if (st.getLayer("place") != null) st.addLayerBelow(layer, "place") else st.addLayer(layer)
        Thread {
            val json = namesCache ?: try {
                val all = AreaData.areas(c)
                val feats = ArrayList<JSONObject>()
                val keys = all.keys()
                while (keys.hasNext()) {
                    val n = keys.next()
                    val cc = all.optJSONObject(n)?.optJSONArray("c") ?: continue
                    feats += feature(point(cc.getDouble(0), cc.getDouble(1)), JSONObject().put("n", n))
                }
                fc(feats).also { namesCache = it }
            } catch (_: Exception) { null } ?: return@Thread
            android.os.Handler(android.os.Looper.getMainLooper()).post { try { src.setGeoJson(json) } catch (_: Exception) { } }
        }.start()
    }

    /** סיכת מיקום אדומה */
    fun pinBitmap(density: Float): Bitmap {
        val w = (26 * density).toInt(); val h = (34 * density).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); cv.scale(density, density)
        val p = Path().apply {
            moveTo(13f, 1f); cubicTo(6.4f, 1f, 1f, 6.3f, 1f, 12.8f); cubicTo(1f, 21.6f, 13f, 33f, 13f, 33f)
            cubicTo(13f, 33f, 25f, 21.6f, 25f, 12.8f); cubicTo(25f, 6.3f, 19.6f, 1f, 13f, 1f); close()
        }
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD50000.toInt() })
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f })
        cv.drawCircle(13f, 12.8f, 4.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return b
    }
}

package com.tal.redalert

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Bundle
import android.view.animation.PathInterpolator
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconTranslate
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos

/**
 * מפת מסך ההתרעה (מסך מלא וחלון קופץ) - מפה מובנית: נפתחת מיד על אזור ההתרעה,
 * עם הרחובות והשמות מהשרת בירושלים. בהתרעה אדומה הסיכות נופלות;
 * התרעה נוספת כשהמסך פתוח - האזורים החדשים נוספים, המפה עוברת אליהם וחוזרת לכולם
 */
class AlertMap(private val a: Activity, savedState: Bundle?, names: List<String>, private val level: Int,
               meLat: Double?, meLon: Double?, dark: Boolean) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private val d = a.resources.displayMetrics.density
    private val shown = LinkedHashSet<String>()
    private val areas = ArrayList<JSONObject>()          // הצורות שעל המפה
    private val pinsStatic = ArrayList<JSONObject>()     // סיכות שכבר נפלו
    private val pts = ArrayList<LatLng>()                // כל נקודות האזורים - לזום
    private val me: LatLng? = if (meLat != null && meLon != null) LatLng(meLat, meLon) else null
    private var pending: List<String> = names
    private var drop: ValueAnimator? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        MapKit.init(a)
        // TextureView: המפה מצוירת יחד עם שאר המסך. ב-SurfaceView (ברירת המחדל) היא הבהבה,
        // כי הספירה לאחור מתעדכנת כל שנייה ומסדרת מחדש את המסך סביבה
        view = MapView(a, org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(a).textureMode(true))
            .apply { setBackgroundColor(MapKit.bg(dark)) }
        view.onCreate(savedState)
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.setMinZoomPreference(7.0); m.setMaxZoomPreference(18.0)
            m.cameraPosition = CameraPosition.Builder().target(LatLng(31.6, 34.9)).zoom(8.0).build()
            m.setStyle(Style.Builder().fromJson(MapStyle.json(dark))) { st ->
                style = st
                MapKit.addAreaNames(a, st, dark)
                layers(st)
                me?.let { (st.getSource("me") as? GeoJsonSource)?.setGeoJson(MapKit.fc(listOf(MapKit.feature(MapKit.point(it.latitude, it.longitude))))) }
                val first = pending; pending = emptyList()
                // מחכים שלמפה יש גודל - אחרת הזום יוצא על כל הארץ
                view.post { addAreas(first, initial = true) }
            }
        }
    }

    private fun layers(st: Style) {
        st.addImage("pin", MapKit.pinBitmap(d))
        listOf("areas", "pins", "drop", "me").forEach { st.addSource(GeoJsonSource(it)) }
        val col = Expression.literal(MapKit.levelHex(level))
        val poly = Expression.eq(Expression.geometryType(), Expression.literal("Polygon"))
        val pt = Expression.eq(Expression.geometryType(), Expression.literal("Point"))
        st.addLayer(FillLayer("areas-fill", "areas").withProperties(fillColor(col), fillOpacity(0.6f)).apply { setFilter(poly) })
        st.addLayer(LineLayer("areas-line", "areas").withProperties(lineColor(col), lineWidth(2f)).apply { setFilter(poly) })
        st.addLayer(CircleLayer("areas-point", "areas").withProperties(circleColor(col), circleRadius(8f), circleStrokeColor(col), circleStrokeWidth(2f)).apply { setFilter(pt) })
        st.addLayer(CircleLayer("me-dot", "me").withProperties(circleColor("#0A84FF"), circleRadius(7f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
        fun pins(id: String, src: String) = SymbolLayer(id, src).withProperties(iconImage("pin"), iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            iconAllowOverlap(true), iconIgnorePlacement(true))
        st.addLayer(pins("pins", "pins"))
        st.addLayer(pins("drop", "drop").withProperties(iconOpacity(0f)))
    }

    /** התרעה נוספת כשהמסך פתוח */
    fun add(names: List<String>) {
        if (style == null) { pending = pending + names; return }
        addAreas(names, initial = false)
    }

    private fun addAreas(names: List<String>, initial: Boolean) {
        val st = style ?: return
        val all = try { AreaData.areas(a) } catch (_: Exception) { return }
        val fresh = names.filter { shown.add(it) }
        if (fresh.isEmpty()) return
        val newPins = ArrayList<JSONObject>(); val newPts = ArrayList<LatLng>()
        fresh.forEach { n ->
            val o = all.optJSONObject(n) ?: return@forEach
            val g = MapKit.geometryOf(o) ?: return@forEach
            areas += MapKit.feature(g)
            val p = o.optJSONArray("p")
            if (p != null) for (i in 0 until p.length()) { val q = p.getJSONArray(i); newPts += LatLng(q.getDouble(0), q.getDouble(1)) }
            o.optJSONArray("c")?.let { c ->
                if (p == null) newPts += LatLng(c.getDouble(0), c.getDouble(1))
                if (level == AlertService.LEVEL_ALERT) newPins += MapKit.feature(MapKit.point(c.getDouble(0), c.getDouble(1)))
            }
        }
        pts += newPts
        (st.getSource("areas") as? GeoJsonSource)?.setGeoJson(MapKit.fc(areas))
        if (initial) {
            map?.moveCamera(fitAll())
            dropPins(newPins)
        } else {
            // קודם לאזור החדש (שם הסיכות נופלות), ואחרי רגע חזרה לכל האזורים
            val m = map ?: return
            ui.removeCallbacksAndMessages(null)
            m.animateCamera(bounds(newPts, 24, 14.0), 900, object : MapLibreMap.CancelableCallback {
                override fun onCancel() { }
                override fun onFinish() {
                    ui.postDelayed({ dropPins(newPins) }, 150)
                    ui.postDelayed({ map?.animateCamera(fitAll(), 1000) }, 1800)
                }
            })
        }
    }

    /** כל האזורים (והמיקום שלי אם הוא עד 20 ק"מ מהם), ריווח 14, זום עד 15 */
    private fun fitAll(): org.maplibre.android.camera.CameraUpdate {
        val fit = ArrayList(pts)
        val m = me
        if (m != null && pts.isNotEmpty()) {
            val cLat = (pts.minOf { it.latitude } + pts.maxOf { it.latitude }) / 2
            val cLon = (pts.minOf { it.longitude } + pts.maxOf { it.longitude }) / 2
            val dy = (m.latitude - cLat) * 111.0; val dx = (m.longitude - cLon) * 111.0 * cos(Math.toRadians(cLat))
            if (dx * dx + dy * dy < 400.0) fit += m
        }
        return bounds(fit, 14, 15.0)
    }

    private fun bounds(list: List<LatLng>, padDp: Int, maxZoom: Double): org.maplibre.android.camera.CameraUpdate {
        if (list.isEmpty()) return CameraUpdateFactory.newLatLngZoom(LatLng(31.6, 34.9), 8.0)
        val b = if (list.size < 2) null else try { LatLngBounds.Builder().includes(list).build() } catch (_: Exception) { null }
        if (b == null) return CameraUpdateFactory.newLatLngZoom(list[0], maxZoom)
        val m = map ?: return CameraUpdateFactory.newLatLngBounds(b, (padDp * d).toInt())
        val pos = m.getCameraForLatLngBounds(b, intArrayOf((padDp * d).toInt(), (padDp * d).toInt(), (padDp * d).toInt(), (padDp * d).toInt()))
            ?: return CameraUpdateFactory.newLatLngBounds(b, (padDp * d).toInt())
        return CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(pos).zoom(minOf(pos.zoom, maxZoom)).build())
    }

    /** הסיכות נופלות מלמעלה וקופצות קצת - כולן יחד, בתנועה אחת */
    private fun dropPins(list: List<JSONObject>) {
        val st = style ?: return
        if (list.isEmpty()) return
        drop?.end()
        (st.getSource("drop") as? GeoJsonSource)?.setGeoJson(MapKit.fc(list))
        val layer = st.getLayer("drop") ?: return
        drop = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = PathInterpolator(0.3f, 1.4f, 0.55f, 1f)
            addUpdateListener { va ->
                val f = va.animatedValue as Float
                layer.setProperties(iconTranslate(arrayOf(0f, -80f * (1 - f))), iconOpacity(minOf(1f, va.animatedFraction * 4)))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: android.animation.Animator) {
                    // נפלו - עוברות לשכבה הקבועה
                    pinsStatic += list
                    style?.let { s ->
                        (s.getSource("pins") as? GeoJsonSource)?.setGeoJson(MapKit.fc(pinsStatic))
                        (s.getSource("drop") as? GeoJsonSource)?.setGeoJson(MapKit.fc(emptyList()))
                        s.getLayer("drop")?.setProperties(iconOpacity(0f))
                    }
                }
            })
            start()
        }
    }

    fun onStart() = view.onStart()
    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onStop() = view.onStop()
    fun onLowMemory() = view.onLowMemory()
    fun onSave(b: Bundle) = view.onSaveInstanceState(b)
    fun onDestroy() {
        ui.removeCallbacksAndMessages(null); drop?.cancel(); drop = null
        view.onDestroy(); map = null; style = null
    }
}

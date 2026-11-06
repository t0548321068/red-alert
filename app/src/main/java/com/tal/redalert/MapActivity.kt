package com.tal.redalert

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
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
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos
import kotlin.math.sin

/**
 * מפת ההתרעות - מפה מובנית (MapLibre), נפתחת מיד ונעלמת לגמרי כשסוגרים אותה.
 * הרחובות והשמות מהשרת שלנו בירושלים, ומה שכבר נראה נשמר בטלפון (עד 20MB).
 * אזורים שהותרעו ב-24 השעות האחרונות צבועים לפי ההתרעה האחרונה בהם; מהרשימה - רק ההתרעה שנלחצה
 */
class MapActivity : Activity() {

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private lateinit var infoCard: TextView
    private lateinit var emptyCard: TextView
    private var me: LatLng? = null
    private var hasBounds = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private val colors = intArrayOf(0xFFFF3B30.toInt(), 0xFFFF9F0A.toInt(), 0xFF30D158.toInt())
    private fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        // רחובות שכבר נטענו נשמרים בטלפון - עד 20MB (הטלפון יכול לנקות)
        try { OfflineManager.getInstance(this).setMaximumAmbientCacheSize(20L * 1024 * 1024, object : OfflineManager.FileSourceCallback {
            override fun onSuccess() { }
            override fun onError(message: String) { }
        }) } catch (_: Exception) { }
        val dark = Prefs.darkTheme(this)
        val bg = Color.parseColor(if (dark) "#000000" else "#F2F2F7")
        val panel = Color.parseColor(if (dark) "#1C1C1E" else "#FFFFFF")
        val text = Color.parseColor(if (dark) "#F2F2F7" else "#1C1C1E")
        window.decorView.setBackgroundColor(bg)

        val mv = MapView(this).apply {
            // רקע המפה עד שהרחובות מופיעים - בצבע של מפת הרחובות
            setBackgroundColor(Color.parseColor(if (dark) "#2C3042" else "#F5F1E8"))
        }
        mapView = mv
        mv.onCreate(savedInstanceState)

        fun card(): TextView = TextView(this).apply {
            setTextColor(text); textSize = 14f; textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(22).toFloat() }
            elevation = dp(4).toFloat()
        }
        infoCard = card().apply { visibility = View.GONE; setOnClickListener { visibility = View.GONE } }
        emptyCard = card().apply { visibility = View.GONE; text = "אין התראות ב-24 השעות האחרונות" }

        // מקרא - כמו במפה הקודמת
        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(22).toFloat() }
            elevation = dp(4).toFloat()
            fun row(c: Int, label: String, dashed: Boolean = false) = addView(LinearLayout(this@MapActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(View(this@MapActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (dashed) setStroke(dp(2), c, dp(2).toFloat(), dp(1).toFloat()) else setColor(c)
                    }
                }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(6) })
                addView(TextView(this@MapActivity).apply { this.text = label; setTextColor(text); textSize = 13f })
            }, LinearLayout.LayoutParams(-2, dp(25)))
            row(colors[0], "ירי / חדירה"); row(colors[1], "התראה מקדימה"); row(colors[2], "האירוע הסתיים")
            row(0xFF0A84FF.toInt(), "המיקום שלי"); row(0xFF0A84FF.toInt(), "האזור שלי עכשיו", dashed = true)
        }
        // כפתור "המיקום שלי"
        val meBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(panel) }
            elevation = dp(4).toFloat()
            addView(android.widget.ImageView(this@MapActivity).apply {
                setImageResource(R.drawable.ic_location)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFF4C7DFF.toInt())
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener {
                val m = me
                if (m != null) map?.animateCamera(CameraUpdateFactory.newLatLngZoom(m, 14.0)) else locate()
            }
        }

        val frame = FrameLayout(this).apply {
            setBackgroundColor(bg)
            addView(mv, FrameLayout.LayoutParams(-1, -1))
            addView(legend, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply { setMargins(dp(12), 0, dp(12), dp(18)) })
            addView(meBtn, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(dp(12), 0, dp(12), dp(18)) })
            addView(emptyCard, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
            addView(infoCard, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(16), dp(12), dp(16), 0) })
            // המפה לא נכנסת מתחת לשורת המצב ולשורת הניווט של הטלפון
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = bg
        if (!dark && android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        setContentView(frame)

        mv.getMapAsync { m ->
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
                addLayers(st)
                drawAlerts()
                locate()
            }
            m.addOnMapClickListener { p -> onTap(m, p); true }
        }
    }

    // ---- שכבות ----
    private fun addLayers(st: Style) {
        st.addImage("pin", pinBitmap())
        st.addSource(GeoJsonSource("alerts"))
        st.addSource(GeoJsonSource("pins"))
        st.addSource(GeoJsonSource("here"))
        st.addSource(GeoJsonSource("me"))
        val color = Expression.get("color")
        st.addLayer(FillLayer("alerts-fill", "alerts").withProperties(fillColor(color), fillOpacity(Expression.get("op")))
            .apply { setFilter(Expression.eq(Expression.geometryType(), Expression.literal("Polygon"))) })
        st.addLayer(LineLayer("alerts-line", "alerts").withProperties(lineColor(color), lineWidth(Expression.get("w")))
            .apply { setFilter(Expression.eq(Expression.geometryType(), Expression.literal("Polygon"))) })
        st.addLayer(CircleLayer("alerts-point", "alerts").withProperties(circleColor(color), circleRadius(8f), circleStrokeColor(color), circleStrokeWidth(2f))
            .apply { setFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point"))) })
        st.addLayer(FillLayer("here-fill", "here").withProperties(fillColor("#0A84FF"), fillOpacity(0.12f)))
        st.addLayer(LineLayer("here-line", "here").withProperties(lineColor("#0A84FF"), lineWidth(2.5f), lineDasharray(arrayOf(2.4f, 2f))))
        st.addLayer(FillLayer("me-acc", "me").withProperties(fillColor("#0A84FF"), fillOpacity(0.15f))
            .apply { setFilter(Expression.eq(Expression.geometryType(), Expression.literal("Polygon"))) })
        st.addLayer(CircleLayer("me-dot", "me").withProperties(circleColor("#0A84FF"), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f))
            .apply { setFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point"))) })
        st.addLayer(SymbolLayer("pins", "pins").withProperties(iconImage("pin"), iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            iconAllowOverlap(true), iconIgnorePlacement(true)))
    }

    /** סיכת מיקום אדומה (כמו במפה הקודמת) */
    private fun pinBitmap(): Bitmap {
        val w = dp(26); val h = dp(34); val s = w / 26f
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); cv.scale(s, s)
        val p = Path().apply {
            moveTo(13f, 1f); cubicTo(6.4f, 1f, 1f, 6.3f, 1f, 12.8f); cubicTo(1f, 21.6f, 13f, 33f, 13f, 33f)
            cubicTo(13f, 33f, 25f, 21.6f, 25f, 12.8f); cubicTo(25f, 6.3f, 19.6f, 1f, 13f, 1f); close()
        }
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD50000.toInt() })
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f })
        cv.drawCircle(13f, 12.8f, 4.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return b
    }

    // ---- נתונים ----
    private class Shown(val name: String, val title: String, val ts: Long, val level: Int)

    private fun geometryOf(o: JSONObject): JSONObject? {
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
    private fun point(lat: Double, lon: Double) = JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat))
    private fun fc(features: List<JSONObject>) = JSONObject().put("type", "FeatureCollection").put("features", JSONArray(features)).toString()

    /** מה מציגים: מהרשימה - רק ההתרעה שנלחצה; אחרת - ההתרעה האחרונה בכל אזור ב-24 השעות האחרונות */
    private fun shownList(): Pair<List<Shown>, Boolean> {
        val focusLevel = intent.getIntExtra("focusLevel", -1)
        val focus = intent.getStringExtra("focus")
        if (focus != null && focusLevel >= 0) {
            val ts = intent.getLongExtra("focusTs", 0L); val title = intent.getStringExtra("focusTitle") ?: ""
            val names = focus.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .flatMap { AreaData.match(this, it) }.distinct()
            return names.map { Shown(it, title, ts, focusLevel) } to true
        }
        val since = System.currentTimeMillis() - 24 * 3600_000L
        val latest = HashMap<String, Shown>()
        Prefs.feed(this).filter { it.ts >= since }.forEach { e ->
            e.body.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { raw ->
                AreaData.match(this, raw).forEach { n ->
                    val prev = latest[n]
                    if (prev == null || prev.ts < e.ts) latest[n] = Shown(n, e.title, e.ts, e.level)
                }
            }
        }
        return latest.values.toList() to false
    }

    private fun drawAlerts() {
        val st = style ?: return
        Thread {
            val (list, focused) = try { shownList() } catch (_: Exception) { emptyList<Shown>() to false }
            val all = try { AreaData.areas(this) } catch (_: Exception) { null }
            val feats = ArrayList<JSONObject>(); val pins = ArrayList<JSONObject>(); val pts = ArrayList<LatLng>()
            list.forEach { s ->
                val o = all?.optJSONObject(s.name) ?: return@forEach
                val g = geometryOf(o) ?: return@forEach
                val col = hex(colors.getOrElse(s.level) { colors[0] })
                feats += JSONObject().put("type", "Feature").put("geometry", g).put("properties", JSONObject()
                    .put("color", col).put("op", if (focused) 0.6 else 0.55).put("w", if (focused) 2.5 else 2.0)
                    .put("name", s.name).put("title", s.title).put("ts", s.ts).put("t", o.optInt("t", -1)))
                val c = o.optJSONArray("c")
                if (c != null) {
                    if (s.level == AlertService.LEVEL_ALERT) pins += JSONObject().put("type", "Feature").put("geometry", point(c.getDouble(0), c.getDouble(1)))
                    if (focused) {
                        val p = o.optJSONArray("p")
                        if (p != null) for (i in 0 until p.length()) { val q = p.getJSONArray(i); pts += LatLng(q.getDouble(0), q.getDouble(1)) }
                        else pts += LatLng(c.getDouble(0), c.getDouble(1))
                    } else pts += LatLng(c.getDouble(0), c.getDouble(1))
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                (st.getSource("alerts") as? GeoJsonSource)?.setGeoJson(fc(feats))
                (st.getSource("pins") as? GeoJsonSource)?.setGeoJson(fc(pins))
                hasBounds = pts.isNotEmpty()
                if (pts.isEmpty()) {
                    emptyCard.visibility = View.VISIBLE
                    ui.postDelayed({ emptyCard.visibility = View.GONE }, 3500)
                } else fit(pts, if (focused) 60 else 50, if (focused) 14.0 else 12.0)
                if (focused && list.isNotEmpty()) showInfo(list.first().name, list.first().title, list.first().ts,
                    all?.optJSONObject(list.first().name)?.optInt("t", -1) ?: -1)
            }
        }.start()
    }

    private fun fit(pts: List<LatLng>, padDp: Int, maxZoom: Double) {
        val m = map ?: return
        if (pts.size == 1 || pts.all { it.latitude == pts[0].latitude && it.longitude == pts[0].longitude }) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(pts[0], maxZoom)); return
        }
        val b = LatLngBounds.Builder().includes(pts).build()
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, dp(padDp)))
        if (m.cameraPosition.zoom > maxZoom) m.moveCamera(CameraUpdateFactory.zoomTo(maxZoom))
    }

    // ---- לחיצה על אזור ----
    private fun onTap(m: MapLibreMap, p: LatLng) {
        val sp = m.projection.toScreenLocation(p)
        val f = m.queryRenderedFeatures(sp, "alerts-fill", "alerts-point").firstOrNull()
        if (f == null) { infoCard.visibility = View.GONE; return }
        showInfo(f.getStringProperty("name") ?: "", f.getStringProperty("title") ?: "",
            f.getNumberProperty("ts")?.toLong() ?: 0L, f.getNumberProperty("t")?.toInt() ?: -1)
    }

    private fun showInfo(name: String, title: String, ts: Long, t: Int) {
        val time = if (ts > 0) android.text.format.DateFormat.format("dd/MM HH:mm", ts).toString() else ""
        val shelter = if (t >= 0) "\n⏱ זמן להגעה: " + (if (t <= 0) "מיידי" else AreaData.shelterText(t)) else ""
        val s = android.text.SpannableString("$name$shelter\n$title\n$time")
        s.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, name.length, 0)
        infoCard.text = s
        infoCard.visibility = View.VISIBLE
        emptyCard.visibility = View.GONE
    }

    // ---- המיקום שלי ----
    private fun locate() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        Weather.lastLocation(this)?.let { show(it) }
        Weather.freshLocation(this) { loc -> if (loc != null) runOnUiThread { show(loc) } }
    }

    private fun show(l: android.location.Location) {
        val st = style ?: return
        if (isDestroyed) return
        val first = me == null
        me = LatLng(l.latitude, l.longitude)
        val feats = ArrayList<JSONObject>()
        if (l.accuracy > 0) {
            // עיגול דיוק
            val ring = JSONArray(); val r = l.accuracy.toDouble()
            for (i in 0..64) {
                val a = 2 * Math.PI * i / 64
                ring.put(JSONArray().put(l.longitude + r * sin(a) / (111_320.0 * cos(Math.toRadians(l.latitude)))).put(l.latitude + r * cos(a) / 110_540.0))
            }
            feats += JSONObject().put("type", "Feature").put("geometry", JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring)))
        }
        feats += JSONObject().put("type", "Feature").put("geometry", point(l.latitude, l.longitude))
        (st.getSource("me") as? GeoJsonSource)?.setGeoJson(fc(feats))
        // האזור שבו אני נמצא עכשיו - מסגרת כחולה מקווקוות
        Thread {
            val here = try { AreaData.areasAt(this, l.latitude, l.longitude, 0.0) } catch (_: Exception) { emptyList() }
            val all = try { AreaData.areas(this) } catch (_: Exception) { null }
            val hf = here.mapNotNull { n -> all?.optJSONObject(n)?.let { o -> geometryOf(o)?.takeIf { it.getString("type") == "Polygon" } } }
                .map { JSONObject().put("type", "Feature").put("geometry", it) }
            runOnUiThread { if (!isDestroyed) (st.getSource("here") as? GeoJsonSource)?.setGeoJson(fc(hf)) }
        }.start()
        if (first && !hasBounds) map?.moveCamera(CameraUpdateFactory.newLatLngZoom(me!!, 12.0))
    }

    // ---- מחזור החיים של המפה ----
    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView?.onSaveInstanceState(outState) }
    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        mapView?.onDestroy(); mapView = null; map = null; style = null
        super.onDestroy()
    }
}

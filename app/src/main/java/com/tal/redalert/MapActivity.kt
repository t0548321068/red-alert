package com.tal.redalert

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
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
    /** נפתח מהרשימה על התרעה אחת - ואחרי לחיצה על "כל ההתרעות" עוברים לכולן */
    private var showAll = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private val colors = MapKit.COLORS
    /** נפתח מההתרעות האחרונות: שורת כותרת בצבע ההתרעה ומסך תחתון עם הפירוט */
    private val sheetMode get() = intent.getStringExtra("focus") != null && intent.getIntExtra("focusLevel", -1) >= 0
    /** כמה מהמפה מכוסה מלמטה על ידי המסך התחתון - המצלמה מתמרכזת מעליו */
    private var sheetPeek = 0
    private var lastPts: List<LatLng> = emptyList()
    private var sheetView: View? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapKit.init(this)
        val dark = Prefs.darkTheme(this)
        val bg = Color.parseColor(if (dark) "#000000" else "#F2F2F7")
        val panel = Color.parseColor(if (dark) "#1C1C1E" else "#FFFFFF")
        val ink = Color.parseColor(if (dark) "#F2F2F7" else "#1C1C1E")
        window.decorView.setBackgroundColor(bg)

        // foregroundLoadColor: עד שהסגנון נטען המפה מצוירת בצבע הזה (ברירת המחדל בהירה - הבהוב לבן)
        val mv = MapView(this, org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(this)
            .foregroundLoadColor(MapKit.bg(dark))).apply {
            // רקע המפה עד שהרחובות מופיעים - בצבע של מפת הרחובות
            setBackgroundColor(MapKit.bg(dark))
        }
        mapView = mv
        mv.onCreate(savedInstanceState)

        fun card(): TextView = TextView(this).apply {
            setTextColor(ink); textSize = 14f; textDirection = View.TEXT_DIRECTION_RTL
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
                addView(TextView(this@MapActivity).apply { this.text = label; setTextColor(ink); textSize = 13f })
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

        // מעל "המיקום שלי": כל ההתרעות של 24 השעות האחרונות - המפה חוזרת להציג את כולן
        val allBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(panel) }
            elevation = dp(4).toFloat()
            contentDescription = "כל ההתרעות"
            addView(android.widget.ImageView(this@MapActivity).apply {
                setImageResource(R.drawable.ic_map_all)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFF4C7DFF.toInt())
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener {
                showAll = true
                infoCard.visibility = View.GONE
                drawAlerts()
            }
        }

        val sheet = sheetMode
        val frame = FrameLayout(this).apply {
            setBackgroundColor(bg)
            addView(mv, FrameLayout.LayoutParams(-1, -1))
            if (!sheet) addView(legend, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply { setMargins(dp(12), 0, dp(12), dp(18)) })
            // עם המסך התחתון (פירוט התרעה מההתרעות האחרונות) - בלי כפתורי "המיקום שלי" ו"כל ההתרעות"
            if (!sheet) {
                addView(meBtn, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(dp(12), 0, dp(12), dp(18)) })
                addView(allBtn, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(dp(12), 0, dp(12), dp(18 + 50 + 12)) })
            }
            addView(emptyCard, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
            addView(infoCard, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(16), dp(12), dp(16), 0) })
        }
        val level = intent.getIntExtra("focusLevel", -1)
        val rawTitle = intent.getStringExtra("focusTitle") ?: ""
        val barTitle = if (level == AlertService.LEVEL_PRE && !rawTitle.startsWith("התרעה מקדימה")) "התרעה מקדימה" else rawTitle
        if (sheet) {
            val areas = (intent.getStringExtra("focus") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val tArr = intent.getLongArrayExtra("focusTimes")
            val times = if (tArr != null && tArr.size == areas.size) areas.indices.associate { areas[it] to tArr[it] } else emptyMap()
            val shownAreas = areas.map { it.replace("ברחבי הארץ", AreaData.ALL_COUNTRY) }
            val shownTimes = times.mapKeys { it.key.replace("ברחבי הארץ", AreaData.ALL_COUNTRY) }
            sheetView = AlertSheet.build(this, barTitle, level, intent.getLongExtra("focusTs", 0L), shownAreas, shownTimes, dark,
                intent.getStringExtra("focusSrc") ?: "") { peek ->
                sheetPeek = peek; if (lastPts.isNotEmpty() && !showAll) fit(lastPts, 60, 14.0)
            }
            frame.addView(sheetView, FrameLayout.LayoutParams(-1, -1))
        }
        // המפה לא נכנסת מתחת לשורת המצב ולשורת הניווט של הטלפון
        val root: View = if (sheet) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(AlertUi.color(level)))
            addView(AlertSheet.bar(this@MapActivity, barTitle, level), LinearLayout.LayoutParams(-1, dp(60)))
            addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        } else frame
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, if (sheet) 0 else insets.systemWindowInsetBottom)
            // המסך התחתון נמשך מתחת לשורת הניווט (באותו צבע), והתוכן שלו מעליה
            @Suppress("DEPRECATION")
            if (sheet) sheetView?.let { it.setPadding(0, 0, 0, insets.systemWindowInsetBottom); it.requestLayout() }
            insets
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = if (sheet) Color.parseColor(AlertUi.color(level)) else bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = bg
        if (!dark && android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        setContentView(root)

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
                MapKit.addAreaNames(this, st, dark)
                addLayers(st)
                drawAlerts()
                locate()
            }
            m.addOnMapClickListener { p -> onTap(m, p); true }
        }
    }

    // ---- שכבות ----
    private fun addLayers(st: Style) {
        st.addImage("pin", MapKit.pinBitmap(resources.displayMetrics.density))
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

    // ---- נתונים ----
    private class Shown(val name: String, val title: String, val ts: Long, val level: Int)

    /** מה מציגים: מהרשימה - רק ההתרעה שנלחצה; אחרת - ההתרעה האחרונה בכל אזור ב-24 השעות האחרונות */
    private fun shownList(): Pair<List<Shown>, Boolean> {
        val focusLevel = intent.getIntExtra("focusLevel", -1)
        val focus = intent.getStringExtra("focus")
        if (!showAll && focus != null && focusLevel >= 0) {
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
                val g = MapKit.geometryOf(o) ?: return@forEach
                val col = MapKit.levelHex(s.level)
                feats += JSONObject().put("type", "Feature").put("geometry", g).put("properties", JSONObject()
                    .put("color", col).put("op", if (focused) 0.6 else 0.55).put("w", if (focused) 2.5 else 2.0)
                    .put("name", s.name).put("title", s.title).put("ts", s.ts).put("t", o.optInt("t", -1)))
                val c = o.optJSONArray("c")
                if (c != null) {
                    if (s.level == AlertService.LEVEL_ALERT) pins += JSONObject().put("type", "Feature").put("geometry", MapKit.point(c.getDouble(0), c.getDouble(1)))
                    if (focused) {
                        val p = o.optJSONArray("p")
                        if (p != null) for (i in 0 until p.length()) { val q = p.getJSONArray(i); pts += LatLng(q.getDouble(0), q.getDouble(1)) }
                        else pts += LatLng(c.getDouble(0), c.getDouble(1))
                    } else pts += LatLng(c.getDouble(0), c.getDouble(1))
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                (st.getSource("alerts") as? GeoJsonSource)?.setGeoJson(MapKit.fc(feats))
                (st.getSource("pins") as? GeoJsonSource)?.setGeoJson(MapKit.fc(pins))
                hasBounds = pts.isNotEmpty()
                if (pts.isEmpty()) {
                    emptyCard.visibility = View.VISIBLE
                    ui.postDelayed({ emptyCard.visibility = View.GONE }, 3500)
                } else { lastPts = pts; fit(pts, if (focused) 60 else 50, if (focused) 14.0 else 12.0) }
                if (focused && list.isNotEmpty() && !sheetMode) showInfo(list.first().name, list.first().title, list.first().ts,
                    all?.optJSONObject(list.first().name)?.optInt("t", -1) ?: -1)
            }
        }.start()
    }

    private fun fit(pts: List<LatLng>, padDp: Int, maxZoom: Double) {
        val m = map ?: return
        val bottom = if (showAll) 0 else sheetPeek   // המסך התחתון מכסה את חלק המפה למטה
        if (pts.size == 1 || pts.all { it.latitude == pts[0].latitude && it.longitude == pts[0].longitude }) {
            if (bottom == 0) { m.moveCamera(CameraUpdateFactory.newLatLngZoom(pts[0], maxZoom)); return }
            val p = pts[0]
            fit(listOf(LatLng(p.latitude - 0.01, p.longitude - 0.01), LatLng(p.latitude + 0.01, p.longitude + 0.01)), padDp, maxZoom); return
        }
        val b = LatLngBounds.Builder().includes(pts).build()
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, dp(padDp), dp(padDp), dp(padDp), dp(padDp) + bottom))
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
        feats += JSONObject().put("type", "Feature").put("geometry", MapKit.point(l.latitude, l.longitude))
        (st.getSource("me") as? GeoJsonSource)?.setGeoJson(MapKit.fc(feats))
        // האזור שבו אני נמצא עכשיו - מסגרת כחולה מקווקוות
        Thread {
            val here = try { AreaData.areasAt(this, l.latitude, l.longitude, 0.0) } catch (_: Exception) { emptyList() }
            val all = try { AreaData.areas(this) } catch (_: Exception) { null }
            val hf = here.mapNotNull { n -> all?.optJSONObject(n)?.let { o -> MapKit.geometryOf(o)?.takeIf { it.getString("type") == "Polygon" } } }
                .map { JSONObject().put("type", "Feature").put("geometry", it) }
            runOnUiThread { if (!isDestroyed) (st.getSource("here") as? GeoJsonSource)?.setGeoJson(MapKit.fc(hf)) }
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

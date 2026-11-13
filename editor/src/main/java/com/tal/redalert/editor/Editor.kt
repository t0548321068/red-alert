package com.tal.redalert.editor

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * שכבת העריכה מעל המסכים האמיתיים של צופר חירום.
 * כל חלון (המסך הראשי, כל עמוד הגדרות, מסכי ההתרעה...) מקבל Layer: כפתור צף (✎ / רשימה),
 * ובמצב עריכה - לחיצה על רכיב בוחרת אותו (גלילה עדיין עובדת), וחלון העריכה משנה אותו בפועל.
 * השינויים נשמרים לפי המסך והמסלול לרכיב, ומוחלים שוב כל חצי שנייה (העמודים נבנים מחדש כל הזמן)
 */
object Editor {
    lateinit var app: Application
    private val ui = Handler(Looper.getMainLooper())
    /** מצב עריכה: לחיצה בוחרת רכיב. כבוי: האפליקציה עובדת רגיל (מעבר בין מסכים) */
    @Volatile var on = false
    private val layers = WeakHashMap<View, Layer>()
    private val marks = WeakHashMap<View, Drawable>()
    private val activities = WeakHashMap<Activity, Boolean>()

    fun start(a: Application) {
        app = a
        Store.load(a)
        a.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(x: Activity, b: android.os.Bundle?) { activities[x] = true }
            override fun onActivityDestroyed(x: Activity) { activities.remove(x) }
            override fun onActivityStarted(x: Activity) {}
            override fun onActivityResumed(x: Activity) {}
            override fun onActivityPaused(x: Activity) {}
            override fun onActivityStopped(x: Activity) {}
            override fun onActivitySaveInstanceState(x: Activity, b: android.os.Bundle) {}
        })
        ui.post(loop)
    }

    private val loop = object : Runnable {
        override fun run() {
            try { scan(); reapply() } catch (_: Exception) { }
            ui.postDelayed(this, 500)
        }
    }
    fun scanSoon() { ui.post { try { scan() } catch (_: Exception) { } } }

    // ---- חלונות ----
    private fun roots(): List<View> =
        if (Build.VERSION.SDK_INT >= 29) android.view.inspector.WindowInspector.getGlobalWindowViews()
        else activities.keys.mapNotNull { it.window?.decorView }

    private fun scan() {
        val w = app.resources.displayMetrics.widthPixels
        for (r in roots()) {
            if (r !is FrameLayout || layers.containsKey(r) || !r.isAttachedToWindow) continue
            if (r.width in 1 until (w * 0.6).toInt()) continue   // הודעות קטנות (Toast) - לא
            val l = Layer(r)
            layers[r] = l
            r.addView(l, FrameLayout.LayoutParams(-1, -1))
        }
        // השכבה תמיד מעל הכל (חלון שהוסיף משהו אחריה)
        for ((r, l) in layers) if (r is ViewGroup && r.indexOfChild(l) != r.childCount - 1) l.bringToFront()
    }
    fun refreshMenus() = ui.post { layers.values.forEach { it.refreshPill(); it.menu?.refreshFooter() } }

    // ---- מסך ומסלול ----
    fun activityOf(v: View): Activity? {
        var c: Context? = v.context
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
    private val NAMES = mapOf("MainActivity" to "מסך ראשי", "AlertActivity" to "מסך התרעה מלא", "AlertPopupActivity" to "פופ-אפ התרעה",
        "MapActivity" to "מפה", "ClockActivity" to "שעון לילה")

    /** מזהה המסך: "מחלקה|שם בעברית". עמוד שנפתח כחלון - לפי הכותרת שלו */
    fun screenOf(root: View): String {
        val a = activityOf(root)
        val cls = a?.javaClass?.simpleName ?: "?"
        val name = NAMES[cls] ?: cls
        val own = a?.window?.decorView === root
        val title = firstText(root)
        return when {
            own && cls == "MainActivity" -> "$cls|$name"
            own -> "$cls|$name – $title"
            else -> "$cls.page|$title"
        }
    }
    private fun firstText(v: View): String {
        if (v is Layer || v.visibility != View.VISIBLE) return ""
        if (v is TextView && !v.text.isNullOrBlank()) return v.text.toString().replace('\n', ' ').take(40)
        if (v is ViewGroup) for (i in 0 until v.childCount) firstText(v.getChildAt(i)).let { if (it.isNotEmpty()) return it }
        return ""
    }
    fun pathOf(root: View, v: View): String {
        val l = mutableListOf<Int>(); var c = v
        while (c !== root) { val p = c.parent as? ViewGroup ?: return ""; l.add(0, p.indexOfChild(c)); c = p }
        return l.joinToString(".")
    }
    private fun find(root: View, path: String): View? {
        var c = root
        if (path.isEmpty()) return c
        for (s in path.split('.')) { val i = s.toIntOrNull() ?: return null; c = (c as? ViewGroup)?.getChildAt(i) ?: return null }
        return c
    }

    /** הרכיב העמוק ביותר בנקודה (קואורדינטות מסך) */
    fun hit(root: View, sx: Int, sy: Int): View? {
        fun inside(v: View): Boolean {
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            return sx >= loc[0] && sy >= loc[1] && sx < loc[0] + v.width && sy < loc[1] + v.height
        }
        fun go(v: View): View? {
            if (v is Layer || v.visibility != View.VISIBLE || v.alpha == 0f || !inside(v)) return null
            if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) go(v.getChildAt(i))?.let { return it }
            return v
        }
        var r = go(root) ?: return null
        // מרווח ריק (View בלי רקע) - הרכיב שמסביב
        while (r.javaClass == View::class.java && r.background == null && r.parent is View && r.parent !== root) r = r.parent as View
        return r
    }

    fun kindOf(v: View) = when (v) { is TextView -> "t"; is ImageView -> "i"; else -> "b" }
    fun labelOf(v: View): String = when (v) {
        is TextView -> v.text.toString().replace('\n', ' ').take(50).ifBlank { "טקסט" }
        is ImageView -> "אייקון" + ((v.parent as? ViewGroup)?.let { firstText(it) }?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
        else -> "רכיב" + firstText(v).takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
    }

    // ---- ערכים ----
    private val d get() = app.resources.displayMetrics.density
    private val sd get() = app.resources.displayMetrics.scaledDensity
    fun hex(c: Int) = if (Color.alpha(c) == 255) "#%06X".format(c and 0xFFFFFF) else "#%08X".format(c)
    private fun r1(f: Float) = Math.round(f * 10) / 10f

    /** הערכים המקוריים של רכיב (לפני שינוי) */
    fun baseOf(v: View): JSONObject {
        val o = JSONObject()
        when (v) {
            is TextView -> o.put("color", hex(v.currentTextColor)).put("size", r1(v.textSize / sd).toDouble()).put("text", v.text.toString())
                .put("bold", v.typeface?.isBold == true).put("align", alignOf(v)).put("valign", valignOf(v))
            is ImageView -> o.put("color", v.imageTintList?.defaultColor?.let { hex(it) } ?: "מקורי").put("size", r1(v.width / d).toDouble())
            else -> {
                val bg = v.background
                o.put("color", when (bg) {
                    is GradientDrawable -> bg.color?.defaultColor?.let { hex(it) } ?: "שקוף"
                    is ColorDrawable -> hex(bg.color)
                    null -> "שקוף"
                    else -> "אחר"
                })
                o.put("radius", r1(((bg as? GradientDrawable)?.cornerRadius ?: 0f) / d).toDouble())
            }
        }
        return o
    }

    /** היישור בפועל של טקסט (ימין / מרכז / שמאל) */
    fun alignOf(v: TextView): String {
        val h = Gravity.getAbsoluteGravity(v.gravity, v.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK
        return when (h) { Gravity.CENTER_HORIZONTAL -> "center"; Gravity.LEFT -> "left"; else -> "right" }
    }

    /** המרכוז האנכי בפועל (למעלה / מרכז / למטה) */
    fun valignOf(v: TextView): String = when (v.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
        Gravity.CENTER_VERTICAL -> "center"; Gravity.BOTTOM -> "bottom"; else -> "top" }

    private fun parse(c: String?): Int? = try { if (c == null || c[0] != '#') null else Color.parseColor(c) } catch (_: Exception) { null }

    /** מחיל שינוי על הרכיב (רק מה שבאמת שונה - בלי לצייר מחדש סתם) */
    fun applyTo(v: View, e: Edit) {
        val color = parse(e.color)
        when (v) {
            is TextView -> {
                if (color != null && v.currentTextColor != color) v.setTextColor(color)
                e.size?.let { if (abs(v.textSize - it * sd) > 0.5f) v.setTextSize(TypedValue.COMPLEX_UNIT_SP, it) }
                e.text?.let { if (v.text.toString() != it) v.text = it }
                e.align?.let { val g = when (it) { "right" -> Gravity.RIGHT; "left" -> Gravity.LEFT; else -> Gravity.CENTER_HORIZONTAL }
                    if (alignOf(v) != it) { v.textAlignment = View.TEXT_ALIGNMENT_GRAVITY; v.gravity = (v.gravity and Gravity.HORIZONTAL_GRAVITY_MASK.inv() and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK.inv()) or g } }
                e.valign?.let { val g = when (it) { "top" -> Gravity.TOP; "bottom" -> Gravity.BOTTOM; else -> Gravity.CENTER_VERTICAL }
                    if (valignOf(v) != it) v.gravity = (v.gravity and Gravity.VERTICAL_GRAVITY_MASK.inv()) or g }
                e.bold?.let { if ((v.typeface?.isBold == true) != it) v.setTypeface(android.graphics.Typeface.create(v.typeface, if (it) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)) }
            }
            is ImageView -> {
                if (color != null && v.imageTintList?.defaultColor != color) v.imageTintList = ColorStateList.valueOf(color)
                e.size?.let { val px = (it * d).toInt(); val lp = v.layoutParams
                    if (lp != null && (lp.width != px || lp.height != px)) { lp.width = px; lp.height = px; v.layoutParams = lp } }
            }
            else -> {
                if (color != null) when (val bg = v.background) {
                    is GradientDrawable -> if (bg.color?.defaultColor != color) { bg.mutate(); bg.setColor(color) }
                    is ColorDrawable -> if (bg.color != color) bg.color = color
                    else -> v.setBackgroundColor(color)
                }
                e.radius?.let { val g = v.background as? GradientDrawable
                    if (g != null && abs(g.cornerRadius - it * d) > 0.5f) { g.mutate(); g.cornerRadius = it * d } }
            }
        }
        if (v.translationX != e.dx * d) v.translationX = e.dx * d
        if (v.translationY != e.dy * d) v.translationY = e.dy * d
    }

    /** חזרה לערכים המקוריים (ביטול). fields = רק השדות האלה (null = הכל) */
    fun restore(v: View, e: Edit, fields: Set<String>? = null) {
        val b = e.base
        fun want(f: String) = fields == null || f in fields
        val back = Edit(e.screen, e.path, e.cls, e.label, e.kind, b).apply {
            if (want("color")) color = b.optString("color").takeIf { it.startsWith("#") }
            if (want("size")) size = b.optDouble("size", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            if (want("radius")) radius = b.optDouble("radius", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            if (want("text")) text = if (b.has("text")) b.optString("text") else null
            if (want("bold") && b.has("bold")) bold = b.optBoolean("bold")
            if (want("align") && b.has("align")) align = b.optString("align")
            if (want("valign") && b.has("valign")) valign = b.optString("valign")
            if (!want("move")) { dx = e.dx; dy = e.dy }
        }
        applyTo(v, back)
        if (want("color") && v is ImageView && b.optString("color") == "מקורי") v.imageTintList = null
    }

    /** כל השינויים, על כל החלונות הפתוחים, + סימון כתום על רכיב ששונה */
    private fun reapply() {
        val seen = HashSet<View>()
        for ((root, layer) in layers) {
            if (!root.isAttachedToWindow) continue
            val sk = screenOf(root)
            layer.screen = sk
            for (e in Store.edits.values) {
                if (e.screen != sk) continue
                val v = find(root, e.path) ?: continue
                if (v.javaClass.simpleName != e.cls) continue
                applyTo(v, e)
                if (e.changed() && layer.selected?.get() !== v) { mark(v, true); seen += v }
            }
        }
        marks.keys.toList().forEach { v -> if (v !in seen && Layer.anySelected() !== v) mark(v, false) }
    }

    private fun mark(v: View, on: Boolean) {
        val old = marks[v]
        if (!on) { old?.let { v.overlay.remove(it) }; marks.remove(v); return }
        val m = old ?: GradientDrawable().apply { setStroke((2 * d).toInt(), Color.parseColor("#FF9F0A")); setColor(Color.TRANSPARENT) }
            .also { v.overlay.add(it); marks[v] = it }
        if (m.bounds.width() != v.width || m.bounds.height() != v.height) m.setBounds(0, 0, v.width, v.height)
    }
    fun unmark(v: View) = mark(v, false)

    /** תמונה קטנה של הרכיב (לזיהוי בשליחה) - JPEG עד 360 פיקסלים */
    fun shot(v: View): String? = try {
        if (v.width <= 0 || v.height <= 0) null else {
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Color.parseColor("#1B1B1D")); v.draw(c)
            val s = minOf(1f, 360f / maxOf(v.width, v.height))
            val sb = if (s < 1f) Bitmap.createScaledBitmap(b, (v.width * s).toInt().coerceAtLeast(1), (v.height * s).toInt().coerceAtLeast(1), true) else b
            val out = java.io.ByteArrayOutputStream(); sb.compress(Bitmap.CompressFormat.JPEG, 70, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    } catch (_: Exception) { null }

    fun dp(v: Int) = (v * d).toInt()
    val touchSlop get() = ViewConfiguration.get(app).scaledTouchSlop

    /** מעביר את האירוע לתוכן של החלון (מתחת לשכבה). target = מי שקיבל את הלחיצה; מחזיר את היעד */
    fun forward(root: ViewGroup, layer: View, ev: MotionEvent, target: View?): View? {
        fun send(c: View): Boolean {
            val e = MotionEvent.obtain(ev); e.offsetLocation(-c.left.toFloat(), -c.top.toFloat())
            val h = c.dispatchTouchEvent(e); e.recycle(); return h
        }
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) { target?.let { send(it) }; return target }
        for (i in root.childCount - 1 downTo 0) {
            val c = root.getChildAt(i)
            if (c === layer || c.visibility != View.VISIBLE) continue
            if (send(c)) return c
        }
        return null
    }
}

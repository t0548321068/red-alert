package com.tal.redalert.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.abs

/** צבעים וכלים קטנים לממשק העריכה (לא קשור לעיצוב של צופר חירום) */
internal object Ui {
    val BG = Color.parseColor("#1C1C1E"); val CARD = Color.parseColor("#2C2C2E"); val TEXT = Color.parseColor("#F2F2F2")
    val GRAY = Color.parseColor("#8E8E93"); val BLUE = Color.parseColor("#0A84FF"); val ORANGE = Color.parseColor("#FF9F0A")
    val RED = Color.parseColor("#FF6961"); val GREEN = Color.parseColor("#30D158")
    fun dp(v: Int) = Editor.dp(v)
    fun round(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    fun tv(c: Context, s: String, size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        textDirection = View.TEXT_DIRECTION_RTL
    }
    fun btn(c: Context, s: String, bg: Int, fg: Int = TEXT, bold: Boolean = false, click: () -> Unit) = tv(c, s, 14f, fg, bold).apply {
        gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)); background = round(bg, 12)
        setOnClickListener { click() }
    }
}

/**
 * השכבה בכל חלון: כפתור צף, ובמצב עריכה - בחירת רכיב בלחיצה (גלילה עוברת לתוכן כרגיל),
 * חלון העריכה ועמוד "עריכת צופר" (שינויים, שליחה, הדמיות)
 */
@SuppressLint("ViewConstructor")
class Layer(val root: FrameLayout) : FrameLayout(root.context) {
    var screen = ""
    var selected: WeakReference<View>? = null
    private var panel: Panel? = null
    var menu: Menu? = null
    private val pill = LinearLayout(context)
    private val penBtn = Ui.tv(context, "✎", 20f, Color.WHITE, true)
    private val listBtn = Ui.tv(context, "≡", 16f, Color.WHITE, true)

    companion object {
        private val all = java.util.WeakHashMap<Layer, Boolean>()
        fun anySelected(): View? = all.keys.firstNotNullOfOrNull { it.selected?.get() }
        /** מיקום הכפתור הצף - משותף לכל החלונות */
        var pillX = 0f; var pillY = 0f
    }

    init {
        all[this] = true
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        fun circle(t: TextView) = t.apply { gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        pill.background = Ui.round(Color.parseColor("#E61C1C1E"), 26)
        pill.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4))
        pill.elevation = Ui.dp(8).toFloat()
        pill.addView(circle(penBtn), LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)))
        pill.addView(circle(listBtn), LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)).apply { marginStart = Ui.dp(4) })
        addView(pill, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply { setMargins(Ui.dp(14), 0, Ui.dp(14), Ui.dp(90)) })
        pill.translationX = pillX; pill.translationY = pillY
        dragPill()
        refreshPill()
    }

    fun refreshPill() {
        (penBtn.background as GradientDrawable).setColor(if (Editor.on) Ui.BLUE else Color.parseColor("#3A3A3C"))
        val n = Store.count()
        listBtn.text = if (n > 0) n.toString() else "≡"
        listBtn.setTextColor(if (n > 0) Color.parseColor("#3A2400") else Color.WHITE)
        (listBtn.background as GradientDrawable).setColor(if (n > 0) Ui.ORANGE else Color.parseColor("#3A3A3C"))
        for (l in all.keys) if (l !== this && l.isAttachedToWindow) l.syncPill()
    }
    private fun syncPill() {
        (penBtn.background as GradientDrawable).setColor(if (Editor.on) Ui.BLUE else Color.parseColor("#3A3A3C"))
        val n = Store.count()
        listBtn.text = if (n > 0) n.toString() else "≡"
        listBtn.setTextColor(if (n > 0) Color.parseColor("#3A2400") else Color.WHITE)
        (listBtn.background as GradientDrawable).setColor(if (n > 0) Ui.ORANGE else Color.parseColor("#3A3A3C"))
        pill.translationX = pillX; pill.translationY = pillY
    }

    /** הכפתור הצף: לחיצה על ✎ = מצב עריכה, על הרשימה = עמוד עריכת צופר. גרירה = הזזה */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragPill() {
        var sx = 0f; var sy = 0f; var tx = 0f; var ty = 0f; var moved = false
        val l = OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; tx = pill.translationX; ty = pill.translationY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) > Editor.touchSlop || abs(e.rawY - sy) > Editor.touchSlop) moved = true
                    if (moved) { pill.translationX = tx + e.rawX - sx; pill.translationY = ty + e.rawY - sy; pillX = pill.translationX; pillY = pill.translationY }
                }
                MotionEvent.ACTION_UP -> if (!moved) { if (v === penBtn) toggle() else openMenu() }
            }
            true
        }
        penBtn.setOnTouchListener(l); listBtn.setOnTouchListener(l)
    }

    private fun toggle() {
        Editor.on = !Editor.on
        if (!Editor.on) closePanel(save = true)
        refreshPill()
        android.widget.Toast.makeText(context, if (Editor.on) "מצב עריכה: לוחצים על רכיב" else "מצב רגיל: האפליקציה עובדת כרגיל", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun openMenu() {
        closePanel(save = true)
        if (menu == null) { menu = Menu(this); addView(menu, LayoutParams(-1, -1)) }
        menu?.rebuild()
        pill.bringToFront()
        pill.visibility = View.GONE
    }
    fun closeMenu() { menu?.let { removeView(it) }; menu = null; pill.visibility = View.VISIBLE; refreshPill() }

    // ---- בחירת רכיב: לחיצה קצרה בוחרת, גרירה גוללת את התוכן ----
    private var target: View? = null
    private var downX = 0f; private var downY = 0f; private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!Editor.on || menu != null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; moved = false; target = Editor.forward(root, this, ev, null) }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > Editor.touchSlop || abs(ev.y - downY) > Editor.touchSlop) moved = true
                Editor.forward(root, this, ev, target)
            }
            MotionEvent.ACTION_UP -> {
                if (moved) Editor.forward(root, this, ev, target)
                else {
                    val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
                    Editor.forward(root, this, c, target); c.recycle()
                    val loc = IntArray(2); getLocationOnScreen(loc)
                    Editor.hit(root, (loc[0] + ev.x).toInt(), (loc[1] + ev.y).toInt())?.let { select(it) }
                }
                target = null
            }
            MotionEvent.ACTION_CANCEL -> { Editor.forward(root, this, ev, target); target = null }
        }
        return true
    }

    /** בחירת רכיב: יוצרים את השינוי שלו (עם הערכים המקוריים) ופותחים את חלון העריכה */
    fun select(v: View) {
        closePanel(save = true)
        val path = Editor.pathOf(root, v)
        val sk = Editor.screenOf(root)
        val key = "$sk/$path"
        val e = Store.edits[key]?.takeIf { it.cls == v.javaClass.simpleName }
            ?: Edit(sk, path, v.javaClass.simpleName, Editor.labelOf(v), Editor.kindOf(v), Editor.baseOf(v))
                .also { it.shot = Editor.shot(v); Store.edits[key] = it }   // תמונה של הרכיב כמו שהוא - לזיהוי בשליחה
        selected = WeakReference(v)
        Editor.unmark(v)
        panel = Panel(this, v, e).also { addView(it) }
        pill.bringToFront()
    }

    /** סגירת חלון העריכה: שמירה, או ביטול (חזרה למה שהיה לפני הפתיחה) */
    fun closePanel(save: Boolean) {
        val p = panel ?: return
        panel = null
        p.finish(save)
        removeView(p)
        selected = null
        Store.save(context)
        refreshPill()
    }

    /** "רכיב מעל" */
    fun selectParent(v: View) {
        val p = v.parent as? View ?: return
        if (p === root) return
        select(p)
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); all.remove(this) }

    fun insets(): Pair<Int, Int> {
        @Suppress("DEPRECATION")
        val i = root.rootWindowInsets
        @Suppress("DEPRECATION")
        return (i?.systemWindowInsetTop ?: Ui.dp(24)) to (i?.systemWindowInsetBottom ?: 0)
    }

    override fun generateDefaultLayoutParams() = LayoutParams(-1, -2)
    fun removeChild(v: View) { (v.parent as? ViewGroup)?.removeView(v) }
}

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
        /** מיקום הכפתור הצף - משותף לכל החלונות. גרירה חופשית, וחוזר למקום הקבוע בכל חזרה לאפליקציה */
        var pillX = 0f; var pillY = 0f
        /** הכפתורים הוסתרו (נגררו ל-✕) - עד היציאה הבאה מהאפליקציה */
        var pillHidden = false
        fun resetPill() {
            pillX = 0f; pillY = 0f; pillHidden = false
            all.keys.forEach { it.pill.translationX = 0f; it.pill.translationY = 0f; if (it.menu == null) it.pill.visibility = View.VISIBLE }
        }
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
        if (pillHidden) pill.visibility = View.GONE
    }

    /** ✕ בתחתית המסך - מופיע רק בזמן גרירה. שחרור הכפתורים עליו מסתיר אותם עד היציאה הבאה מהאפליקציה */
    private val bin = android.widget.TextView(context).apply {
        text = "✕"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = Ui.round(Color.parseColor("#CC3A3A3C"), 28)
        elevation = Ui.dp(8).toFloat()
        visibility = View.GONE
    }
    private fun overBin(): Boolean {
        if (bin.width == 0) return false
        val p = IntArray(2); pill.getLocationOnScreen(p)
        val b = IntArray(2); bin.getLocationOnScreen(b)
        val cx = p[0] + pill.width / 2f; val cy = p[1] + pill.height / 2f
        val bx = b[0] + bin.width / 2f; val by = b[1] + bin.height / 2f
        return abs(cx - bx) < Ui.dp(60) && abs(cy - by) < Ui.dp(60)
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
                    if (moved) {
                        pill.translationX = tx + e.rawX - sx; pill.translationY = ty + e.rawY - sy; pillX = pill.translationX; pillY = pill.translationY
                        if (bin.parent == null) addView(bin, LayoutParams(Ui.dp(56), Ui.dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.dp(150) })
                        if (bin.visibility != View.VISIBLE) { bin.visibility = View.VISIBLE; bin.bringToFront(); pill.bringToFront() }
                        // מעל ה-✕: אדום וגדול יותר
                        val on = overBin()
                        (bin.background as GradientDrawable).setColor(Color.parseColor(if (on) "#E6FF3B30" else "#CC3A3A3C"))
                        bin.scaleX = if (on) 1.2f else 1f; bin.scaleY = bin.scaleX
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val drop = moved && overBin()
                    bin.visibility = View.GONE
                    if (drop) {
                        if (Editor.on) toggle()   // בלי כפתורים אין איך לכבות - מכבים את מצב העריכה
                        pillHidden = true
                        pillX = 0f; pillY = 0f
                        all.keys.forEach { it.pill.visibility = View.GONE; it.pill.translationX = 0f; it.pill.translationY = 0f }
                    } else if (!moved && e.actionMasked == MotionEvent.ACTION_UP) { if (v === penBtn) toggle() else openMenu() }
                }
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
    /** עמוד "בדיקת התרעה" (לא במצב עריכה): הלחיצות עוברות לעמוד, ובשורת בדיקה - מפעילים את ההתרעה מכאן */
    private var testPage = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) testPage = !Editor.on && screen.endsWith("|בדיקת התרעה")
        if ((!Editor.on && !testPage) || menu != null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; moved = false; target = Editor.forward(root, this, ev, null) }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > Editor.touchSlop || abs(ev.y - downY) > Editor.touchSlop) moved = true
                Editor.forward(root, this, ev, target)
            }
            MotionEvent.ACTION_UP -> {
                if (testPage) {
                    Editor.forward(root, this, ev, target)
                    if (!moved) testTap(ev)
                } else if (moved) Editor.forward(root, this, ev, target)
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

    /** צופר חירום 2 עובדת במלואה (שירות ההתרעות קיים) - שורות "בדיקת התרעה" עובדות לבד, אין מה להפעיל כאן */
    @Suppress("UNUSED_PARAMETER") private fun testTap(ev: MotionEvent) { }

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

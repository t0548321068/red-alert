package com.tal.redalert.editor

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * חלון העריכה של רכיב: צבע · גודל / פינות · טקסט · מיקום · הערה.
 * כל שינוי נראה מיד על הרכיב עצמו. ביטול = חזרה למה שהיה כשנפתח החלון
 */
@SuppressLint("ViewConstructor")
class Panel(private val layer: Layer, private val v: View, private val e: Edit) : LinearLayout(layer.context) {
    private val before = e.copy()
    /** שדות ששונו בחלון הזה - רק אותם מחזירים למקור כשמבטלים שינוי */
    private val touched = HashSet<String>().apply {
        if (e.color != null) add("color"); if (e.size != null) add("size"); if (e.radius != null) add("radius")
        if (e.text != null) add("text"); if (e.dx != 0f || e.dy != 0f) add("move") }
    private val sel = GradientDrawable().apply {
        setStroke(Ui.dp(2), Ui.BLUE, Ui.dp(5).toFloat(), Ui.dp(3).toFloat()); setColor(Color.TRANSPARENT) }
    private var tab = "color"
    private val tabs = LinearLayout(context)
    private val pane = FrameLayout(context)
    private val d = context.resources.displayMetrics.density

    private val tabList = when (e.kind) {
        "t" -> listOf("color" to "צבע", "size" to "גודל", "text" to "טקסט", "move" to "מיקום", "note" to "הערה")
        "i" -> listOf("color" to "צבע", "size" to "גודל", "move" to "מיקום", "note" to "הערה")
        else -> listOf("color" to "רקע", "radius" to "פינות", "move" to "מיקום", "note" to "הערה")
    }

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        isClickable = true
        elevation = Ui.dp(16).toFloat()
        setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(14))
        v.overlay.add(sel); sel.setBounds(0, 0, v.width, v.height)

        // כותרת: שם הרכיב, סוג ומסך, ו"רכיב מעל"
        addView(LinearLayout(context).apply {
            gravity = Gravity.TOP
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(Ui.tv(context, e.label, 16f, bold = true).apply { maxLines = 2 })
                addView(Ui.tv(context, (when (e.kind) { "t" -> "טקסט"; "i" -> "אייקון"; else -> "מסגרת / רקע" }) + " · " + e.screen.substringAfter('|'), 12f, Ui.GRAY))
            }, LayoutParams(0, -2, 1f))
            if (v.parent is View && v.parent !== layer.root)
                addView(Ui.tv(context, "רכיב מעל ›", 13f, Color.parseColor("#5AA9FF")).apply {
                    setPadding(Ui.dp(8), Ui.dp(4), 0, Ui.dp(4)); setOnClickListener { layer.selectParent(v) } })
        })
        addView(tabs, LayoutParams(-1, -2).apply { topMargin = Ui.dp(10); bottomMargin = Ui.dp(10) })
        addView(pane, LayoutParams(-1, -2))
        addView(LinearLayout(context).apply {
            addView(Ui.btn(context, "בלי שינוי", Color.TRANSPARENT, Ui.RED) { reset() }, LayoutParams(0, -2, 0.8f))
            addView(Ui.btn(context, "ביטול", Ui.CARD) { layer.closePanel(save = false) }, LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(8) })
            addView(Ui.btn(context, "שמירה", Ui.BLUE, Color.WHITE, true) { layer.closePanel(save = true) }, LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(8) })
        }, LayoutParams(-1, -2).apply { topMargin = Ui.dp(12) })
        drawTabs(); drawPane(); place()
    }

    /** למעלה או למטה - שלא יסתיר את הרכיב. בטקסט והערה תמיד למעלה (המקלדת עולה מלמטה) */
    private fun place() {
        val (top, bottom) = layer.insets()
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        val up = tab == "text" || tab == "note" || loc[1] + v.height / 2 > layer.height / 2
        val r = Ui.dp(22).toFloat()
        background = GradientDrawable().apply { setColor(Ui.BG)
            cornerRadii = if (up) floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        setPadding(Ui.dp(14), Ui.dp(12) + (if (up) top else 0), Ui.dp(14), Ui.dp(14) + (if (up) 0 else bottom))
        layoutParams = FrameLayout.LayoutParams(-1, -2, if (up) Gravity.TOP else Gravity.BOTTOM)
    }

    private fun modified(k: String) = when (k) {
        "color" -> e.color != null; "size" -> e.size != null; "radius" -> e.radius != null; "text" -> e.text != null
        "move" -> e.dx != 0f || e.dy != 0f; else -> e.note.isNotBlank()
    }
    private fun drawTabs() {
        tabs.removeAllViews()
        tabList.forEachIndexed { i, (k, name) ->
            tabs.addView(Ui.tv(context, name + if (modified(k)) " •" else "", 13f, if (k == tab) Color.WHITE else Ui.TEXT).apply {
                gravity = Gravity.CENTER; setPadding(0, Ui.dp(7), 0, Ui.dp(7))
                background = Ui.round(if (k == tab) Ui.BLUE else Ui.CARD, 10)
                setOnClickListener { tab = k; drawTabs(); drawPane(); place() }
            }, LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = Ui.dp(5) })
        }
    }

    /** שינוי חי: על הרכיב מיד */
    private fun live() {
        if (e.size != null && e.size == e.base.optDouble("size", -1.0).toFloat()) e.size = null
        if (e.radius != null && e.radius == e.base.optDouble("radius", -1.0).toFloat()) e.radius = null
        if (e.text != null && e.text == e.base.optString("text")) e.text = null
        if (e.color != null && e.color.equals(e.base.optString("color"), true)) e.color = null
        if (e.color != null) touched += "color"; if (e.size != null) touched += "size"; if (e.radius != null) touched += "radius"
        if (e.text != null) touched += "text"; if (e.dx != 0f || e.dy != 0f) touched += "move"
        val back = touched.filter { f -> when (f) { "color" -> e.color == null; "size" -> e.size == null; "radius" -> e.radius == null
            "text" -> e.text == null; else -> e.dx == 0f && e.dy == 0f } }.toSet()
        if (back.isNotEmpty()) Editor.restore(v, e, back)
        Editor.applyTo(v, e)
        sel.setBounds(0, 0, v.width, v.height)
        drawTabs()
        layer.refreshPill()
    }


    private fun drawPane() {
        pane.removeAllViews()
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        pane.addView(col, FrameLayout.LayoutParams(-1, -2))
        val b = e.base
        when (tab) {
            "color" -> {
                val now = e.color ?: b.optString("color")
                val row = LinearLayout(context).apply { setPadding(0, Ui.dp(2), 0, Ui.dp(2)) }
                palette.forEach { c ->
                    row.addView(View(context).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(c))
                            setStroke(Ui.dp(if (c.equals(now, true)) 3 else 1), if (c.equals(now, true)) Color.WHITE else Color.parseColor("#555555")) }
                        setOnClickListener { e.color = c; live(); drawPane() }
                    }, LayoutParams(Ui.dp(32), Ui.dp(32)).apply { marginEnd = Ui.dp(8) })
                }
                col.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) })
                val hex = edit(now, false).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; textDirection = View.TEXT_DIRECTION_LTR }
                col.addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(hex, LayoutParams(0, -2, 1f))
                    addView(Ui.btn(context, "החל", Ui.CARD) {
                        val s = hex.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }.uppercase()
                        if (Regex("^#([0-9A-F]{6}|[0-9A-F]{8})$").matches(s)) { e.color = s; live(); drawPane() }
                        else hex.error = "#RRGGBB"
                    }, LayoutParams(-2, -2).apply { marginStart = Ui.dp(8) })
                }, LayoutParams(-1, -2).apply { topMargin = Ui.dp(10) })
                col.addView(hint("מקורי: " + b.optString("color")))
            }
            "size", "radius" -> {
                val k = tab
                val base = b.optDouble(k, 0.0).toFloat()
                var now = (if (k == "size") e.size else e.radius) ?: base
                val max = if (k == "radius") 60 else 140
                val value = Ui.tv(context, fmt(now), 18f, bold = true).apply { gravity = Gravity.CENTER }
                val bar = SeekBar(context).apply { this.max = max * 2; progress = (now * 2).toInt() }
                fun set(x: Float) {
                    now = x.coerceIn(0f, max.toFloat()); value.text = fmt(now); bar.progress = (now * 2).toInt()
                    if (k == "size") e.size = now else e.radius = now
                    live()
                }
                col.addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER
                    addView(Ui.btn(context, "+", Ui.CARD) { set(now + 1) }, LayoutParams(Ui.dp(52), -2))
                    addView(value, LayoutParams(Ui.dp(96), -2))
                    addView(Ui.btn(context, "−", Ui.CARD) { set(now - 1) }, LayoutParams(Ui.dp(52), -2))
                })
                bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) set(p / 2f) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
                bar.layoutDirection = View.LAYOUT_DIRECTION_LTR
                col.addView(bar, LayoutParams(-1, -2).apply { topMargin = Ui.dp(8) })
                col.addView(hint("מקורי: ${fmt(base)} · " + when { k == "radius" -> "עיגול הפינות"; e.kind == "t" -> "גודל הגופן"; else -> "רוחב וגובה" }))
            }
            "text" -> {
                val t = edit(e.text ?: b.optString("text"), true)
                t.addTextChangedListener(watch { e.text = it; live() })
                col.addView(t)
                col.addView(hint("מקורי: " + b.optString("text")))
            }
            "move" -> {
                val desc = hint(moveText())
                fun arrow(s: String, x: Float, y: Float) = Ui.btn(context, s, Ui.CARD) { e.dx += x; e.dy += y; live(); desc.text = moveText() }
                col.addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR
                    addView(arrow("←", -1f, 0f), LayoutParams(Ui.dp(56), -2))
                    addView(LinearLayout(context).apply {
                        orientation = VERTICAL
                        addView(arrow("↑", 0f, -1f), LayoutParams(Ui.dp(56), -2))
                        addView(arrow("↓", 0f, 1f), LayoutParams(Ui.dp(56), -2).apply { topMargin = Ui.dp(6) })
                    }, LayoutParams(-2, -2).apply { setMargins(Ui.dp(6), 0, Ui.dp(6), 0) })
                    addView(arrow("→", 1f, 0f), LayoutParams(Ui.dp(56), -2))
                })
                col.addView(desc)
            }
            else -> {
                val t = edit(e.note, true).apply { hint = "מה לשנות ברכיב הזה" }
                t.addTextChangedListener(watch { e.note = it; drawTabs() })
                col.addView(t)
            }
        }
    }

    private fun fmt(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.1f".format(f)
    private fun moveText(): String {
        if (e.dx == 0f && e.dy == 0f) return "במקום המקורי · כל לחיצה = 1"
        return listOfNotNull(if (e.dx != 0f) "${fmt(kotlin.math.abs(e.dx))} " + (if (e.dx > 0) "ימינה" else "שמאלה") else null,
            if (e.dy != 0f) "${fmt(kotlin.math.abs(e.dy))} " + (if (e.dy > 0) "למטה" else "למעלה") else null).joinToString(", ")
    }
    private fun hint(s: String) = Ui.tv(context, s, 12f, Ui.GRAY).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dp(8), 0, 0) }
    private fun edit(s: String, multi: Boolean) = EditText(context).apply {
        setText(s); textSize = 15f; setTextColor(Ui.TEXT); setHintTextColor(Ui.GRAY)
        background = Ui.round(Ui.CARD, 10); setPadding(Ui.dp(10), Ui.dp(9), Ui.dp(10), Ui.dp(9))
        if (multi) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2; maxLines = 5; gravity = Gravity.TOP or Gravity.START }
        else isSingleLine = true
    }
    private fun watch(f: (String) -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    private fun reset() {
        e.color = null; e.size = null; e.radius = null; e.text = null; e.dx = 0f; e.dy = 0f; e.note = ""
        live(); drawPane()
    }

    /** סגירה: שמירה (עם תמונה קטנה של הרכיב לזיהוי) או ביטול */
    fun finish(save: Boolean) {
        v.overlay.remove(sel)
        if (!save) {
            e.color = before.color; e.size = before.size; e.radius = before.radius; e.text = before.text
            e.dx = before.dx; e.dy = before.dy; e.note = before.note
            if (touched.isNotEmpty()) Editor.restore(v, e, touched)
            Editor.applyTo(v, e)
        } else if (e.changed()) e.theme = if (com.tal.redalert.Prefs.darkTheme(context)) "dark" else "light"
        if (!e.changed()) Store.edits.remove(e.key)
    }

    companion object {
        /** הצבעים לבחירה מהירה (בקומפניון - כדי שיהיו מוכנים כבר בבניית החלון) */
        private val palette = listOf("#FFFFFF", "#F2F2F2", "#E6FFFFFF", "#AAAAAA", "#8E8E93", "#636366", "#30D158", "#69F0AE", "#4CAF50", "#2E7D32",
            "#0A84FF", "#4C7DFF", "#5AA9FF", "#FF3B30", "#D50000", "#FF9F0A", "#F08C00", "#7E57C2", "#1B1B1D", "#2C2C2E", "#000000", "#00000000")
    }
}

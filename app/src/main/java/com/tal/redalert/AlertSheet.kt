package com.tal.redalert

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * מסך תחתון במפה של התרעה מההתרעות האחרונות: כל האזורים, התאריך והשעה,
 * וכרטיס לכל שעת הגעה - היישובים מקובצים לפי אזור. נגרר למעלה עד שמכסה את המפה
 */
object AlertSheet {

    /** קבוצה אחת: שעת ההגעה, ולכל אזור (לפי הסדר) - היישובים שלו */
    data class Group(val at: Long, val districts: List<Pair<String, List<String>>>)

    /**
     * היישובים לפי שעת הגעה (לשנייה), ובתוך כל שעה לפי אזור - בסדר שבו הגיעו.
     * יישוב בלי אזור (כמו "בחלק מהאזורים בארץ") - הוא עצמו הכותרת, בלי שורת יישובים
     */
    fun groups(areas: List<String>, times: Map<String, Long>, ts: Long, district: (String) -> String?): List<Group> {
        val bySec = LinkedHashMap<Long, MutableList<String>>()
        areas.forEach { a -> bySec.getOrPut((times[a] ?: ts) / 1000) { mutableListOf() }.add(a) }
        return bySec.entries.sortedBy { it.key }.map { (sec, list) ->
            val d = LinkedHashMap<String, MutableList<String>>()
            list.forEach { a -> val k = district(a); if (k == null) d.getOrPut(a) { mutableListOf() } else d.getOrPut(k) { mutableListOf() }.add(a) }
            Group(sec * 1000, d.map { it.key to it.value.toList() })
        }
    }

    /** הכותרת: כל האזורים, כל אחד פעם אחת, לפי הסדר */
    fun summary(groups: List<Group>) = groups.flatMap { g -> g.districts.map { it.first } }.distinct().joinToString(", ")

    @SuppressLint("ClickableViewAccessibility")
    fun build(a: Activity, title: String, level: Int, ts: Long, areas: List<String>, times: Map<String, Long>,
              dark: Boolean, onPeek: (Int) -> Unit): View {
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
        val sheetBg = Color.parseColor(if (dark) "#121212" else "#F2F2F7")
        val cardBg = Color.parseColor(if (dark) "#1E1E1E" else "#FFFFFF")
        val ink = Color.parseColor(if (dark) "#F2F2F7" else "#1C1C1E")
        val muted = Color.parseColor(if (dark) "#A0A0A5" else "#6E6E73")
        val accent = Color.parseColor(MapKit.levelHex(level))
        val dmap = try { AreaData.districtMap(a) } catch (_: Exception) { emptyMap() }
        val gs = groups(areas, times, ts, { n -> dmap[n] ?: AreaData.match(a, n).firstNotNullOfOrNull { dmap[it] } })

        fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(a).apply {
            text = s; textSize = size; setTextColor(color); textDirection = View.TEXT_DIRECTION_RTL
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val sheet = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable().apply { setColor(sheetBg); cornerRadii = FloatArray(8) { i -> if (i < 4) dp(24).toFloat() else 0f } }
            elevation = dp(8).toFloat()
        }
        // החלק העליון - נגרר: ידית, כל האזורים, התאריך והשעה
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addView(View(a).apply { background = GradientDrawable().apply { setColor(muted); cornerRadius = dp(2).toFloat() }; alpha = 0.5f },
                LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
            addView(tv(summary(gs), 19f, ink, bold = true))
            val date = java.text.SimpleDateFormat("EEEE dd/MM/yyyy · HH:mm", java.util.Locale("iw")).format(java.util.Date(ts))
            addView(tv(date, 14f, muted).apply { setPadding(0, dp(2), 0, 0) })
        }
        sheet.addView(head)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(24)) }
        val timeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        gs.forEach { g ->
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(14))
                background = GradientDrawable().apply { setColor(cardBg); cornerRadius = dp(18).toFloat() }
            }
            card.addView(LinearLayout(a).apply {
                addView(tv(title, 13f, muted), LinearLayout.LayoutParams(0, -2, 1f))
                addView(tv(timeFmt.format(java.util.Date(g.at)), 13f, muted))
            })
            g.districts.forEach { (d, towns) ->
                card.addView(tv(d, 17f, accent, bold = true).apply { setPadding(0, dp(6), 0, 0) })
                if (towns.isNotEmpty()) card.addView(tv(towns.joinToString(", "), 15f, ink).apply { setPadding(0, dp(2), 0, 0) })
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        val scroll = ScrollView(a).apply { isVerticalScrollBarEnabled = false; addView(list) }
        sheet.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // גרירה: למטה - חלק מהמסך (המפה למעלה גלויה); למעלה - מכסה את המפה
        var down = 0f; var start = 0f; var moved = false
        fun collapsedY() = (sheet.height * 0.55f)
        fun snap(up: Boolean) = sheet.animate().translationY(if (up) 0f else collapsedY()).setDuration(200).start()
        head.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = ev.rawY; start = sheet.translationY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - down
                    if (Math.abs(dy) > dp(6)) moved = true
                    sheet.translationY = (start + dy).coerceIn(0f, collapsedY())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) snap(sheet.translationY > collapsedY() / 2)   // לחיצה - פותח / סוגר
                    else snap(sheet.translationY < collapsedY() / 2)
                }
            }
            true
        }
        sheet.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                sheet.translationY = collapsedY()
                onPeek((sheet.height - collapsedY()).toInt())
            }
        }
        return sheet
    }

    /** שורת הכותרת: בצבע של סוג ההתרעה, עם חץ חזרה ואייקון */
    fun bar(a: Activity, title: String, level: Int): View {
        fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
        return LinearLayout(a).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(AlertUi.color(level)))
            setPadding(dp(8), 0, dp(16), 0)
            addView(FrameLayout(a).apply {
                isClickable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
                contentDescription = "חזרה"
                setOnClickListener { a.finish() }
                addView(android.widget.ImageView(a).apply { setImageResource(R.drawable.ic_back); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) },
                    FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(TextView(a).apply {
                text = title; textSize = 19f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
                textDirection = View.TEXT_DIRECTION_RTL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.ImageView(a).apply { AlertUi.setIcon(this, AlertUi.icon(title, level)); imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) },
                LinearLayout.LayoutParams(dp(24), dp(24)))
        }
    }
}

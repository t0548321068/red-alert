package com.tal.redalert

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tal.redalert.MainActivity.C
import com.tal.redalert.MainActivity.Row
import com.tal.redalert.MainActivity.Perm
import com.tal.redalert.MainActivity.SameHeight

// בחירת אזורים נוספים ויישובים
// (חלק מהמסך הראשי - MainActivity; המצב עצמו נשמר שם)

/** חלון אזורים נוספים: הוספת יישוב / הוספת אזור שלם */
/** היישובים והאזורים הנוספים שנבחרו (בנפרד); נבחרו כולם - "כל הארץ" */
internal fun MainActivity.extraSelection(): Pair<List<String>, List<String>> {
    val P = AreaData.DISTRICT_PREFIX
    val mine = Prefs.cities(this)
    val allDists = AreaData.districts(this)
    val pickedD = mine.filter { it.startsWith(P) }.map { it.removePrefix(P) }
    val dists = if (AreaData.ALL_COUNTRY in pickedD || (allDists.isNotEmpty() && pickedD.containsAll(allDists)))
        listOf(AreaData.ALL_COUNTRY) else pickedD.sorted()
    val pickedT = mine.filterNot { it.startsWith(P) }
    // רשימת כל היישובים נטענת רק כשיש יישובים שנבחרו (טעינה כבדה - שלא יתקע את המסך הראשי)
    val allAreas = if (pickedT.isEmpty()) emptyList()
        else AreaData.areas(this).keys().asSequence().filter { !AreaData.isNational(it) }.toList()
    val towns = if (allAreas.isNotEmpty() && pickedT.toSet().containsAll(allAreas)) listOf(AreaData.ALL_COUNTRY) else pickedT.sorted()
    return towns to dists
}

internal fun MainActivity.showExtraAreas() = ouiPage("אזורים נוספים") { body, rebuild ->
    // שתי קבוצות: הוספת יישוב + היישובים שנבחרו, הוספת אזור + האזורים שנבחרו
    val P = AreaData.DISTRICT_PREFIX
    val dmap = AreaData.districtMap(this)
    val (towns, dists) = extraSelection()
    /** מחיקת שורה: יישוב / אזור; "כל הארץ" - מוחק את כל היישובים (או כל האזורים) */
    fun remove(name: String, whole: Boolean) {
        val keep = Prefs.cities(this).filter { k ->
            val isDist = k.startsWith(P)
            when {
                whole != isDist -> true
                name == AreaData.ALL_COUNTRY -> false
                whole -> k != P + name
                else -> k != name
            }
        }
        Prefs.setCities(this, keep); refresh(); rebuild()
    }
    fun group(add: String, onAdd: () -> Unit, title: String, names: List<String>, empty: String, whole: Boolean, small: (String) -> String) {
        val card = ouiCard()
        ouiRow(card, add) { onAdd() }
        ouiDivider(card)
        card.addView(text(title, 13f, C.MUTED).apply { setPadding(dp(18), dp(12), dp(18), dp(2)) })
        if (names.isEmpty()) ouiRow(card, empty, subColor = C.MUTED)
        names.forEachIndexed { i, name ->
            if (i > 0) ouiDivider(card)
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
            // שם היישוב, ומתחתיו האזור שלו
            val s = small(name)
            r.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(name, 16f, C.TEXT).apply { includeFontPadding = false })
                if (s.isNotEmpty()) addView(text(s, 12f, C.MUTED).apply { includeFontPadding = false; setPadding(0, dp(4), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            // סימן מחיקה בקצה השורה: קו אדום קצר (כמו ברשימות של סמסונג)
            r.addView(FrameLayout(this).apply {
                addView(View(this@showExtraAreas).apply {
                    background = GradientDrawable().apply { setColor(C.RED); cornerRadius = dp(1).toFloat() }
                }, FrameLayout.LayoutParams(dp(16), dp(2), Gravity.CENTER))
                setOnClickListener { remove(name, whole) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = -dp(12) })
            card.addView(r, LinearLayout.LayoutParams(-1, dp(if (s.isEmpty()) 48 else 56)))
        }
        ouiAdd(body, card)
    }
    group("הוספת יישוב", { showAreaPicker(whole = false) }, "יישובים שנבחרו", towns, "לא נבחרו יישובים", false) { if (it == AreaData.ALL_COUNTRY) "" else dmap[it] ?: "" }
    body.addView(View(this), LinearLayout.LayoutParams(-1, dp(10)))   // רווח בין הקבוצות
    group("הוספת אזור", { showAreaPicker(whole = true) }, "אזורים שנבחרו", dists, "לא נבחרו אזורים", true) { "" }
}

/**
 * בחירת אזורים נוספים בסגנון בחירת קבצים בסמסונג: "N נבחרו" למעלה, "הכל" (= כל הארץ) ו"ביטול",
 * חיפוש, ורשימה עם עיגול בחירה בכל שורה.
 * whole = בחירת אזורים שלמים (כל שורה אזור)
 */
internal fun MainActivity.showAreaPicker(whole: Boolean) {
    val dmap = AreaData.districtMap(this)
    val sel = LinkedHashSet(Prefs.cities(this))
    val P = AreaData.DISTRICT_PREFIX
    val allKey = P + AreaData.ALL_COUNTRY
    // "ברחבי הארץ" מאוחד עם "כל הארץ"
    val all = AreaData.areas(this).keys().asSequence().filter { !AreaData.isNational(it) }.toList()
    val districts = AreaData.districts(this)
    var query = ""
    val distCount: Map<String?, Int> = if (!whole) emptyMap() else all.groupingBy { dmap[it] }.eachCount()
    // זמן ההתגוננות של אזור שלם: מהקצר לארוך מבין היישובים שבו
    val distTime: Map<String, String> = if (!whole) emptyMap() else
        all.groupBy { dmap[it] }.mapNotNull { (dist, names) ->
            val secs = names.mapNotNull { AreaData.shelterSeconds(this, it) }
            if (dist == null || secs.isEmpty()) null else {
                val lo = secs.min(); val hi = secs.max()
                dist to (if (lo == hi) AreaData.shelterText(lo) else AreaData.shelterText(lo) + " – " + AreaData.shelterText(hi))
            }
        }.toMap()

    // הפרדה מלאה: חלון יישובים מטפל רק ביישובים, חלון אזורים רק באזורים
    // "כל הארץ" ישן (מגרסה קודמת) הופך לכל האזורים אחד אחד
    if (sel.remove(allKey)) sel.addAll(districts.map { P + it })
    fun key(name: String) = if (whole) P + name else name
    val kind = if (whole) districts else all
    fun covered(name: String) = key(name) in sel
    fun allOn() = kind.all { key(it) in sel }
    lateinit var render: () -> Unit

    val d = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG); layoutDirection = View.LAYOUT_DIRECTION_RTL }

    // כותרת: [עיגול "הכל"] "N נבחרו"
    val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(20), dp(10)) }
    val allDot = CheckDot(this, false)
    top.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(allDot, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(text("הכל", 12f, C.TEXT).apply { setPadding(0, dp(2), 0, 0) })
        setOnClickListener {
            // "הכל" - בוחר / מבטל את כל היישובים (או כל האזורים) בחלון הזה בלבד
            if (allOn()) kind.forEach { sel.remove(key(it)) } else kind.forEach { sel.add(key(it)) }
            render()
        }
    }, LinearLayout.LayoutParams(dp(44), -2))
    val count = text("", 26f, C.TEXT, bold = true)
    top.addView(count, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
    root.addView(top)

    // חיפוש
    val search = EditText(this).apply {
        hint = if (whole) "חיפוש אזור" else "חיפוש יישוב"; setSingleLine(); textSize = 16f
        setTextColor(C.TEXT); setHintTextColor(C.MUTED)
        background = GradientDrawable().apply { setColor(C.CARD2); cornerRadius = dp(24).toFloat() }
        setPadding(dp(20), 0, dp(20), 0)
    }
    root.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(12), 0, dp(12), dp(10)) })

    // הרשימה
    val items = ArrayList<String>()
    val adapter = object : android.widget.BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val name = items[p]
            val v = (cv as? LinearLayout) ?: LinearLayout(this@showAreaPicker).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(20), 0)
                minimumHeight = dp(66)
                addView(CheckDot(this@showAreaPicker, false), LinearLayout.LayoutParams(dp(22), dp(22)))
                // שם היישוב ומתחתיו האזור; בסוף השורה "זמן התגוננות" ומתחתיו הזמן במילים
                addView(LinearLayout(this@showAreaPicker).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("", 17f, C.TEXT).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
                    addView(text("", 13f, C.MUTED).apply { setSingleLine(); setPadding(0, dp(2), 0, 0) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(18) })
                // זמן ההתגוננות מיושר לשמאל (צמוד לקצה המסך)
                addView(LinearLayout(this@showAreaPicker).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("זמן התגוננות", 12f, C.MUTED).apply { gravity = Gravity.LEFT }, LinearLayout.LayoutParams(-1, -2))
                    addView(text("", 14f, C.TEXT).apply { gravity = Gravity.LEFT; setPadding(0, dp(2), 0, 0) },
                        LinearLayout.LayoutParams(-1, -2))
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            }
            (v.getChildAt(0) as CheckDot).apply { on = covered(name); invalidate() }
            val names = v.getChildAt(1) as LinearLayout
            (names.getChildAt(0) as TextView).text = name
            (names.getChildAt(1) as TextView).apply {
                // באזור שלם: מספר היישובים - כדי שהשורה תהיה בשתי שורות ותתיישר כמו ביישובים
                text = if (whole) distCount[name]?.let { "$it יישובים" } ?: "" else dmap[name] ?: ""
                visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
            val time = if (whole) distTime[name]
                else AreaData.shelterSeconds(this@showAreaPicker, name)?.let { AreaData.shelterText(it) }
            val end = v.getChildAt(2) as LinearLayout
            end.visibility = if (time == null) View.GONE else View.VISIBLE
            (end.getChildAt(1) as TextView).text = time ?: ""
            return v
        }
    }
    val list = android.widget.ListView(this).apply {
        this.adapter = adapter; divider = null; selector = android.graphics.drawable.ColorDrawable(0)
        clipToPadding = false; setPadding(0, 0, 0, dp(120))
    }
    root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

    val fill = {
        val t = query.trim(); val k = Prefs.areaKey(t)
        items.clear()
        items.addAll((if (whole) districts else all).filter {
            t.isEmpty() || it.contains(t) || (k.isNotEmpty() && Prefs.areaKey(it).contains(k))
        }.sortedWith(compareBy<String>({ if (t.isNotEmpty() && it.startsWith(t)) 0 else 1 }, { it })))
        adapter.notifyDataSetChanged()
    }
    render = {
        // תמיד "N נבחרו" (גם כשנבחר הכל); "כל הארץ" מופיע ברשימת הנבחרים
        val n = kind.count { key(it) in sel }
        val full = n == kind.size && n > 0
        count.text = when { n == 0 -> if (whole) "בחירת אזור שלם" else "בחירת יישוב"; else -> "$n נבחרו" }
        allDot.on = full; allDot.invalidate()
        adapter.notifyDataSetChanged()
    }
    list.setOnItemClickListener { _, _, p, _ ->
        val name = items[p]
        if (!sel.remove(key(name))) sel.add(key(name))
        render()
    }
    search.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(e: android.text.Editable?) { query = e.toString(); fill() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    fill(); render()

    // שומרים רק שמות אמיתיים - מנקה שאריות של שמות שנחתכו בפסיק בגרסאות קודמות
    val valid = all.toHashSet() + districts.map { P + it }
    showWithCancelSave(d, root) { Prefs.setCities(this@showAreaPicker, sel.filter { it in valid }); refresh() }
}

// ---- מסך הגדרות: כפתור לכל קטגוריה, וכל קטגוריה במסך משלה ----
